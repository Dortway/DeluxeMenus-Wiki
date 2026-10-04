package com.exoblacksmith.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical identity of an ExoBlackSmith item. This is the only thing the plugin trusts;
 * names, lore, textures and models are presentation and are regenerated from config.
 *
 * @param kind  item category
 * @param id    stable internal id, e.g. {@code blast_rune}
 * @param level tier (runes) or level (masks); 1 for items without levels
 * @param uid   per-copy id for unique items (armor, masks), {@code null} for stackables
 * @param runes installed runes (armor only)
 */
public record ItemData(ItemKind kind, String id, int level, String uid, List<RuneSlot> runes) {

    public static final int SCHEMA = 1;

    public ItemData {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid id: " + id);
        }
        if (level < 1) {
            throw new IllegalArgumentException("invalid level: " + level);
        }
        if (kind.unique() && (uid == null || uid.isEmpty())) {
            throw new IllegalArgumentException(kind + " items require a uid");
        }
        if (!kind.unique() && uid != null) {
            throw new IllegalArgumentException(kind + " items must not carry a uid");
        }
        runes = runes == null ? List.of() : List.copyOf(runes);
        if (kind != ItemKind.ARMOR && !runes.isEmpty()) {
            throw new IllegalArgumentException("only armor can hold runes");
        }
        if (runes.size() > RuneSlot.MAX_SLOTS) {
            throw new IllegalArgumentException("too many runes");
        }
    }

    public static ItemData stackable(ItemKind kind, String id, int level) {
        return new ItemData(kind, id, level, null, List.of());
    }

    public ItemData withRune(RuneSlot rune) {
        List<RuneSlot> next = new ArrayList<>(runes);
        next.add(rune);
        return new ItemData(kind, id, level, uid, next);
    }

    public ItemData withLevelAndUid(int newLevel, String newUid) {
        return new ItemData(kind, id, newLevel, newUid, runes);
    }

    public boolean hasRune(String runeId) {
        for (RuneSlot slot : runes) {
            if (slot.runeId().equals(runeId)) {
                return true;
            }
        }
        return false;
    }

    /** The exact byte string that is signed. Any change to identity fields changes the signature. */
    public String canonical() {
        return SCHEMA + "|" + kind.name() + "|" + id + "|" + level + "|" + (uid == null ? "" : uid) + "|" + RuneSlot.encode(runes);
    }
}
