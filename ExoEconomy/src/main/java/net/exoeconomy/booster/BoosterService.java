package net.exoeconomy.booster;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.api.event.BoosterActivatedEvent;
import net.exoeconomy.api.event.BoosterExpiredEvent;
import net.exoeconomy.api.event.BoosterPurchaseEvent;
import net.exoeconomy.config.BoosterSettings;
import net.exoeconomy.config.Messages;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.Booster;
import net.exoeconomy.core.booster.BoosterBook;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.core.booster.Plan;
import net.exoeconomy.core.tx.Journal;
import net.exoeconomy.core.tx.LedgerResult;
import net.exoeconomy.core.tx.TransactionCoordinator;
import net.exoeconomy.core.tx.TxKind;
import net.exoeconomy.storage.Database;
import net.exoeconomy.util.Props;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Server-side booster management: purchases, grants, expiry ticking, announcements, persistence. */
public final class BoosterService {
    private final ExoEconomyPlugin plugin;
    private final BoosterBook book;

    public BoosterService(ExoEconomyPlugin plugin, List<Booster> stored) {
        this.plugin = plugin;
        this.book = new BoosterBook(plugin.settings().boosters().rules(), System::currentTimeMillis);
        book.restore(stored, online());
        persist();
    }

    public BoosterBook book() { return book; }

