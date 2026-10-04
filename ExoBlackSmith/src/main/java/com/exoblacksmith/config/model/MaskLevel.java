package com.exoblacksmith.config.model;

import java.util.List;

/**
 * Everything a mask grants at one level. Ability fields are {@code null} when absent.
 *
 * @param bonusHearts   extra maximum health in hearts (1 heart = 2 health points)
 * @param tridentBonus  extra damage points per trident hit (melee or thrown)
 */
public record MaskLevel(int level, Rarity rarity, List<PotionSpec> effects, double bonusHearts, double tridentBonus,
                        HealAbility heal, ArrowTeleportAbility arrowTeleport, ExplosiveHitAbility explosiveHit,
                        CobwebAbility cobweb, SummonAbility summon, List<String> effectLore) {

    public boolean hasActivatedAbility() {
        return heal != null || cobweb != null || summon != null;
    }

    /** Heals {@code hearts} hearts, or to full when {@code full}. */
    public record HealAbility(double hearts, boolean full, long cooldownSeconds) {
    }

    public record ArrowTeleportAbility(long cooldownSeconds, long missCooldownSeconds, int maxFlightSeconds) {
    }

    public record ExplosiveHitAbility(double bonusDamage, long cooldownSeconds) {
    }

    public enum CobwebShape { SINGLE, PLUS, SQUARE }

    public record CobwebAbility(long cooldownSeconds, CobwebShape shape, int durationSeconds, int range) {
    }

    public record SummonAbility(org.bukkit.entity.EntityType entity, int count, int lifetimeSeconds,
                                long cooldownSeconds, int range, double damage) {
    }
}
