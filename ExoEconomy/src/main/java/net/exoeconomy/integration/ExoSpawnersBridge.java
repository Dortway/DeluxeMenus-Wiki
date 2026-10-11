package net.exoeconomy.integration;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.config.RewardSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Pays slaying rewards for ExoSpawners stacked-mob kills, mirroring how ExoSkills 1.0.1 integrates.
 * Bound by reflection to {@code net.exospawners.api.StackMobKilledEvent} with {@code getPlayer(): Player}
 * and {@code getMobType(): EntityType}. Those signatures are read from ExoSkills 1.0.1's compiled
 * SpawnerHook, not from ExoSpawners itself. Only active when rewards.yml
 * activities.slaying.exospawners-stack-kills is true (spawner mobs are excluded by default).
 */
public final class ExoSpawnersBridge implements Listener, AutoCloseable {
    private final ExoEconomyPlugin plugin;
    private final Method getPlayer;
    private final Method getMobType;
    private final Set<Event> handled = Collections.newSetFromMap(new WeakHashMap<>());

    private ExoSpawnersBridge(ExoEconomyPlugin plugin, Class<? extends Event> type) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.getPlayer = type.getMethod("getPlayer");
        this.getMobType = type.getMethod("getMobType");
        if (!Player.class.isAssignableFrom(getPlayer.getReturnType()) || getMobType.getReturnType() != EntityType.class)
            throw new NoSuchMethodException("StackMobKilledEvent signatures differ from ExoSpawners 1.1.1");
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> handle(event), plugin, true);
    }

    public static ExoSpawnersBridge connect(ExoEconomyPlugin plugin) {
        Plugin spawners = Bukkit.getPluginManager().getPlugin("ExoSpawners");
        if (spawners == null || !spawners.isEnabled()) return null;
        try {
            Class<?> raw = Class.forName("net.exospawners.api.StackMobKilledEvent", true, spawners.getClass().getClassLoader());
            ExoSpawnersBridge bridge = new ExoSpawnersBridge(plugin, raw.asSubclass(Event.class));
            String version = spawners.getPluginMeta().getVersion();
            plugin.getLogger().info("ExoSpawners " + version + " stacked kills hooked" + ("1.1.1".equals(version) ? "" : " (unverified version; written for 1.1.1)") + ".");
            return bridge;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().warning("ExoSpawners found but its StackMobKilledEvent is not compatible (" + e + "); stacked kills pay nothing.");
            return null;
        }
    }

    private void handle(Event event) {
        if (!Bukkit.isPrimaryThread() || !handled.add(event)) return;
        if (event instanceof Cancellable c && c.isCancelled()) return;
        if (!plugin.settings().rewards().slaying().exoSpawnersStackKills()) return;
        try {
            Player player = (Player) getPlayer.invoke(event);
            EntityType type = (EntityType) getMobType.invoke(event);
            if (player == null || type == null) return;
            BigDecimal amount = plugin.rewards().configured(RewardSettings.SLAYING, type.name());
            if (amount.signum() > 0) plugin.rewards().award(player, RewardSettings.SLAYING, type.name(), amount);
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning("Could not read ExoSpawners kill event: " + e);
        }
    }

    @Override
    public void close() { HandlerList.unregisterAll(this); }
}
