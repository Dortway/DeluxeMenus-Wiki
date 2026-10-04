package com.exoblacksmith.config.model;

/**
 * Damage categories that reductions can target. Classification of an actual damage event lives in
 * {@code com.exoblacksmith.effect.DamageClassifier}.
 */
public enum DamageCategory {
    /** Every damage event. */
    ALL,
    /** End crystal and respawn anchor explosions only. */
    CRYSTAL_ANCHOR,
    /** Any block or entity explosion (includes crystals and anchors). */
    EXPLOSION,
    /** Critical melee hits by players using swords or axes. */
    CRITICAL_MELEE,
    /** Mace smash attacks. */
    MACE_SMASH,
    /** Elytra flight-into-wall damage. */
    ELYTRA_COLLISION,
    /** Fire, fire tick and campfire damage. */
    FIRE,
    /** Lava contact damage. */
    LAVA,
    /** Fall damage. */
    FALL,
    /** Arrows, tridents and other projectiles. */
    PROJECTILE
}
