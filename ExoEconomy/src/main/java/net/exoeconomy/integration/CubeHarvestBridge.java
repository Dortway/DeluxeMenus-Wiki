package net.exoeconomy.integration;

import net.exoeconomy.ExoEconomyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Pays rewards for blocks mined inside ExoCubes ore cubes and ExoWoodCube wood cubes.
 *
 * <p>Both plugins cancel the BlockBreakEvent of every cube block at LOWEST priority and harvest the block
 * themselves, so ExoEconomy's normal break listener (which ignores cancelled events) never pays for them.
 * Instead this bridge listens to their "committed harvest" events, which fire once per block a player
 * really mined in survival (not for refused breaks, creative mode or Auto-Mine):
 * <ul>
 *   <li>ExoCubes: {@code dev.exo.cubes.api.CubeHarvestEvent} (shipped in ExoCubes)</li>
 *   <li>ExoWoodCube: {@code dev.exo.woodcube.api.WoodCubeHarvestEvent} (added by the patch in
 *       integrations/ExoWoodCube; older ExoWoodCube builds do not fire it)</li>
 * </ul>
 * Bound by reflection to {@code getPlayer(): Player} and {@code getBlock(): Material}, verified against the
 * compiled ExoCubes classes and the ExoWoodCube source. The reward is the activity's configured reward for the
 * mined block, times the integration's reward-multiplier, once per block (fortune does not multiply it).
 */
public final class CubeHarvestBridge implements Listener, AutoCloseable {
    /** Live per-integration settings, so /exoeconomy reload applies immediately. */
    public record Rule(boolean enabled, String activity, BigDecimal multiplier) {}

    private final ExoEconomyPlugin plugin;
    private final String name;
    private final Supplier<Rule> rule;
    private final Method getPlayer;
    private final Method getBlock;
    private final Set<Event> handled = Collections.newSetFromMap(new WeakHashMap<>());

    private CubeHarvestBridge(ExoEconomyPlugin plugin, String name, Class<? extends Event> type, Supplier<Rule> rule) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.name = name;
        this.rule = rule;
        this.getPlayer = type.getMethod("getPlayer");
        this.getBlock = type.getMethod("getBlock");
        if (!Player.class.isAssignableFrom(getPlayer.getReturnType()) || getBlock.getReturnType() != Material.class)
            throw new NoSuchMethodException(type.getName() + " has unexpected getPlayer()/getBlock() signatures");
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> handle(event), plugin, false);
    }

    /**
     * Hooks one cube plugin if it is installed. Returns null (with a log line saying why) when it is
     * missing, disabled in config, or too old to fire its harvest event.
     */
    public static CubeHarvestBridge connect(ExoEconomyPlugin plugin, String pluginName, String eventClass, Supplier<Rule> rule, String missingEventHint) {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled() || !rule.get().enabled()) return null;
        try {
            Class<?> raw = Class.forName(eventClass, true, target.getClass().getClassLoader());
            CubeHarvestBridge bridge = new CubeHarvestBridge(plugin, pluginName, raw.asSubclass(Event.class), rule);
            plugin.getLogger().info(pluginName + " " + target.getPluginMeta().getVersion() + " hooked: blocks mined in cubes pay "
                    + rule.get().activity() + " rewards (x" + rule.get().multiplier().stripTrailingZeros().toPlainString() + ").");
            return bridge;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning(pluginName + " is installed but does not fire " + eventClass + "; cube harvests pay nothing. " + missingEventHint);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().warning(pluginName + " harvest event is not compatible (" + e + "); cube harvests pay nothing.");
        }
        return null;
    }

    private void handle(Event event) {
        if (!Bukkit.isPrimaryThread() || !handled.add(event)) return;
        Rule r = rule.get();
        if (!r.enabled() || r.multiplier().signum() <= 0) return;
        try {
            Player player = (Player) getPlayer.invoke(event);
            Material block = (Material) getBlock.invoke(event);
            if (player == null || block == null) return;
            BigDecimal base = plugin.rewards().configured(r.activity(), block.name());
            if (base.signum() <= 0) return;
            plugin.rewards().award(player, r.activity(), block.name(), base.multiply(r.multiplier(), MathContext.DECIMAL64));
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning("Could not read " + name + " harvest event: " + e);
        }
    }

    @Override
    public void close() { HandlerList.unregisterAll(this); }
}
