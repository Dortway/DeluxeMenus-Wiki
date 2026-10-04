package com.exoblacksmith.config.model;

/**
 * Hard-coded rune behaviours. The numeric tier value is interpreted per mechanic:
 * reductions are fractions, Totem Surge is invisibility seconds, Void Stride is a speed fraction,
 * Anchor Guard is knockback resistance, Tidal Breath is water-breathing seconds.
 */
public enum RuneMechanic {
    BLAST(DamageCategory.CRYSTAL_ANCHOR),
    TOTEM_SURGE(null),
    HARDENED_SHELL(DamageCategory.CRITICAL_MELEE),
    KINETIC_REDUCER(null),
    PHOENIX_AURA(null),
    VOID_STRIDE(null),
    FEATHER_WARD(DamageCategory.FALL),
    ANCHOR_GUARD(null),
    TIDAL_BREATH(null);

    private final DamageCategory simpleReduction;

    RuneMechanic(DamageCategory simpleReduction) {
        this.simpleReduction = simpleReduction;
    }

    /** Category for mechanics that are a plain reduction against a single category. */
    public DamageCategory simpleReduction() {
        return simpleReduction;
    }

    /** Whether the tier value is a damage reduction fraction (validated to 0..1). */
    public boolean isReduction() {
        return switch (this) {
            case BLAST, HARDENED_SHELL, KINETIC_REDUCER, PHOENIX_AURA, FEATHER_WARD -> true;
            default -> false;
        };
    }
}
