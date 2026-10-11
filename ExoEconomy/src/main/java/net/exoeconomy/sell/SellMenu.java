package net.exoeconomy.sell;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.event.SaleCompletedEvent;
import net.exoeconomy.api.event.SellRequestEvent;
import net.exoeconomy.booster.BoosterService;
import net.exoeconomy.config.Messages;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.tx.Journal;
import net.exoeconomy.core.tx.LedgerResult;
import net.exoeconomy.core.tx.TransactionCoordinator;
import net.exoeconomy.core.tx.TxKind;
import net.exoeconomy.menu.Items;
import net.exoeconomy.menu.Menu;
import net.exoeconomy.menu.MenuListener;
import net.exoeconomy.storage.Database;
import net.exoeconomy.util.Props;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.*;

/**
 * /sell. Slots 0-44 hold deposited items; the bottom row holds controls.
 *
 * <p>Item safety:
 * <ul>
 *   <li>Every click is cancelled unless it is a known-safe move inside the deposit area or the
 *       player's own inventory. Shift-clicks into the menu are performed by ExoEconomy so they can
 *       only reach deposit slots. Collect-to-cursor, bundle actions, creative clone and unknown
 *       actions are refused. Drags touching the control row are refused.</li>
 *   <li>The deposit contents are written to the escrow table on the next tick after any change.
 *       After a crash they become a claim (held for review by default).</li>
 *   <li>Closing, disconnecting, kicks, game mode changes and shutdown return every deposited item;
 *       what does not fit becomes a claim (/sell claim).</li>
 *   <li>Selling is two-step: the first click arms a quote for the exact contents, the second click
 *       within the confirm window sells exactly that quote. While a sale is processing no item can
 *       move.</li>
 * </ul>
 */
public final class SellMenu extends Menu implements MenuListener.GuardedMenu {
    private static final int DEPOSIT = 45;
    private static final int INFO = 45, SELL = 49, CLOSE = 53;

    private enum State { IDLE, ARMED, PROCESSING }

    private final String session = UUID.randomUUID().toString();
    private final SellService service;
    private State state = State.IDLE;
    private SellService.Quote quote;
    private SellService.Quote armed;
    private long armedUntil;
    private boolean updateScheduled;
    private boolean escrowWritten;

