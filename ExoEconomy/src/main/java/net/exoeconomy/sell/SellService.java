package net.exoeconomy.sell;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.config.Messages;
import net.exoeconomy.config.RewardSettings;
import net.exoeconomy.config.SellSettings;
import net.exoeconomy.core.Money;
import net.exoeconomy.integration.ExoSkillsBridge;
import net.exoeconomy.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/** Selling support: quotes, escrow persistence, returning items and delivering claims. */
public final class SellService {
    private final ExoEconomyPlugin plugin;
    private final ItemInspector inspector;
    private final Set<UUID> delivering = new HashSet<>();
    private ExoSkillsBridge exoSkills;

    public SellService(ExoEconomyPlugin plugin) {
        this.plugin = plugin;
        this.inspector = new ItemInspector(plugin.getLogger());
    }

    public ItemInspector inspector() { return inspector; }
    public void exoSkills(ExoSkillsBridge bridge) { this.exoSkills = bridge; }
    public boolean exoSkillsConnected() { return exoSkills != null; }

    public record Line(String key, Material type, int amount, BigDecimal unit, BigDecimal multiplier, BigDecimal subtotal) {}
    public record Rejected(Material type, int amount, String reasonKey, String detail) {}

    /**
     * An immutable sale offer. {@code sold} maps menu slots to the exact stacks being sold;
     * {@code fingerprint} identifies the menu contents the quote was made for.
     */
    public record Quote(List<Line> lines, List<Rejected> rejected, Map<Integer, ItemStack> sold, BigDecimal total,
                        BigDecimal payout, long fingerprint, boolean tooLarge) {
        public boolean sellable() { return payout.signum() > 0 && !tooLarge; }
        public Map<String, Integer> counts() {
            Map<String, Integer> out = new TreeMap<>();
            for (Line l : lines) out.merge(l.key(), l.amount(), Integer::sum);
            return out;
        }
    }

