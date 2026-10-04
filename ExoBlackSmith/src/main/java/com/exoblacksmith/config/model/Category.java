package com.exoblacksmith.config.model;

/** Blacksmith catalog categories. */
public enum Category {
    ARMOR, MASKS, RUNES, UPGRADES;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
