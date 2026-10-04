package com.exoblacksmith.config.model;

import java.util.List;
import java.util.Set;

/** Set bonus and active ability shared by armor pieces with the same set id. */
public record ArmorSetDef(String id, String name, Set<EquipSlot> requiredSlots, List<Reduction> bonusReductions,
                          double bonusSpeed, SetAbility ability, List<String> bonusLore) {

    public enum AbilityType { WARD, DASH }

    /**
     * WARD: temporary {@code reductions} for {@code durationSeconds} (optionally extinguishing fire).
     * DASH: horizontal launch with {@code dashForce}/{@code dashVertical}, then {@code reductions}
     * (typically FALL) for {@code durationSeconds}.
     */
    public record SetAbility(String name, AbilityType type, long cooldownSeconds, double durationSeconds,
                             List<Reduction> reductions, boolean extinguish, double dashForce,
                             double dashVertical, List<String> lore) {
    }
}