    public static Set<UUID> online() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
    }

    /** One repeating task for all boosters (every second). */
    public void tick() {
        List<BoosterBook.Change> changes = book.tick(online());
        for (BoosterBook.Change c : changes) {
            if (c.type() == BoosterBook.Change.Type.EXPIRED) {
                Bukkit.getPluginManager().callEvent(new BoosterExpiredEvent(c.booster()));
                announce(c.booster(), false, null);
            } else {
                Bukkit.getPluginManager().callEvent(new BoosterActivatedEvent(c.booster(), false));
                announce(c.booster(), true, null);
            }
        }
        persist();
    }

    public void persist() {
        List<Booster> dirty = book.drainDirty();
        if (!dirty.isEmpty()) plugin.database().write(List.of(Database.saveBoosters(dirty)));
    }

    public void reloadRules() { book.rules(plugin.settings().boosters().rules()); }

    public void join(Player player) { book.ownerOnline(player.getUniqueId()); persist(); }

    public void quit(Player player) { book.ownerOffline(player.getUniqueId()); persist(); }

    public void shutdown() {
        book.serverStopping();
        persist();
    }

    // ------------------------------------------------------------------ purchase

    /** Checks done before showing the confirmation: permission, plan, balance. Returns a message key or null. */
    public String precheck(Player player, BoosterSettings.Tier tier) {
        BoosterSpec spec = tier.spec();
        String scopePerm = "exoeconomy.boosters.buy." + spec.scope().name().toLowerCase(Locale.ROOT);
        if (!player.hasPermission(scopePerm) || (!tier.permission().isEmpty() && !player.hasPermission(tier.permission()))) return "booster-no-permission";
        if (!plugin.ledger().available()) return "economy-unavailable";
        if (!plugin.database().healthy()) return "storage-unavailable";
        Plan plan = book.plan(spec, owner(spec, player));
        if (plan.rejected()) return plan.reason();
        if (spec.price().signum() > 0) {
            Optional<BigDecimal> balance = plugin.ledger().balance(player.getUniqueId());
            if (balance.isEmpty()) return "economy-unavailable";
            if (balance.get().compareTo(spec.price()) < 0) return "insufficient-funds";
        }
        return null;
    }

    public Plan plan(Player player, BoosterSpec spec) { return book.plan(spec, owner(spec, player)); }

    private static UUID owner(BoosterSpec spec, Player player) {
        return spec.scope() == BoosterScope.PERSONAL ? player.getUniqueId() : null;
    }

    /**
     * Buys a tier exactly as shown in the confirmation menu ({@code tier} is that menu's snapshot,
     * so a reload in between cannot change the price, duration or multiplier the player agreed to).
     * The purchase is aborted only if the tier was removed or the stacking rules now refuse it.
     *
     * @param done called on the server thread with true when the booster is active, queued or extended
     */
    public void purchase(Player player, BoosterSettings.Tier tier, Consumer<Boolean> done) {
        Messages msg = plugin.settings().messages();
        String problem = precheck(player, tier);
        if (problem != null) { msg.send(player, problem); done.accept(false); return; }
        BoosterSpec spec = tier.spec();
        BoosterPurchaseEvent event = new BoosterPurchaseEvent(player, spec.tierId(), spec.scope(), spec.activity(), spec.multiplier(), spec.durationMs(), spec.price());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) { msg.send(player, "booster-purchase-cancelled"); done.accept(false); return; }
        String boosterId = UUID.randomUUID().toString();
        if (spec.price().signum() == 0) {
            try {
                Booster b = book.apply(spec, owner(spec, player), boosterId, null, online());
                persist();
                activated(player, b, !b.id.equals(boosterId));
                done.accept(true);
            } catch (IllegalStateException e) {
                msg.send(player, e.getMessage());
                done.accept(false);
            }
            return;
        }
        plugin.coordinator().submit(new PurchaseOperation(player, tier, boosterId, done));
    }

    private final class PurchaseOperation implements TransactionCoordinator.Operation {
        private final UUID player;
        private final String name;
        private final BoosterSettings.Tier tier;
        private final String boosterId;
        private final Consumer<Boolean> done;

        PurchaseOperation(Player player, BoosterSettings.Tier tier, String boosterId, Consumer<Boolean> done) {
            this.player = player.getUniqueId(); this.name = player.getName(); this.tier = tier; this.boosterId = boosterId; this.done = done;
        }

        @Override public UUID player() { return player; }
        @Override public TxKind kind() { return TxKind.BOOSTER_PURCHASE; }
        @Override public BigDecimal amount() { return tier.spec().price(); }
        @Override public String details() { return specDetails(tier.spec(), boosterId, player); }

        @Override public String validate() {
            Player p = Bukkit.getPlayer(player);
            if (p == null) return "player-offline";
            if (!plugin.settings().boosters().tiers().containsKey(tier.spec().tierId())) return "booster-offer-changed";
            Plan plan = book.plan(tier.spec(), owner(tier.spec(), p));
            return plan.rejected() ? plan.reason() : null;
        }

        @Override public List<Journal.Work> committed(String txId, LedgerResult result) {
            Player p = Bukkit.getPlayer(player);
            Booster b = book.apply(tier.spec(), tier.spec().scope() == BoosterScope.PERSONAL ? player : null, boosterId, txId, online());
            List<Booster> dirty = book.drainDirty();
            // The booster is active from here on; nothing below may undo the purchase or trigger a refund.
            try {
                if (p != null) {
                    activated(p, b, !b.id.equals(boosterId));
                    plugin.settings().messages().send(p, "booster-purchased", "tier", tier.displayName(), "price", plugin.ledger().format(tier.spec().price()), "tx", shortId(txId));
                }
                done.accept(true);
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Booster " + b.id + " is active but its notification failed", e);
            }
            return List.of(Database.saveBoosters(dirty));
        }

        @Override public List<Journal.Work> rejected(String txId, String reason) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) plugin.settings().messages().send(p, reason == null ? "economy-refused" : reason);
            done.accept(false);
            return List.of();
        }

        @Override public List<Journal.Work> uncertain(String txId, LedgerResult result) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) plugin.settings().messages().send(p, "transaction-uncertain", "tx", shortId(txId));
            plugin.notifyAdmins("admin-uncertain", "tx", shortId(txId), "player", name);
            done.accept(false);
            return List.of();
        }

        @Override public void aborted(String reason) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) plugin.settings().messages().send(p, reason);
            done.accept(false);
        }
    }

    public static String specDetails(BoosterSpec spec, String boosterId, UUID owner) {
        return Props.of("tier", spec.tierId(), "scope", spec.scope().name(), "activity", spec.activity(),
                "multiplier", spec.multiplier().toPlainString(), "duration_ms", String.valueOf(spec.durationMs()),
                "price", spec.price().toPlainString(), "booster", boosterId, "owner", owner == null ? "" : owner.toString());
    }

    /** Rebuilds a spec from journal details (admin resolution of an uncertain purchase). */
    public static BoosterSpec specFrom(Map<String, String> d) {
        return new BoosterSpec(d.get("tier"), BoosterScope.valueOf(d.get("scope")), d.get("activity"),
                new BigDecimal(d.get("multiplier")), Long.parseLong(d.get("duration_ms")), new BigDecimal(d.get("price")));
    }

    private void activated(Player buyer, Booster b, boolean extension) {
        BoosterInfo view = b.view();
        Bukkit.getPluginManager().callEvent(new BoosterActivatedEvent(view, extension));
        announce(view, true, buyer.getName());
    }

    // ------------------------------------------------------------------ grants and cancellation

    /** Free grant (admin command or API). Returns the booster or a message key explaining the refusal. */
    public Object grant(BoosterSpec spec, UUID owner, String sourceTx, String byName) {
        try {
            Booster b = book.apply(spec, spec.scope() == BoosterScope.PERSONAL ? owner : null, UUID.randomUUID().toString(), sourceTx, online());
            persist();
            Bukkit.getPluginManager().callEvent(new BoosterActivatedEvent(b.view(), false));
            announce(b.view(), true, byName);
            return b.view();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    /** Activates the booster of an uncertain purchase an admin confirmed as paid; uses the original booster id. */
    public Object grantResolved(Map<String, String> details, String txId) {
        BoosterSpec spec = specFrom(details);
        String owner = details.getOrDefault("owner", "");
        try {
            Booster b = book.apply(spec, owner.isEmpty() ? null : UUID.fromString(owner), details.get("booster"), txId, online());
            persist();
            return b.view();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    public Optional<BoosterInfo> cancel(String id) {
        Optional<BoosterInfo> cancelled = book.cancel(id, online());
        persist();
        return cancelled;
    }

    public Optional<BoosterInfo> find(String idOrPrefix) {
        List<BoosterInfo> matches = book.snapshot().stream().filter(b -> b.id().startsWith(idOrPrefix)).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Looks up the purchase transaction a booster came from (for refunds), if any. Completes off the server thread. */
    public java.util.concurrent.CompletableFuture<Optional<String>> sourceTx(String boosterId) {
        return plugin.database().submit(c -> {
            try (var p = c.prepareStatement("SELECT source_tx FROM boosters WHERE id=?")) {
                p.setString(1, boosterId);
                try (var r = p.executeQuery()) { return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.<String>empty(); }
            }
        });
    }

    // ------------------------------------------------------------------ announcements

    private void announce(BoosterInfo b, boolean activated, String byName) {
        BoosterSettings.Announcements a = plugin.settings().boosters().announcements();
        Messages msg = plugin.settings().messages();
        String[] values = values(b, byName);
        if (b.scope() == BoosterScope.GLOBAL) {
            if (activated ? !a.globalActivated() : !a.globalExpired()) return;
            String key = activated ? "booster-global-activated" : "booster-global-expired";
            for (Player p : Bukkit.getOnlinePlayers()) msg.send(p, key, values);
            msg.send(Bukkit.getConsoleSender(), key, values);
        } else {
            if (activated ? !a.personalActivated() : !a.personalExpired()) return;
            Player owner = Bukkit.getPlayer(b.owner());
            if (owner != null) msg.send(owner, activated ? "booster-personal-activated" : "booster-personal-expired", values);
        }
    }

    public String[] values(BoosterInfo b, String byName) {
        BoosterSettings.Tier tier = plugin.settings().boosters().tiers().get(b.tierId());
        long now = System.currentTimeMillis();
        String owner = "";
        if (b.owner() != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(b.owner());
            owner = op.getName() == null ? b.owner().toString() : op.getName();
        }
        return new String[]{"tier", tier == null ? Text.title(b.tierId()) : tier.displayName(), "multiplier", Money.plain(b.multiplier()),
                "activity", activityName(b.activity()), "remaining", Text.duration(b.remaining(now)), "player", byName == null ? "" : byName,
                "owner", owner, "state", b.state(), "id", shortId(b.id())};
    }

    public String activityName(String activity) {
        Messages msg = plugin.settings().messages();
        String key = "activity-names." + activity;
        return msg.has(key) ? msg.raw(key) : Text.title(activity);
    }

    public static String shortId(String id) { return id == null ? "-" : id.length() > 8 ? id.substring(0, 8) : id; }
}
