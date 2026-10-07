package dev.exo.dailyspinner.spin;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.animation.ReelPlan;
import dev.exo.dailyspinner.animation.SpinAnimation;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.Settings;
import dev.exo.dailyspinner.cooldown.CooldownCalculator;
import dev.exo.dailyspinner.menu.Placeholders;
import dev.exo.dailyspinner.menu.RewardItems;
import dev.exo.dailyspinner.menu.SpinnerMenu;
import dev.exo.dailyspinner.reward.CommandTemplate;
import dev.exo.dailyspinner.reward.Rarity;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.reward.RewardSnapshot;
import dev.exo.dailyspinner.reward.RewardType;
import dev.exo.dailyspinner.storage.PendingItem;
import dev.exo.dailyspinner.storage.PlayerData;
import dev.exo.dailyspinner.storage.ReservationResult;
import dev.exo.dailyspinner.storage.SpinRecord;
import dev.exo.dailyspinner.storage.SpinSource;
import dev.exo.dailyspinner.storage.SpinStatus;
import dev.exo.dailyspinner.util.TimeFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Orchestrates spins and pending-reward claims.
 *
 * <p>Order of operations for a spin:</p>
 * <ol>
 *   <li>Main thread: acquire the per-player operation guard, pick a reward and build a snapshot.</li>
 *   <li>DB thread: one transaction checks entitlement, consumes it and stores the snapshot (RESERVED).</li>
 *   <li>Main thread: play the cosmetic animation (or skip it when the menu is gone).</li>
 *   <li>DB thread: conditional RESERVED -> DELIVERING transition; only one caller can ever win it.</li>
 *   <li>Main thread: give items / run commands from the <em>stored</em> snapshot.</li>
 *   <li>DB thread: store overflow items as pending and mark DELIVERED in one transaction.</li>
 * </ol>
 * All state in this class is confined to the server thread.
 */
public final class SpinService {

    private static final int CLAIM_BATCH = 54;
    private static final long RETURN_TO_IDLE_TICKS = 50L;

    private final ExoDailySpinner plugin;
    private final OperationGuard guard;
    private final Map<UUID, ActiveSpin> active = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static final class ActiveSpin {
        final OperationGuard.Token token;
        final SpinRecord record;
        final boolean resumed;
        SpinnerMenu menu;
        SpinAnimation animation;
        boolean finalizing;
        boolean handedOut;

        ActiveSpin(OperationGuard.Token token, SpinRecord record, boolean resumed) {
            this.token = token;
            this.record = record;
            this.resumed = resumed;
        }
    }

