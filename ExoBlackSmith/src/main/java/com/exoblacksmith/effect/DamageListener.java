package com.exoblacksmith.effect;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.Settings;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.DamageCategory;
import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.config.model.Reduction;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.util.Durations;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Damage pipeline (per damage event):
 * <ol>
 *   <li>HIGH: attacker bonuses (mask trident bonus, Creepy explosive hit) are added to the base damage,
 *       capped by {@code damage.max-bonus-damage-per-hit}. Skipped for ExoBlackSmith's own redirected damage.</li>
 *   <li>HIGHEST: victim reductions (runes, armor pieces, set bonus, active abilities) are combined and
 *       capped by {@code damage.max-total-reduction}, then applied to the base damage.</li>
 *   <li>MONITOR: side effects of procs (cooldowns, particles) are committed only if the event was not
 *       cancelled, so PvP-protected hits never consume a cooldown.</li>
 * </ol>
 * Both stages run with {@code ignoreCancelled = true}. Vanilla armor, enchantments and resistance are
 * applied by the server afterwards on the adjusted base damage.
 */
public final class DamageListener implements Listener {
    private final Supplier<Registry> registry;
    private final EquipmentService equipment;
    private final CooldownService cooldowns;
    private final TempBuffs buffs;
    private final Map<EntityDamageByEntityEvent, Runnable> pendingProcs = new IdentityHashMap<>();

    public DamageListener(Supplier<Registry> registry, EquipmentService equipment, CooldownService cooldowns, TempBuffs buffs) {
        this.registry = registry;
        this.equipment = equipment;
        this.cooldowns = cooldowns;
        this.buffs = buffs;
    }

    // ------------------------------------------------------------------ attacker bonuses

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (DamageGuard.active()) {
            return;
        }
        Registry reg = registry.get();
        Player attacker = null;
        boolean tridentHit = false;
        if (event.getDamager() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            attacker = p;
            tridentHit = p.getInventory().getItemInMainHand().getType() == Material.TRIDENT;
        } else if (event.getDamager() instanceof Trident trident && trident.getShooter() instanceof Player p) {
            attacker = p;
            tridentHit = true;
        }
        if (attacker == null || attacker.equals(event.getEntity())) {
            return;
        }
        MaskLevel mask = equipment.current(attacker).maskLevel();
        if (mask == null) {
            return;
        }
        double bonus = 0;
        if (tridentHit && mask.tridentBonus() > 0) {
            bonus += mask.tridentBonus();
        }
        MaskLevel.ExplosiveHitAbility explosive = mask.explosiveHit();
        if (explosive != null && event.getEntity() instanceof Player victim
                && event.getDamager() instanceof Player && !tridentHit && !victim.equals(attacker)) {
            String key = "mask.explosive_hit";
            if (cooldowns.ready(attacker, key)) {
                bonus += explosive.bonusDamage();
                Player finalAttacker = attacker;
                pendingProcs.put(event, () -> {
                    cooldowns.start(finalAttacker, key, explosive.cooldownSeconds() * 1000);
                    victim.getWorld().spawnParticle(Particle.EXPLOSION, victim.getLocation().add(0, 1, 0), 1);
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
                    Text.actionBar(finalAttacker, reg, "explosive-hit", Map.of(
                            "damage", Durations.number(explosive.bonusDamage()), "player", victim.getName()));
                });
            }
        }
        if (bonus > 0) {
            event.setDamage(ReductionMath.withBonus(event.getDamage(), bonus, reg.settings.maxBonusDamagePerHit));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackMonitor(EntityDamageByEntityEvent event) {
        Runnable proc = pendingProcs.remove(event);
        if (proc != null && !event.isCancelled() && event.getFinalDamage() > 0) {
            proc.run();
        }
    }

    // ------------------------------------------------------------------ victim reductions

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getDamage() <= 0) {
            return;
        }
        Registry reg = registry.get();
        Loadout loadout = equipment.current(victim);
        List<Reduction> buffList = buffs.active(victim.getUniqueId());
        if (loadout.signature().isEmpty() && buffList.isEmpty()) {
            return;
        }
        Set<DamageCategory> categories = DamageClassifier.classify(event);
        List<Double> reductions = new ArrayList<>();

        addRune(reductions, loadout, RuneMechanic.BLAST, categories, DamageCategory.CRYSTAL_ANCHOR);
        addRune(reductions, loadout, RuneMechanic.HARDENED_SHELL, categories, DamageCategory.CRITICAL_MELEE);
        addRune(reductions, loadout, RuneMechanic.KINETIC_REDUCER, categories, DamageCategory.MACE_SMASH, DamageCategory.ELYTRA_COLLISION);
        addRune(reductions, loadout, RuneMechanic.PHOENIX_AURA, categories, DamageCategory.FIRE, DamageCategory.LAVA);
        addRune(reductions, loadout, RuneMechanic.FEATHER_WARD, categories, DamageCategory.FALL);

        for (ArmorPieceDef piece : loadout.armor().values()) {
            addReductions(reductions, piece.reductions(), categories);
        }
        if (loadout.activeSet() != null) {
            addReductions(reductions, loadout.activeSet().bonusReductions(), categories);
        }
        addReductions(reductions, buffList, categories);
        if (reductions.isEmpty()) {
            return;
        }
        double multiplier = ReductionMath.multiplier(reductions,
                reg.settings.combine == Settings.ReductionCombine.MULTIPLICATIVE, reg.settings.maxTotalReduction);
        event.setDamage(event.getDamage() * multiplier);
    }

    private static void addRune(List<Double> out, Loadout loadout, RuneMechanic mechanic, Set<DamageCategory> categories,
                                DamageCategory... targets) {
        if (!loadout.hasRune(mechanic)) {
            return;
        }
        for (DamageCategory target : targets) {
            if (categories.contains(target)) {
                out.add(loadout.rune(mechanic));
                return;
            }
        }
    }

    private static void addReductions(List<Double> out, List<Reduction> reductions, Set<DamageCategory> categories) {
        for (Reduction r : reductions) {
            if (categories.contains(r.category())) {
                out.add(r.value());
            }
        }
    }
}
