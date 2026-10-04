package com.exoblacksmith.effect;

import com.exoblacksmith.config.model.DamageCategory;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * Maps a damage event to the categories reductions can target.
 *
 * <p>Verified against the Paper 1.21.11 API:
 * <ul>
 *   <li>End crystals: {@link EntityDamageByEntityEvent} whose damager is an {@link EnderCrystal}.</li>
 *   <li>Respawn anchors: {@link EntityDamageByBlockEvent} whose {@code getDamagerBlockState()} is a
 *       RESPAWN_ANCHOR. Beds exploding in the Nether/End share the {@code bad_respawn_point} damage
 *       type, so the block state is what tells them apart. If the server reports no block state, a
 *       {@code bad_respawn_point} explosion in the Overworld is treated as an anchor (beds work there).</li>
 *   <li>Critical melee: Paper's {@link EntityDamageByEntityEvent#isCritical()}, restricted to direct
 *       player attacks with an item in the {@code minecraft:swords} or {@code minecraft:axes} tags.</li>
 *   <li>Mace smash: damage type {@code mace_smash}; elytra collisions: cause {@code FLY_INTO_WALL}.</li>
 * </ul>
 */
public final class DamageClassifier {
    private DamageClassifier() {
    }

    public static Set<DamageCategory> classify(EntityDamageEvent event) {
        Set<DamageCategory> out = EnumSet.of(DamageCategory.ALL);
        DamageCause cause = event.getCause();
        DamageType type = event.getDamageSource().getDamageType();

        if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION
                || type == DamageType.EXPLOSION || type == DamageType.PLAYER_EXPLOSION || type == DamageType.BAD_RESPAWN_POINT) {
            out.add(DamageCategory.EXPLOSION);
        }
        if (isCrystalOrAnchor(event, type)) {
            out.add(DamageCategory.CRYSTAL_ANCHOR);
        }
        if (type == DamageType.MACE_SMASH) {
            out.add(DamageCategory.MACE_SMASH);
        }
        if (cause == DamageCause.FLY_INTO_WALL) {
            out.add(DamageCategory.ELYTRA_COLLISION);
        }
        if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.CAMPFIRE) {
            out.add(DamageCategory.FIRE);
        }
        if (cause == DamageCause.LAVA) {
            out.add(DamageCategory.LAVA);
        }
        if (cause == DamageCause.FALL) {
            out.add(DamageCategory.FALL);
        }
        if (cause == DamageCause.PROJECTILE) {
            out.add(DamageCategory.PROJECTILE);
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && isCriticalMelee(byEntity)) {
            out.add(DamageCategory.CRITICAL_MELEE);
        }
        return out;
    }

    static boolean isCrystalOrAnchor(EntityDamageEvent event, DamageType type) {
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof EnderCrystal) {
            return true;
        }
        if (event instanceof EntityDamageByBlockEvent byBlock) {
            BlockState state = byBlock.getDamagerBlockState();
            if (state != null) {
                return state.getType() == Material.RESPAWN_ANCHOR;
            }
            return type == DamageType.BAD_RESPAWN_POINT
                    && event.getEntity().getWorld().getEnvironment() == World.Environment.NORMAL;
        }
        return false;
    }

    static boolean isCriticalMelee(EntityDamageByEntityEvent event) {
        if (!event.isCritical() || event.getCause() != DamageCause.ENTITY_ATTACK) {
            return false;
        }
        Entity damager = event.getDamager();
        if (!(damager instanceof Player attacker) || damager instanceof Projectile) {
            return false;
        }
        Material held = attacker.getInventory().getItemInMainHand().getType();
        return Tag.ITEMS_SWORDS.isTagged(held) || Tag.ITEMS_AXES.isTagged(held);
    }
}