    public SpinService(ExoDailySpinner plugin, OperationGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    /** State key used by menus: ready, resume, cooldown, running or unavailable. */
    public String stateFor(UUID player, PlayerData data, ConfigBundle bundle) {
        if (bundle.rewards().isEmpty()) {
            return "unavailable";
        }
        if (guard.isBusy(player)) {
            return "running";
        }
        if (data.activeSpin()) {
            return "resume";
        }
        long remaining = CooldownCalculator.remaining(data.lastDailySpin(), bundle.settings().cooldownMillis(), System.currentTimeMillis());
        if (remaining == 0 || data.bonusSpins() > 0) {
            return "ready";
        }
        return "cooldown";
    }

    public boolean isBusy(UUID player) {
        return guard.isBusy(player);
    }

    // =============================================================== spin request

    public void requestSpin(Player player, SpinnerMenu menu) {
        ConfigBundle bundle = plugin.bundle();
        UUID id = player.getUniqueId();
        if (bundle == null) {
            return;
        }
        if (menu.bundle() != bundle) {
            // Configuration was reloaded since this menu opened; reopen with the current one.
            plugin.menus().openSpinner(player);
            return;
        }
        if (!player.hasPermission("exodailyspinner.use")) {
            plugin.sound(player, "denied");
            bundle.messages().send(player, "no-permission");
            return;
        }
        if (bundle.rewards().isEmpty()) {
            plugin.sound(player, "denied");
            bundle.messages().send(player, "no-rewards");
            return;
        }
        Optional<OperationGuard.Token> token = guard.tryAcquire(id, OperationGuard.Kind.SPIN);
        if (token.isEmpty()) {
            plugin.sound(player, "denied");
            bundle.messages().send(player, "operation-busy");
            return;
        }
        RewardSnapshot snapshot;
        try {
            snapshot = snapshot(bundle.rewards().pick(random));
        } catch (RuntimeException e) {
            guard.release(token.get());
            plugin.getLogger().log(Level.SEVERE, "Could not prepare a reward snapshot; no spin was consumed.", e);
            bundle.messages().send(player, "error-generic");
            return;
        }
        String operationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Settings settings = bundle.settings();
        menu.setReserving();
        plugin.debug("Reserving spin " + operationId + " for " + player.getName() + " (candidate " + snapshot.rewardId() + ")");
        plugin.database()
                .submit(repo -> repo.reserve(id, operationId, snapshot, now, settings.cooldownMillis(), settings.consumeOrder()))
                .whenComplete((result, error) -> plugin.sync(() -> onReserved(id, token.get(), menu, result, error)));
    }

    /** Builds the immutable snapshot that is persisted with the spin. Main thread. */
    public static RewardSnapshot snapshot(RewardDefinition reward) {
        byte[] display = reward.displayItem().serializeAsBytes();
        String announcement = reward.shouldAnnounce() ? (reward.announcement() == null ? "" : reward.announcement()) : null;
        return new RewardSnapshot(reward.id(), reward.type(), reward.itemData(), reward.amount(), reward.commands(),
                display, reward.displayNameRaw(), reward.rarity().id(), announcement);
    }

    private void onReserved(UUID id, OperationGuard.Token token, SpinnerMenu menu, ReservationResult result, Throwable error) {
        if (!guard.isHeld(token)) {
            // Player left meanwhile; any reserved spin is resumed on their next join.
            return;
        }
        Player player = Bukkit.getPlayer(id);
        ConfigBundle bundle = plugin.bundle();
        if (error != null) {
            guard.release(token);
            if (player != null) {
                bundle.messages().send(player, "error-generic");
            }
            refreshMenu(menu);
            return;
        }
        switch (result.outcome()) {
            case UNAVAILABLE, DUPLICATE -> {
                guard.release(token);
                if (player != null) {
                    plugin.sound(player, "denied");
                    bundle.messages().send(player, "spin-not-ready",
                            Placeholder.unparsed("time", TimeFormat.compact(result.remainingMillis())));
                }
                refreshMenu(menu);
                return;
            }
            case EXISTING -> {
                if (result.spin().status() != SpinStatus.RESERVED) {
                    guard.release(token);
                    if (player != null) {
                        bundle.messages().send(player, "operation-busy");
                    }
                    refreshMenu(menu);
                    return;
                }
                if (player != null) {
                    bundle.messages().send(player, "spin-resumed");
                }
            }
            case RESERVED -> plugin.debug("Spin " + result.spin().id() + " reserved (" + result.spin().source()
                    + ", reward " + result.spin().snapshot().rewardId() + ")");
        }
        ActiveSpin spin = new ActiveSpin(token, result.spin(), result.outcome() == ReservationResult.Outcome.EXISTING);
        if (player == null || !player.isOnline()) {
            guard.release(token);
            return;
        }
        active.put(id, spin);
        if (plugin.menus().manager().isCurrent(player, menu)) {
            spin.menu = menu;
            startAnimation(spin, menu);
        } else {
            finalizeSpin(spin);
        }
    }

    private void startAnimation(ActiveSpin spin, SpinnerMenu menu) {
        ConfigBundle bundle = menu.bundle();
        Settings s = bundle.settings();
        RewardSnapshot snapshot = spin.record.snapshot();
        ItemStack winner = RewardItems.winnerItem(bundle, snapshot);
        Rarity rarity = RewardItems.rarity(bundle, snapshot.rarityId());
        ReelPlan<ItemStack> plan = ReelPlan.create(bundle.menus().spinner().reelSlots().size(),
                bundle.menus().spinner().centerIndex(), s.animationSteps(), winner, menu::randomReelItem,
                s.minDelayTicks(), s.maxDelayTicks(), s.easing());
        spin.animation = new SpinAnimation(plugin, menu, plan, winner, rarity, s.borderCycle(), () -> finalizeSpin(spin));
        spin.animation.start();
    }

    /** Called when a spinner menu closes. Closing during an animation completes the spin instantly. */
    public void spinnerClosed(SpinnerMenu menu, boolean disconnect) {
        ActiveSpin spin = active.get(menu.viewer());
        if (spin == null || spin.menu != menu) {
            return;
        }
        if (spin.animation != null) {
            spin.animation.stop();
        }
        spin.menu = null;
        if (disconnect && !spin.finalizing) {
            // Leave the spin RESERVED; it is delivered when the player returns.
            cleanup(spin);
            return;
        }
        finalizeSpin(spin);
    }

    // =============================================================== delivery

    private void finalizeSpin(ActiveSpin spin) {
        if (spin.finalizing) {
            return;
        }
        spin.finalizing = true;
        if (spin.animation != null) {
            spin.animation.stop();
        }
        UUID id = spin.record.player();
        String spinId = spin.record.id();
        plugin.database()
                .submit(repo -> repo.beginDelivery(spinId, id, System.currentTimeMillis()))
                .whenComplete((record, error) -> plugin.sync(() -> deliver(spin, record, error)));
    }

    private void deliver(ActiveSpin spin, Optional<SpinRecord> stored, Throwable error) {
        UUID id = spin.record.player();
        String spinId = spin.record.id();
        if (error != null) {
            cleanup(spin);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.bundle().messages().send(p, "error-generic");
            }
            return;
        }
        if (stored.isEmpty()) {
            plugin.debug("Spin " + spinId + " was already handled; ignoring duplicate completion.");
            cleanup(spin);
            return;
        }
        SpinRecord record = stored.get();
        Player player = Bukkit.getPlayer(id);
        spin.handedOut = true;
        if (player == null || !player.isOnline()) {
            plugin.database().submit(repo -> repo.revertDelivery(spinId, System.currentTimeMillis()))
                    .whenComplete((ok, err) -> plugin.sync(() -> cleanup(spin)));
            return;
        }
        if (record.snapshot().type() == RewardType.ITEM) {
            deliverItem(spin, player, record);
        } else {
            deliverCommands(spin, player, record);
        }
    }

