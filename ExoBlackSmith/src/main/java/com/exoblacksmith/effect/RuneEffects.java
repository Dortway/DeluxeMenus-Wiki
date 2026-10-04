package com.exoblacksmith.effect;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.util.Durations;
import com.exoblacksmith.util.Text;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Rune behaviours that are not plain damage reductions:
 * <ul>
 *   <li>Totem Surge: after a successful totem resurrection, invisibility plus a facing-direction dash.
 *       The dash is a velocity impulse, so normal physics (collisions) and movement checks apply; it is
 *       skipped when its estimated end point is outside the world border. Vanilla limitation:
 *       invisibility does not hide worn armor, held items or the totem animation.</li>
 *   <li>Phoenix Aura: burning is capped to 15 ticks every 5 ticks, so fire goes out within one second
 *       unless the player is still standing in fire or lava. It is not lava immunity.</li>
 *   <li>Tidal Breath: when the player's eyes are submerged, grants Water Breathing for the tier's
 *       seconds, then a cooldown (default 60s) starts.</li>
 * </ul>
 */
public final class RuneEffects implements Listener {
    private static final String TOTEM_KEY = "rune.totem_surge";
    private static final String TIDAL_KEY = "rune.tidal_breath";

    private final Plugin plugin;
    private final Supplier<Registry> registry;
    private final EquipmentService equipment;
    private final CooldownService cooldowns;
    private final TempBuffs buffs;
    private BukkitTask task;
    private int tick;

    public RuneEffects(Plugin plugin, Supplier<Registry> registry, EquipmentService equipment, CooldownService cooldowns,
                       TempBuffs buffs) {
        this.plugin = plugin;
        this.registry = registry;
        this.equipment = equipment;
        this.cooldowns = cooldowns;
        this.buffs = buffs;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5, 5);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            Loadout loadout = equipment.loadout(player);
            boolean extinguish = loadout.hasRune(RuneMechanic.PHOENIX_AURA) || buffs.extinguishing(player.getUniqueId());
            if (extinguish && player.getFireTicks() > 15) {
                player.setFireTicks(15);
            }
            if (tick % 4 == 0 && loadout.hasRune(RuneMechanic.TIDAL_BREATH)) {
                tidal(player, loadout);
            }
        }
    }

    private void tidal(Player player, Loadout loadout) {
        if (!player.isUnderWater() || player.hasPotionEffect(PotionEffectType.WATER_BREATHING)
                || !cooldowns.ready(player, TIDAL_KEY)) {
            return;
        }
        RuneDef rune = loadout.runeDefs().get(RuneMechanic.TIDAL_BREATH);
        double seconds = loadout.rune(RuneMechanic.TIDAL_BREATH);
        long cooldown = (long) (rune == null ? 60 : rune.param("cooldown", 60));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, (int) (seconds * 20), 0, false, true, true));
        cooldowns.start(player, TIDAL_KEY, cooldown * 1000);
        Text.actionBar(player, registry.get(), "tidal-breath", Map.of("seconds", Durations.seconds(seconds)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Loadout loadout = equipment.current(player);
        if (!loadout.hasRune(RuneMechanic.TOTEM_SURGE) || !cooldowns.ready(player, TOTEM_KEY)) {
            return;
        }
        RuneDef rune = loadout.runeDefs().get(RuneMechanic.TOTEM_SURGE);
        double seconds = loadout.rune(RuneMechanic.TOTEM_SURGE);
        double force = rune.param("dash-force", 1.4);
        double vertical = rune.param("dash-vertical", 0.35);
        long cooldown = (long) rune.param("cooldown", 30);
        cooldowns.start(player, TOTEM_KEY, cooldown * 1000);
        // Run next tick: the resurrection itself resets effects and motion during this tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) (seconds * 20), 0, false, false, true));
            Vector direction = player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() > 1.0E-4) {
                direction.normalize();
                Location estimate = player.getLocation().add(direction.clone().multiply(force * 4));
                WorldBorder border = player.getWorld().getWorldBorder();
                if (border.isInside(estimate)) {
                    player.setVelocity(direction.multiply(force).setY(vertical));
                }
            }
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.2);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.2f);
            Text.actionBar(player, registry.get(), "totem-surge", Map.of("seconds", Durations.seconds(seconds)));
        });
    }
}
