package net.exoeconomy.reward;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.ExoEconomyAPI.RewardOutcome;
import net.exoeconomy.api.event.ShardRewardEvent;
import net.exoeconomy.config.RewardSettings;
import net.exoeconomy.config.Settings;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.reward.DailyLimits;
import net.exoeconomy.core.reward.RewardAccumulator;
import net.exoeconomy.core.tx.LedgerResult;
import net.exoeconomy.storage.Database;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The activity reward pipeline: eligibility, {@link ShardRewardEvent}, booster multiplier, daily
 * caps, then batching. One repeating task (not one per player) deposits each player's accumulated
 * rewards through ExcellentEconomy and persists provenance and daily counters in batches.
 *
 * <p>Batching trade-off: rewards accrued in the last flush interval (default 2 seconds) are lost if
 * the server crashes. They are flushed on logout and on clean shutdown.
 */
public final class RewardService {
    private static final int MAX_DEPOSIT_FAILURES = 30;

    private final ExoEconomyPlugin plugin;
    private final RewardAccumulator accumulator = new RewardAccumulator();
    private final DailyLimits limits;
    private final Provenance provenance = new Provenance();
    private final Map<String, String> customActivities = new LinkedHashMap<>();
    private final Map<UUID, Long> unavailableNotified = new HashMap<>();
    private final ZoneId zone = ZoneId.systemDefault();

    private final NotificationPrefs prefs;
    private final Map<UUID, PendingChat> pendingChat = new HashMap<>();

    /** Chat summaries collected while chat.interval-seconds is above 0. */
    private static final class PendingChat {
        BigDecimal amount = BigDecimal.ZERO;
        final Set<String> activities = new LinkedHashSet<>();
        long lastSent;
    }

    public RewardService(ExoEconomyPlugin plugin, List<DailyLimits.Row> today, Map<UUID, Integer> mutedNotifications) {
        this.plugin = plugin;
        this.prefs = new NotificationPrefs(mutedNotifications);
        this.limits = new DailyLimits(LocalDate.now(zone).toEpochDay());
        today.forEach(limits::load);
    }

    public static long today() { return LocalDate.now(ZoneId.systemDefault()).toEpochDay(); }

    public Provenance provenance() { return provenance; }

    public Set<String> activityIds() {
        Set<String> ids = new LinkedHashSet<>(plugin.settings().rewards().activities().keySet());
        ids.addAll(customActivities.keySet());
        return ids;
    }

    public boolean registerActivity(String pluginName, String id) {
        if (!id.matches("[a-z0-9_\\-]{1,32}") || RewardSettings.BUILT_IN.contains(id)) return false;
        customActivities.put(id, pluginName);
        return true;
    }

    public void unregisterActivities(String pluginName) { customActivities.values().removeIf(pluginName::equals); }

    private RewardSettings.Activity activity(String id) {
        RewardSettings.Activity configured = plugin.settings().rewards().activity(id);
        if (configured != null) return configured;
        if (!customActivities.containsKey(id)) return null;
        return new RewardSettings.Activity(id, true, "exoeconomy.earn." + id, Set.of(), Set.of(),
                Set.of(GameMode.SURVIVAL, GameMode.ADVENTURE), BigDecimal.ZERO, Map.of(), BigDecimal.ZERO);
    }

    /** Cheap pre-check used by listeners before doing any work. */
    public boolean eligible(Player player, String activityId) {
        RewardSettings.Activity a = activity(activityId);
        return a != null && a.enabled() && a.gameModes().contains(player.getGameMode())
                && a.worldAllowed(player.getWorld().getName())
                && (a.permission().isEmpty() || player.hasPermission(a.permission()));
    }

    /** Base amount configured for a key (material or entity type name). */
    public BigDecimal configured(String activityId, String key) {
        RewardSettings.Activity a = activity(activityId);
        if (a == null) return BigDecimal.ZERO;
        BigDecimal v = a.reward(key);
        return v.signum() > 0 ? v : a.defaultReward();
    }

    /**
     * Reward for a block broken by a plugin that handles breaking itself (cancels the BlockBreakEvent or
     * regenerates blocks, as mine/cube plugins do). Uses the configured farming, woodcutting or mining
     * reward for the material; provenance is not checked because the calling plugin vouches for the break.
     */
    public RewardOutcome awardBlock(Player player, org.bukkit.Material material) {
        String key = material.name();
        for (String activity : List.of(RewardSettings.FARMING, RewardSettings.WOODCUTTING, RewardSettings.MINING)) {
            BigDecimal base = configured(activity, key);
            RewardSettings.Activity a = activity(activity);
            if (a != null && a.reward(key).signum() > 0) return award(player, activity, key, base);
        }
        return RewardOutcome.INELIGIBLE;
    }