    private void deliverItem(ActiveSpin spin, Player player, SpinRecord record) {
        RewardSnapshot snapshot = record.snapshot();
        ItemStack template;
        try {
            template = ItemStack.deserializeBytes(snapshot.itemData());
        } catch (RuntimeException e) {
            fail(spin, player, record, "stored item could not be read: " + e.getMessage());
            return;
        }
        if (player.isDead()) {
            plugin.database().submit(repo -> repo.moveReservedToPending(record.id(), record.player(), System.currentTimeMillis()))
                    .whenComplete((ok, err) -> plugin.sync(() -> {
                        if (err != null) {
                            plugin.reconciliation().record("DB_ERROR", record, "could not store item for dead player; spin left DELIVERING");
                        }
                        complete(spin, record, snapshot.amount());
                    }));
            return;
        }
        int leftover = give(player, template, snapshot.amount());
        String detail = "given=" + (snapshot.amount() - leftover) + " stored=" + leftover;
        plugin.database()
                .submit(repo -> repo.completeDelivery(record.id(), record.player(), leftover > 0 ? snapshot.itemData() : null,
                        leftover, System.currentTimeMillis(), detail))
                .whenComplete((ok, err) -> plugin.sync(() -> {
                    if (err != null || !Boolean.TRUE.equals(ok)) {
                        plugin.reconciliation().record("DB_ERROR", record,
                                "items handed out (" + detail + ") but completion was not recorded; it will be flagged UNCERTAIN on next start");
                    }
                    complete(spin, record, leftover);
                }));
    }

