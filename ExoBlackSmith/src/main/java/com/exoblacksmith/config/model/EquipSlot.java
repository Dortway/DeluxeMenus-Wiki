package com.exoblacksmith.config.model;

import org.bukkit.inventory.EquipmentSlot;

/** Armor slot restriction used by armor pieces and rune compatibility. */
public enum EquipSlot {
    HELMET(EquipmentSlot.HEAD),
    CHESTPLATE(EquipmentSlot.CHEST),
    LEGGINGS(EquipmentSlot.LEGS),
    BOOTS(EquipmentSlot.FEET),
    ANY(null);

    private final EquipmentSlot bukkit;

    EquipSlot(EquipmentSlot bukkit) {
        this.bukkit = bukkit;
    }

    public EquipmentSlot bukkit() {
        return bukkit;
    }

    public boolean accepts(EquipSlot pieceSlot) {
        return this == ANY || this == pieceSlot;
    }

    public String display() {
        return this == ANY ? "any armor" : name().toLowerCase(java.util.Locale.ROOT);
    }

    public static final EquipSlot[] ARMOR_SLOTS = {HELMET, CHESTPLATE, LEGGINGS, BOOTS};
}
