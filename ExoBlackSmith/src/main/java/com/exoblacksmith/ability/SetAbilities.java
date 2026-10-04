package com.exoblacksmith.ability;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ArmorSetDef;
import com.exoblacksmith.effect.CooldownService;
import com.exoblacksmith.effect.EquipmentService;
import com.exoblacksmith.effect.Loadout;
import com.exoblacksmith.effect.TempBuffs;
import com.exoblacksmith.util.Durations;
import com.exoblacksmith.util.Text;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

/**
 * Armor set abilities use a different control than masks to avoid conflicts:
 * sneak + swap-hands key (F by default). The swap is cancelled only when a complete set with an ability
 * is worn, so ordinary offhand swapping still works otherwise.
 */
public final class SetAbilities implements Listener {
    private final Supplier<Registry> registry;
    private final EquipmentService equipment;
    private final CooldownService cooldowns;
    private final TempBuffs buffs;

    public SetAbilities(Supplier<Registry> registry, EquipmentService equipment, CooldownService cooldowns, TempBuffs buffs) {
        this.registry = registry;
        this.equipment = equipment;
        this.cooldowns = cooldowns;
        this.buffs = buffs;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        Loadout loadout = equipment.current(player);
        ArmorSetDef set = loadout.activeSet();
        if (set == null || set.ability() == null) {
            return;
        }
        event.setCancelled(true);
        Registry reg = registry.get();
        if (!player.hasPermission("exoblacksmith.ability.armor")) {
            Text.send(player, reg, "no-permission");
            return;
        }
        String key = "set." + set.id();
        long left = cooldowns.remaining(player, key);
        if (left > 0) {
            Text.actionBar(player, reg, "on-cooldown", Map.of("time", Durations.format(left)));
            return;
        }
        ArmorSetDef.SetAbility ability = set.ability();
        if (ability.type() == ArmorSetDef.AbilityType.DASH) {
            Vector direction = player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() < 1.0E-4 || player.isGliding() || player.isInsideVehicle()) {
                return;
            }
            direction.normalize();
            Location estimate = player.getLocation().add(direction.clone().multiply(ability.dashForce() * 4));
            if (!player.getWorld().getWorldBorder().isInside(estimate)) {
                return;
            }
            player.setVelocity(direction.multiply(ability.dashForce()).setY(ability.dashVertical()));
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
            player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1f, 1f);
        } else {
            if (ability.extinguish()) {
                player.setFireTicks(0);
            }
            player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0.05);
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 0.8f);
        }
        buffs.add(player.getUniqueId(), ability.reductions(), ability.durationSeconds(), ability.extinguish());
        cooldowns.start(player, key, ability.cooldownSeconds() * 1000);
        Text.actionBar(player, reg, "set-ability-used", Map.of("ability", Text.plainName(ability.name()),
                "duration", Durations.seconds(ability.durationSeconds())));
    }
}