    private void deliverCommands(ActiveSpin spin, Player player, SpinRecord record) {
        RewardSnapshot snapshot = record.snapshot();
        List<String> commands = new ArrayList<>();
        try {
            for (String command : snapshot.commands()) {
                commands.add(CommandTemplate.apply(command, player.getName(), player.getUniqueId()));
            }
        } catch (IllegalArgumentException e) {
            fail(spin, player, record, e.getMessage());
            return;
        }
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (String command : commands) {
            try {
                if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    ok++;
                } else {
                    failed.add(command);
                }
            } catch (RuntimeException e) {
                failed.add(command);
                plugin.getLogger().log(Level.WARNING, "Reward command failed: " + command, e);
            }
        }
        String detail = "commands ok=" + ok + " failed=" + failed.size();
        if (!failed.isEmpty()) {
            plugin.reconciliation().record("COMMAND_FAILED", record, "not replayed automatically; failed: " + failed);
        }
        plugin.database()
                .submit(repo -> repo.completeDelivery(record.id(), record.player(), null, 0, System.currentTimeMillis(), detail))
                .whenComplete((done, err) -> plugin.sync(() -> {
                    if (err != null || !Boolean.TRUE.equals(done)) {
                        plugin.reconciliation().record("DB_ERROR", record,
                                "commands executed (" + detail + ") but completion was not recorded; it will be flagged UNCERTAIN on next start");
                    }
                    complete(spin, record, 0);
                }));
    }

    private void fail(ActiveSpin spin, Player player, SpinRecord record, String reason) {
        plugin.reconciliation().record("FAILED", record, reason);
        plugin.database().submit(repo -> repo.failDelivery(record.id(), System.currentTimeMillis(), reason))
                .whenComplete((ok, err) -> plugin.sync(() -> {
                    plugin.bundle().messages().send(player, "delivery-failed");
                    cleanup(spin);
                }));
    }

    /** Adds {@code amount} copies of {@code template}; returns how many did not fit. */
    public static int give(Player player, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int n = Math.min(max, remaining);
            ItemStack stack = template.clone();
            stack.setAmount(n);
            stacks.add(stack);
            remaining -= n;
        }
        int leftover = 0;
        for (ItemStack rest : player.getInventory().addItem(stacks.toArray(new ItemStack[0])).values()) {
            leftover += rest.getAmount();
        }
        return leftover;
    }

    private void complete(ActiveSpin spin, SpinRecord record, int stored) {
        Player player = Bukkit.getPlayer(record.player());
        ConfigBundle bundle = plugin.bundle();
        SpinnerMenu menu = spin.menu;
        // Release the operation first: cosmetic feedback must never be able to leave the player locked.
        cleanup(spin);
        if (player != null && bundle != null) {
            try {
                showResult(player, bundle, record, stored);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not display the spin result (reward was delivered)", e);
            }
        }
        if (menu != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> refreshMenu(menu), RETURN_TO_IDLE_TICKS);
        }
        if (spin.resumed && player != null && menu == null) {
            // Deliver any further interrupted spins one at a time.
            Bukkit.getScheduler().runTaskLater(plugin, () -> resume(player), 20L);
        }
    }

    private void showResult(Player player, ConfigBundle bundle, SpinRecord record, int stored) {
        RewardSnapshot snapshot = record.snapshot();
        Rarity rarity = RewardItems.rarity(bundle, snapshot.rarityId());
        int amount = snapshot.type() == RewardType.ITEM ? snapshot.amount() : 1;
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("reward", snapshot.displayName()),
                Placeholder.parsed("rarity", rarity.name()),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("stored", String.valueOf(stored)),
                Placeholder.unparsed("player", player.getName()),
                Placeholder.parsed("source", bundle.messages().raw(record.source() == SpinSource.DAILY ? "source-daily" : "source-bonus").get(0)));
        bundle.messages().send(player, "spin-result", resolver);
        if (stored > 0) {
            bundle.messages().send(player, "spin-result-stored", resolver);
        }
        Settings s = bundle.settings();
        if (s.titles()) {
            player.showTitle(Title.title(
                    bundle.messages().line("result-title", resolver),
                    bundle.messages().line("result-subtitle", resolver),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2200), Duration.ofMillis(600))));
        }
        plugin.sound(player, rarity.celebrate() ? "win-rare" : "win");
        if (s.particle() != null) {
            Location loc = player.getLocation().add(0, 1.0, 0);
            player.spawnParticle(s.particle(), loc, s.particleCount(), s.particleSpread(), s.particleSpread(),
                    s.particleSpread(), s.particleSpeed());
        }
        if (s.announcements() && snapshot.announcement() != null) {
            String template = snapshot.announcement().isEmpty()
                    ? String.join("\n", bundle.messages().raw("announce"))
                    : snapshot.announcement();
            Component message = bundle.text().parse(template,
                    TagResolver.resolver(resolver, Placeholder.parsed("prefix", bundle.messages().raw("prefix").get(0))));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(message);
            }
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }

    private void refreshMenu(SpinnerMenu menu) {
        if (menu == null || !menu.isViewing()) {
            return;
        }
        plugin.database().submit(repo -> repo.loadPlayer(menu.viewer()))
                .whenComplete((data, error) -> plugin.sync(() -> {
                    if (menu.isViewing() && menu.state() != SpinnerMenu.State.SPINNING) {
                        menu.setIdle(error == null ? data : null);
                    }
                }));
    }

    private void cleanup(ActiveSpin spin) {
        active.remove(spin.record.player(), spin);
        guard.release(spin.token);
    }

    // =============================================================== resume

    /** Delivers spins that were reserved but never delivered (disconnect, restart, closed during reservation). */
    public void resume(Player player) {
        if (!player.isOnline() || plugin.bundle() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        if (guard.isBusy(id)) {
            return;
        }
        plugin.database().submit(repo -> repo.findReservedSpins(id)).whenComplete((spins, error) -> plugin.sync(() -> {
            if (error != null || spins.isEmpty() || !player.isOnline()) {
                return;
            }
            Optional<OperationGuard.Token> token = guard.tryAcquire(id, OperationGuard.Kind.RESUME);
            if (token.isEmpty()) {
                return;
            }
            ActiveSpin spin = new ActiveSpin(token.get(), spins.get(0), true);
            active.put(id, spin);
            plugin.bundle().messages().send(player, "spin-resuming");
            finalizeSpin(spin);
        }));
    }

    // =============================================================== claims

    public void claim(Player player, boolean reopenMain) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null) {
            return;
        }
        if (!player.hasPermission("exodailyspinner.claim")) {
            bundle.messages().send(player, "no-permission");
            return;
        }
        UUID id = player.getUniqueId();
        Optional<OperationGuard.Token> token = guard.tryAcquire(id, OperationGuard.Kind.CLAIM);
        if (token.isEmpty()) {
            plugin.sound(player, "denied");
            bundle.messages().send(player, "operation-busy");
            return;
        }
        String claimOp = UUID.randomUUID().toString();
        plugin.database().submit(repo -> repo.beginClaim(id, claimOp, CLAIM_BATCH, System.currentTimeMillis()))
                .whenComplete((items, error) -> plugin.sync(() -> onClaimLocked(player, token.get(), claimOp, items, error, reopenMain)));
    }

    private void onClaimLocked(Player player, OperationGuard.Token token, String claimOp, List<PendingItem> items,
                               Throwable error, boolean reopenMain) {
        ConfigBundle bundle = plugin.bundle();
        if (error != null) {
            guard.release(token);
            bundle.messages().send(player, "error-generic");
            return;
        }
        if (items.isEmpty()) {
            guard.release(token);
            plugin.sound(player, "denied");
            bundle.messages().send(player, "claim-none");
            return;
        }
        Map<Long, Integer> remaining = new LinkedHashMap<>();
        if (!player.isOnline()) {
            for (PendingItem item : items) {
                remaining.put(item.id(), item.amount());
            }
            plugin.database().submit(repo -> repo.completeClaim(claimOp, remaining, System.currentTimeMillis()))
                    .whenComplete((n, err) -> plugin.sync(() -> guard.release(token)));
            return;
        }
        int given = 0;
        int left = 0;
        for (PendingItem item : items) {
            ItemStack template;
            try {
                template = ItemStack.deserializeBytes(item.itemData());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Pending reward #" + item.id() + " for " + player.getName()
                        + " could not be read and was kept: " + e.getMessage());
                remaining.put(item.id(), item.amount());
                left += item.amount();
                continue;
            }
            int leftover = give(player, template, item.amount());
            remaining.put(item.id(), leftover);
            given += item.amount() - leftover;
            left += leftover;
        }
        int givenTotal = given;
        int leftTotal = left;
        plugin.database().submit(repo -> repo.completeClaim(claimOp, remaining, System.currentTimeMillis()))
                .whenComplete((n, err) -> plugin.sync(() -> {
                    guard.release(token);
                    if (err != null) {
                        plugin.reconciliation().record("DB_ERROR", player.getUniqueId(), claimOp,
                                "claimed items handed out but not recorded; rows stay CLAIMING and will be flagged UNCERTAIN on next start");
                    }
                    if (!player.isOnline()) {
                        return;
                    }
                    TagResolver r = TagResolver.resolver(Placeholder.unparsed("count", String.valueOf(givenTotal)),
                            Placeholder.unparsed("left", String.valueOf(leftTotal)));
                    if (givenTotal > 0) {
                        plugin.sound(player, "claim");
                        bundle.messages().send(player, "claim-success", r);
                    }
                    if (leftTotal > 0) {
                        bundle.messages().send(player, "claim-partial", r);
                    }
                    if (reopenMain && plugin.menus().manager().current(player.getUniqueId()) instanceof dev.exo.dailyspinner.menu.MainMenu) {
                        plugin.menus().openMain(player);
                    }
                }));
    }

    // =============================================================== lifecycle

    public void handleQuit(UUID player) {
        ActiveSpin spin = active.get(player);
        if (spin == null) {
            return;
        }
        if (spin.animation != null) {
            spin.animation.stop();
        }
        spin.menu = null;
        if (!spin.finalizing) {
            // Still RESERVED in the database: delivered on next join.
            cleanup(spin);
        }
    }

    /** Stops animations and returns not-yet-handed-out deliveries to RESERVED before the database closes. */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (ActiveSpin spin : new ArrayList<>(active.values())) {
            if (spin.animation != null) {
                spin.animation.stop();
            }
            if (spin.finalizing && !spin.handedOut) {
                String id = spin.record.id();
                plugin.database().submit(repo -> repo.revertDelivery(id, now));
            }
        }
        active.clear();
        guard.clear();
    }

    /** Placeholder data for messages about a player's current state. */
    public TagResolver describe(ConfigBundle bundle, PlayerData data) {
        return Placeholders.player(bundle, data);
    }
}