    public SellMenu(ExoEconomyPlugin plugin, Player viewer) {
        super(plugin, viewer);
        this.service = plugin.sell();
        this.inventory = Bukkit.createInventory(this, 54, Text.color(plugin.settings().selling().title()));
        Messages msg = plugin.settings().messages();
        ItemStack filler = Items.icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = DEPOSIT; i < 54; i++) inventory.setItem(i, filler);
        inventory.setItem(INFO, Items.icon(Material.BOOK, msg.raw("menus.sell.info-name"), msg.lines("menus.sell.info-lore")));
        inventory.setItem(CLOSE, Items.icon(Material.BARRIER, msg.raw("menus.sell.close-name"), msg.lines("menus.sell.close-lore")));
        refresh();
    }

    // ------------------------------------------------------------------ click policy

    @Override
    public boolean allowed(InventoryClickEvent e) {
        if (state == State.PROCESSING || closed()) return false;
        InventoryAction action = e.getAction();
        ClickType click = e.getClick();
        if (click == ClickType.CREATIVE || click == ClickType.UNKNOWN || click == ClickType.DOUBLE_CLICK) return false;
        if (action.name().contains("BUNDLE")) return false;
        if (REFUSED.contains(action.name())) return false;
        int raw = e.getRawSlot();
        if (raw < 0) return true; // clicking outside drops the cursor item, which belongs to the player
        if (raw < inventory.getSize()) return raw < DEPOSIT && depositAction(action);
        // Player inventory: everything except shift-moving into the menu (ExoEconomy does that itself).
        return action != InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }

    /** Compared by name so new or removed Paper actions can never be allowed by accident. */
    private static final Set<String> REFUSED = Set.of("NOTHING", "UNKNOWN", "CLONE_STACK", "COLLECT_TO_CURSOR");
    private static final Set<String> DEPOSIT_ACTIONS = Set.of("PICKUP_ALL", "PICKUP_HALF", "PICKUP_ONE", "PICKUP_SOME",
            "PLACE_ALL", "PLACE_ONE", "PLACE_SOME", "SWAP_WITH_CURSOR", "MOVE_TO_OTHER_INVENTORY", "HOTBAR_SWAP",
            "DROP_ALL_SLOT", "DROP_ONE_SLOT", "DROP_ALL_CURSOR", "DROP_ONE_CURSOR");

    private static boolean depositAction(InventoryAction a) { return DEPOSIT_ACTIONS.contains(a.name()); }

    @Override
    public boolean allowed(InventoryDragEvent e) {
        if (state == State.PROCESSING || closed()) return false;
        for (int raw : e.getRawSlots()) if (raw >= DEPOSIT && raw < inventory.getSize()) return false;
        return true;
    }

    @Override
    protected void click(InventoryClickEvent e) {
        int raw = e.getRawSlot();
        if (raw >= DEPOSIT && raw < inventory.getSize()) {
            if (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT) button(raw);
            return;
        }
        if (raw >= inventory.getSize() && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && state != State.PROCESSING) {
            // Move the stack ourselves so it can only land in deposit slots. The source slot is written
            // directly (not through the cancelled event) so the item can never exist in both places.
            var source = e.getClickedInventory();
            ItemStack moving = source == null ? null : source.getItem(e.getSlot());
            if (moving == null || moving.getType().isAir() || Items.isIcon(moving)) return;
            ItemStack rest = insert(moving.clone());
            source.setItem(e.getSlot(), rest);
            changed();
            return;
        }
        if (allowed(e)) {
            e.setCancelled(false);
            changed();
        }
    }

    @Override
    protected void drag(InventoryDragEvent e) {
        if (!allowed(e)) return;
        e.setCancelled(false);
        for (int raw : e.getRawSlots()) if (raw < DEPOSIT) { changed(); break; }
    }

    /** Moves a stack into deposit slots only, merging first. Returns what did not fit (or null). */
    private ItemStack insert(ItemStack item) {
        int remaining = item.getAmount();
        int max = item.getMaxStackSize();
        for (int i = 0; i < DEPOSIT && remaining > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot != null && slot.isSimilar(item) && slot.getAmount() < max) {
                int add = Math.min(remaining, max - slot.getAmount());
                slot.setAmount(slot.getAmount() + add);
                inventory.setItem(i, slot);
                remaining -= add;
            }
        }
        for (int i = 0; i < DEPOSIT && remaining > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot == null || slot.getType().isAir()) {
                ItemStack put = item.clone();
                put.setAmount(Math.min(remaining, max));
                inventory.setItem(i, put);
                remaining -= put.getAmount();
            }
        }
        if (remaining <= 0) return null;
        ItemStack rest = item.clone();
        rest.setAmount(remaining);
        return rest;
    }

    /** Any change disarms the confirmation and schedules one preview + escrow update for the next tick. */
    private void changed() {
        if (state == State.ARMED) state = State.IDLE;
        armed = null;
        if (updateScheduled) return;
        updateScheduled = true;
        later(() -> {
            updateScheduled = false;
            if (closed()) return;
            refresh();
            saveEscrow();
        });
    }

    private Map<Integer, ItemStack> contents() {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        for (int i = 0; i < DEPOSIT; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir() && !Items.isIcon(item)) out.put(i, item.clone());
        }
        return out;
    }

    private void saveEscrow() {
        Map<Integer, ItemStack> items = contents();
        if (items.isEmpty()) {
            if (escrowWritten) plugin.database().write(List.of(Database.deleteEscrow(session)));
            escrowWritten = false;
            return;
        }
        escrowWritten = true;
        plugin.database().write(List.of(Database.saveEscrow(session, viewer.getUniqueId(), "OPEN", null,
                ItemCodec.encode(items), ItemCodec.summary(items.values()))));
    }

    // ------------------------------------------------------------------ preview and buttons

    private void refresh() {
        quote = service.quote(viewer, contents());
        Messages msg = plugin.settings().messages();
        List<String> lore = new ArrayList<>();
        int shown = 0;
        for (SellService.Line l : quote.lines()) {
            if (shown++ == 8) { lore.add(msg.format("menus.sell.more-lines", "count", String.valueOf(quote.lines().size() - 8))); break; }
            lore.add(msg.format("menus.sell.line", "amount", String.valueOf(l.amount()), "item", Text.title(l.type().name().toLowerCase(Locale.ROOT)),
                    "unit", Money.plain(l.unit()), "multiplier", Money.plain(l.multiplier().stripTrailingZeros()),
                    "subtotal", plugin.sellLedger().format(Money.floor(l.subtotal(), Math.max(2, plugin.sellLedger().scale())))));
        }
        if (!quote.rejected().isEmpty()) {
            lore.add(msg.format("menus.sell.rejected-header", "count", String.valueOf(quote.rejected().stream().mapToInt(SellService.Rejected::amount).sum())));
            quote.rejected().stream().limit(5).forEach(r -> lore.add(msg.format("menus.sell.rejected-line", "item",
                    Text.title(r.type().name().toLowerCase(Locale.ROOT)), "amount", String.valueOf(r.amount()),
                    "reason", msg.raw(r.reasonKey()) + (r.detail() == null ? "" : " (" + r.detail() + ")"))));
        }
        lore.add("");
        String payout = plugin.sellLedger().format(quote.payout());
        Material icon;
        String name;
        if (state == State.ARMED && armed != null) {
            icon = Material.LIME_CONCRETE;
            name = msg.format("menus.sell.armed-name", "payout", plugin.sellLedger().format(armed.payout()));
            lore.addAll(lines(msg, "menus.sell.armed-lore", "seconds", String.valueOf(Math.max(0, (armedUntil - System.currentTimeMillis() + 999) / 1000))));
        } else if (state == State.PROCESSING) {
            icon = Material.YELLOW_CONCRETE;
            name = msg.raw("menus.sell.processing-name");
        } else if (quote.tooLarge()) {
            icon = Material.RED_CONCRETE;
            name = msg.format("menus.sell.too-large-name", "max", plugin.sellLedger().format(plugin.settings().selling().maxPayoutPerSale()));
        } else if (quote.sellable()) {
            icon = Material.EMERALD;
            name = msg.format("menus.sell.sell-name", "payout", payout);
            lore.addAll(msg.lines("menus.sell.sell-lore"));
        } else {
            icon = Material.GRAY_DYE;
            name = msg.raw("menus.sell.empty-name");
        }
        inventory.setItem(SELL, Items.icon(icon, name, lore));
    }

    private static List<String> lines(Messages msg, String key, String... values) {
        List<String> out = new ArrayList<>();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(values[i], values[i + 1]);
        for (String l : msg.lines(key)) out.add(Text.replace(l, map));
        return out;
    }

    private void button(int slot) {
        if (slot == CLOSE) { later(this::close); return; }
        if (slot != SELL || state == State.PROCESSING) return;
        Messages msg = plugin.settings().messages();
        if (!service.canSell(viewer)) { msg.send(viewer, "sell-not-here"); return; }
        if (!plugin.sellLedger().available()) { msg.send(viewer, "sell-economy-unavailable"); return; }
        if (!plugin.database().healthy()) { msg.send(viewer, "storage-unavailable"); return; }
        if (updateScheduled) return; // preview is about to change; ignore clicks on a stale button
        Map<Integer, ItemStack> now = contents();
        if (state == State.IDLE) {
            SellService.Quote fresh = service.quote(viewer, now);
            if (!fresh.sellable()) { refresh(); return; }
            armed = fresh;
            armedUntil = System.currentTimeMillis() + plugin.settings().selling().confirmWindowMs();
            state = State.ARMED;
            refresh();
            return;
        }
        // ARMED: second click sells exactly the armed quote, if nothing changed and it has not expired.
        if (armed == null || System.currentTimeMillis() > armedUntil || SellService.fingerprint(now) != armed.fingerprint()) {
            state = State.IDLE;
            armed = null;
            msg.send(viewer, "sell-confirm-expired");
            refresh();
            return;
        }
        SellRequestEvent event = new SellRequestEvent(viewer, armed.counts(), armed.payout());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            state = State.IDLE;
            armed = null;
            msg.send(viewer, "sell-cancelled");
            refresh();
            return;
        }
        state = State.PROCESSING;
        refresh();
        plugin.sellCoordinator().submit(new SaleOperation(armed));
    }

    @Override
    protected void tick() {
        if (state == State.ARMED && System.currentTimeMillis() > armedUntil) {
            state = State.IDLE;
            armed = null;
        }
        if (state != State.PROCESSING) refresh();
    }

    // ------------------------------------------------------------------ sale transaction

    private final class SaleOperation implements TransactionCoordinator.Operation {
        private final SellService.Quote quote;
        private final Map<Integer, ItemStack> removed = new LinkedHashMap<>();

        SaleOperation(SellService.Quote quote) { this.quote = quote; }

        @Override public UUID player() { return viewer.getUniqueId(); }
        @Override public TxKind kind() { return TxKind.SALE; }
        @Override public BigDecimal amount() { return quote.payout(); }

        @Override public String details() {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("session", session);
            d.put("payout", quote.payout().toPlainString());
            d.put("unrounded", quote.total().toPlainString());
            d.put("items", ItemCodec.summary(quote.sold().values()));
            for (SellService.Line l : quote.lines()) d.put("line." + l.key() + "@" + l.multiplier().toPlainString(), l.amount() + " x " + l.unit().toPlainString());
            return Props.write(d);
        }

        @Override public List<Journal.Work> beginWork(String txId) {
            Map<Integer, ItemStack> all = contents();
            return List.of(Database.saveEscrow(session, viewer.getUniqueId(), "SELLING", txId, ItemCodec.encode(all), ItemCodec.summary(all.values())));
        }

        @Override public String validate() {
            if (closed() || !viewer.isOnline()) return "sell-menu-closed";
            if (SellService.fingerprint(contents()) != quote.fingerprint()) return "sell-confirm-expired";
            return null;
        }

        @Override public void beforeLedger() {
            for (Map.Entry<Integer, ItemStack> e : quote.sold().entrySet()) {
                ItemStack current = inventory.getItem(e.getKey());
                removed.put(e.getKey(), current == null ? null : current.clone());
                inventory.setItem(e.getKey(), null);
            }
        }

        @Override public List<Journal.Work> committed(String txId, LedgerResult result) {
            state = State.IDLE;
            armed = null;
            Journal.Work escrow = remainingEscrow(); // must be written with COMMITTED, whatever happens below
            try {
                Messages msg = plugin.settings().messages();
                msg.send(viewer, "sell-completed", "payout", plugin.sellLedger().format(quote.payout()),
                        "count", String.valueOf(quote.sold().values().stream().mapToInt(ItemStack::getAmount).sum()), "tx", BoosterService.shortId(txId));
                Bukkit.getPluginManager().callEvent(new SaleCompletedEvent(viewer, txId, quote.counts(), quote.payout()));
                refresh();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Sale " + txId + " completed but its notification failed", e);
            }
            return List.of(escrow);
        }

        @Override public List<Journal.Work> rejected(String txId, String reason) {
            removed.forEach(inventory::setItem);
            state = State.IDLE;
            armed = null;
            plugin.settings().messages().send(viewer, reason == null ? "economy-refused" : reason);
            refresh();
            return List.of(remainingEscrow());
        }

        @Override public List<Journal.Work> uncertain(String txId, LedgerResult result) {
            state = State.IDLE;
            armed = null;
            plugin.settings().messages().send(viewer, "sale-uncertain", "tx", BoosterService.shortId(txId));
            plugin.notifyAdmins("admin-uncertain", "tx", BoosterService.shortId(txId), "player", viewer.getName());
            refresh();
            Map<Integer, ItemStack> held = new LinkedHashMap<>();
            removed.forEach((slot, item) -> { if (item != null) held.put(slot, item); });
            return List.of(remainingEscrow(), Database.insertClaim(new Database.Claim(UUID.randomUUID().toString(), viewer.getUniqueId(),
                    Database.CLAIM_REVIEW, txId, "sale outcome uncertain", ItemCodec.encode(held), ItemCodec.summary(held.values()), System.currentTimeMillis())));
        }

        @Override public void aborted(String reason) {
            if (!closed()) {
                state = State.IDLE;
                armed = null;
                refresh();
            }
            if (viewer.isOnline()) plugin.settings().messages().send(viewer, reason);
        }

        private Journal.Work remainingEscrow() {
            Map<Integer, ItemStack> left = contents();
            escrowWritten = !left.isEmpty();
            return left.isEmpty() ? Database.deleteEscrow(session)
                    : Database.saveEscrow(session, viewer.getUniqueId(), "OPEN", null, ItemCodec.encode(left), ItemCodec.summary(left.values()));
        }
    }

    // ------------------------------------------------------------------ closing

    /**
     * Returns every deposited item. Runs for close, disconnect, kick, death and shutdown. During
     * PROCESSING the economy has not been called yet (it runs within a single tick after the
     * journal write), so the items are still here and the pending sale aborts on validation.
     */
    @Override
    protected void onClose() {
        Map<Integer, ItemStack> items = contents();
        for (int i = 0; i < DEPOSIT; i++) inventory.setItem(i, null);
        List<Journal.Work> work = new ArrayList<>();
        work.add(Database.deleteEscrow(session));
        service.giveBack(viewer, items.values(), "returned from /sell", work);
    }
}