    /** Builds a quote from deposit slots. Server thread (reads ExoSkills and boosters). */
    public Quote quote(Player seller, Map<Integer, ItemStack> contents) {
        SellSettings s = plugin.settings().selling();
        PriceCatalogue catalogue = plugin.settings().catalogue();
        BigDecimal booster = s.boostersEnabled() ? plugin.boosters().book().multiplier(seller.getUniqueId(), RewardSettings.SELLING) : BigDecimal.ONE;
        Map<String, Line> lines = new LinkedHashMap<>();
        List<Rejected> rejected = new ArrayList<>();
        Map<Integer, ItemStack> sold = new LinkedHashMap<>();
        Map<Material, BigDecimal> skillCache = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Integer, ItemStack> e : contents.entrySet()) {
            ItemStack item = e.getValue();
            ItemInspector.Verdict v = inspector.inspect(seller, item, catalogue);
            if (!v.sellable()) { rejected.add(new Rejected(item.getType(), item.getAmount(), v.reasonKey(), v.detail())); continue; }
            BigDecimal mult = booster;
            if (!v.custom() && s.exoSkillsBonus() && exoSkills != null)
                mult = mult.multiply(skillCache.computeIfAbsent(item.getType(), m -> exoSkills.multiplier(seller, m)), MathContext.DECIMAL64);
            mult = mult.min(s.maxTotalMultiplier()).max(BigDecimal.ONE);
            BigDecimal unit = v.custom() ? v.unitPrice() : v.unitPrice().multiply(s.priceMultiplier());
            BigDecimal subtotal = unit.multiply(BigDecimal.valueOf(item.getAmount())).multiply(mult, MathContext.DECIMAL64);
            total = total.add(subtotal);
            sold.put(e.getKey(), item.clone());
            BigDecimal m = mult;
            lines.merge(v.key() + "@" + m.toPlainString(), new Line(v.key(), item.getType(), item.getAmount(), unit, m, subtotal),
                    (a, b) -> new Line(a.key(), a.type(), a.amount() + b.amount(), a.unit(), a.multiplier(), a.subtotal().add(b.subtotal())));
        }
        BigDecimal payout = Money.floor(total, plugin.sellLedger().scale());
        boolean tooLarge = payout.compareTo(s.maxPayoutPerSale()) > 0 || payout.compareTo(Money.MAX_OPERATION) > 0;
        return new Quote(List.copyOf(lines.values()), List.copyOf(rejected), Collections.unmodifiableMap(sold), total, payout,
                fingerprint(contents), tooLarge);
    }

    /** Identity of a set of slot contents, including full item data and amounts. */
    public static long fingerprint(Map<Integer, ItemStack> contents) {
        long h = 1125899906842597L;
        for (Map.Entry<Integer, ItemStack> e : new TreeMap<>(contents).entrySet()) {
            h = 31 * h + e.getKey();
            h = 31 * h + e.getValue().hashCode();
            h = 31 * h + e.getValue().getAmount();
        }
        return h;
    }

    // ------------------------------------------------------------------ returning items

    /**
     * Gives items back to a player. Whatever does not fit is stored as a claim (/sell claim) in
     * the same database transaction as {@code extra}; nothing is dropped on the ground or deleted.
     */
    public void giveBack(Player player, Collection<ItemStack> items, String reason, List<net.exoeconomy.core.tx.Journal.Work> extra) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            leftovers.addAll(player.getInventory().addItem(item.clone()).values());
        }
        List<net.exoeconomy.core.tx.Journal.Work> work = new ArrayList<>(extra);
        if (!leftovers.isEmpty()) {
            Map<Integer, ItemStack> map = new LinkedHashMap<>();
            for (int i = 0; i < leftovers.size(); i++) map.put(i, leftovers.get(i));
            work.add(Database.insertClaim(new Database.Claim(UUID.randomUUID().toString(), player.getUniqueId(), Database.CLAIM_CLAIMABLE,
                    null, reason, ItemCodec.encode(map), ItemCodec.summary(leftovers), System.currentTimeMillis())));
            plugin.settings().messages().send(player, "sell-items-stored", "count", String.valueOf(leftovers.stream().mapToInt(ItemStack::getAmount).sum()));
        }
        if (!work.isEmpty()) plugin.database().write(work).whenComplete((ok, error) -> {
            if (error != null) plugin.getLogger().severe("Could not store returned items for " + player.getName() + ": " + ItemCodec.summary(leftovers) + " (" + error + ")");
        });
    }

    // ------------------------------------------------------------------ claims

    /**
     * Delivers CLAIMABLE claims. Each claim is deleted in the database first and only then given,
     * so a claim can never be delivered twice; overflow becomes a new claim.
     */
    public void deliverClaims(Player player, boolean quietIfNone) {
        if (!delivering.add(player.getUniqueId())) return;
        Messages msg = plugin.settings().messages();
        plugin.database().claims(player.getUniqueId(), Database.CLAIM_CLAIMABLE).whenComplete((claims, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || claims == null) {
                delivering.remove(player.getUniqueId());
                msg.send(player, "storage-unavailable");
                return;
            }
            if (claims.isEmpty()) {
                delivering.remove(player.getUniqueId());
                if (!quietIfNone) msg.send(player, "claims-none");
                return;
            }
            deliverNext(player, new ArrayDeque<>(claims), 0);
        }));
    }

    private void deliverNext(Player player, Deque<Database.Claim> queue, int delivered) {
        Database.Claim claim = queue.poll();
        if (claim == null || !player.isOnline()) {
            delivering.remove(player.getUniqueId());
            if (delivered > 0 && player.isOnline()) plugin.settings().messages().send(player, "claims-delivered", "count", String.valueOf(delivered));
            return;
        }
        plugin.database().write(List.of(Database.deleteClaim(claim.id(), Database.CLAIM_CLAIMABLE))).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { deliverNext(player, queue, delivered); return; }
            Map<Integer, ItemStack> items;
            try {
                items = ItemCodec.decode(claim.items());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Claim " + claim.id() + " could not be decoded; restoring it for admin review: " + e);
                plugin.database().write(List.of(Database.insertClaim(new Database.Claim(claim.id(), claim.player(), Database.CLAIM_REVIEW,
                        claim.tx(), claim.reason() + "; undecodable", claim.items(), claim.summary(), claim.created()))));
                deliverNext(player, queue, delivered);
                return;
            }
            if (!player.isOnline()) {
                // Deleted but not given: put it back unchanged.
                plugin.database().write(List.of(Database.insertClaim(claim)));
                delivering.remove(player.getUniqueId());
                return;
            }
            giveBack(player, items.values(), claim.reason(), List.of());
            deliverNext(player, queue, delivered + 1);
        }));
    }

    public boolean canSell(Player player) {
        SellSettings s = plugin.settings().selling();
        return s.enabled() && s.gameModes().contains(player.getGameMode()) && !s.disabledWorlds().contains(player.getWorld().getName());
    }
}