    public RewardOutcome award(Player player, String activityId, String key, BigDecimal base) {
        if (base == null || base.signum() <= 0) return RewardOutcome.INELIGIBLE;
        RewardSettings.Activity a = activity(activityId);
        if (a == null) return RewardOutcome.UNKNOWN_ACTIVITY;
        if (!eligible(player, activityId)) return RewardOutcome.INELIGIBLE;
        if (!plugin.ledger().available()) {
            notifyUnavailable(player);
            return RewardOutcome.ECONOMY_UNAVAILABLE;
        }
        ShardRewardEvent event = new ShardRewardEvent(player, activityId, key, base);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getBaseAmount().signum() <= 0) return RewardOutcome.CANCELLED;
        BigDecimal multiplier = plugin.boosters().book().multiplier(player.getUniqueId(), activityId);
        BigDecimal amount = event.getBaseAmount().multiply(multiplier, MathContext.DECIMAL64);
        limits.roll(today());
        BigDecimal allowed = limits.take(player.getUniqueId(), activityId, amount, a.dailyCap(), plugin.settings().rewards().totalDailyCap());
        if (allowed.signum() <= 0) return RewardOutcome.CAPPED;
        accumulator.add(player.getUniqueId(), activityId, allowed);
        return allowed.compareTo(amount) < 0 ? RewardOutcome.CAPPED : RewardOutcome.AWARDED;
    }

    private void notifyUnavailable(Player player) {
        long now = System.currentTimeMillis();
        Long last = unavailableNotified.get(player.getUniqueId());
        if (last != null && now - last < plugin.settings().rewards().notifications().unavailableCooldownMs()) return;
        unavailableNotified.put(player.getUniqueId(), now);
        plugin.settings().messages().send(player, "rewards-unavailable");
    }

    /** Repeating task: deposit accumulated rewards and persist batched state. */
    public void flush() {
        for (UUID id : accumulator.players()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                BigDecimal lost = accumulator.remove(id);
                if (lost.signum() > 0) plugin.getLogger().fine("Dropped " + lost + " unflushed reward for offline " + id);
                continue;
            }
            flush(player);
        }
        if (!pendingChat.isEmpty()) flushChat();
        persist();
    }

    /** Deposits one player's accumulated rewards now (also on logout and shutdown). */
    public void flush(Player player) {
        UUID id = player.getUniqueId();
        int scale = plugin.ledger().scale();
        Optional<RewardAccumulator.Payout> payout = accumulator.take(id, scale);
        if (payout.isEmpty()) return;
        RewardAccumulator.Payout p = payout.get();
        LedgerResult result;
        try {
            result = plugin.ledger().deposit(id, p.amount());
        } catch (RuntimeException e) {
            result = new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, null, null, "exception: " + e);
        }
        switch (result.outcome()) {
            case SUCCESS -> {
                accumulator.succeeded(id);
                notifyReward(player, p);
            }
            case REJECTED -> {
                int failures = accumulator.restore(p);
                if (failures >= MAX_DEPOSIT_FAILURES) {
                    BigDecimal dropped = accumulator.remove(id);
                    p.byActivity().forEach((activity, amount) -> limits.giveBack(id, activity, amount));
                    plugin.getLogger().warning("Dropped " + dropped + " pending activity Shards for " + player.getName()
                            + " after " + failures + " refused deposits (" + result.reason() + ")");
                }
            }
            case UNCERTAIN -> plugin.getLogger().warning("Activity reward deposit of " + p.amount() + " for " + player.getName()
                    + " has an uncertain outcome (" + result.reason() + "); not retried to avoid paying twice. Check ExcellentEconomy's operations log.");
        }
    }

    public NotificationPrefs prefs() { return prefs; }

    /**
     * Action bar and chat line for a deposit. Each channel can be turned off server-wide
     * (notifications.*.enabled) or per player (/shardnotify) when allow-player-toggle is true.
     */
    private void notifyReward(Player player, RewardAccumulator.Payout p) {
        RewardSettings.Notifications n = plugin.settings().rewards().notifications();
        UUID id = player.getUniqueId();
        if (n.actionBar() && wants(id, NotificationPrefs.ACTION_BAR, n))
            player.sendActionBar(Text.color(Text.replace(n.actionBarFormat(), values(p.amount(), p.byActivity().keySet()))));
        if (n.chat() && wants(id, NotificationPrefs.CHAT, n)) {
            if (n.chatIntervalMs() <= 0) sendChat(player, p.amount(), p.byActivity().keySet());
            else {
                PendingChat pc = pendingChat.computeIfAbsent(id, k -> new PendingChat());
                pc.amount = pc.amount.add(p.amount());
                pc.activities.addAll(p.byActivity().keySet());
                if (System.currentTimeMillis() - pc.lastSent >= n.chatIntervalMs()) sendPendingChat(player, pc);
            }
        }
        if (n.sound() && wants(id, NotificationPrefs.SOUND, n)) {
            try {
                player.playSound(player.getLocation(), n.soundKey(), n.volume(), n.pitch());
            } catch (RuntimeException ignored) { }
        }
    }

    private boolean wants(UUID player, int channel, RewardSettings.Notifications n) {
        return !n.playerToggle() || prefs.enabled(player, channel);
    }

    private Map<String, String> values(BigDecimal amount, Collection<String> activityIds) {
        String activities = activityIds.stream().map(a -> plugin.boosters().activityName(a)).collect(Collectors.joining(", "));
        return Map.of("amount", plugin.ledger().format(amount), "activities", activities, "raw_amount", Money.plain(amount));
    }

    private void sendChat(Player player, BigDecimal amount, Collection<String> activities) {
        RewardSettings.Notifications n = plugin.settings().rewards().notifications();
        player.sendMessage(Text.color(Text.replace(n.chatFormat().replace("%prefix%", plugin.settings().messages().raw("prefix")), values(amount, activities))));
    }

    private void sendPendingChat(Player player, PendingChat pc) {
        if (pc.amount.signum() > 0) sendChat(player, pc.amount, pc.activities);
        pc.amount = BigDecimal.ZERO;
        pc.activities.clear();
        pc.lastSent = System.currentTimeMillis();
    }

    /** Sends chat summaries whose interval has passed (so the last batch is not held back). */
    private void flushChat() {
        RewardSettings.Notifications n = plugin.settings().rewards().notifications();
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, PendingChat>> it = pendingChat.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PendingChat> e = it.next();
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) { it.remove(); continue; }
            if (e.getValue().amount.signum() > 0 && now - e.getValue().lastSent >= n.chatIntervalMs()) sendPendingChat(player, e.getValue());
        }
    }

    /** Writes dirty provenance rows and daily counters (bounded batches, storage thread). */
    public void persist() {
        Database db = plugin.database();
        if (provenance.loaded() && provenance.pending() > 0) {
            List<Database.PlacedRow> rows = provenance.drainDirty(5000);
            db.write(List.of(Database.writePlaced(rows))).whenComplete((ok, error) -> {
                if (error != null) Bukkit.getScheduler().runTask(plugin, () -> provenance.requeue(rows));
            });
        }
        List<DailyLimits.Row> daily = limits.drainDirty();
        if (!daily.isEmpty()) db.write(List.of(Database.writeDaily(daily)));
    }

    public void quit(Player player) {
        flush(player);
        accumulator.remove(player.getUniqueId());
        unavailableNotified.remove(player.getUniqueId());
        PendingChat pc = pendingChat.remove(player.getUniqueId());
        if (pc != null && pc.amount.signum() > 0) sendChat(player, pc.amount, pc.activities);
        limits.forget(player.getUniqueId());
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) flush(p);
        persistAll();
    }

    private void persistAll() {
        Database db = plugin.database();
        if (provenance.loaded()) {
            while (provenance.pending() > 0) db.write(List.of(Database.writePlaced(provenance.drainDirty(5000))));
        }
        List<DailyLimits.Row> daily = limits.drainDirty();
        if (!daily.isEmpty()) db.write(List.of(Database.writeDaily(daily)));
    }

    public BigDecimal earnedToday(UUID player, String activity) { return limits.earned(player, activity); }

    public BigDecimal pending(UUID player) { return accumulator.pending(player); }

    /** Applies the settings snapshot that is current now; nothing to cache. */
    public void reload(Settings settings) { }
}
