package com.exoblacksmith.item;

/** Category of an ExoBlackSmith item. Stored in item data; never inferred from appearance. */
public enum ItemKind {
    HEAD(false),
    MASK(true),
    RUNE(false),
    ARMOR(true),
    MATERIAL(false),
    TOTEM(false);

    private final boolean unique;

    ItemKind(boolean unique) {
        this.unique = unique;
    }

    /** Unique items carry a per-copy UID and never stack. */
    public boolean unique() {
        return unique;
    }
}
