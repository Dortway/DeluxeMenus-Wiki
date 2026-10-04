package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;

/** Upgrade materials (rune upgrades) and mask totems. {@code maskId} is set for totems only. */
public record MaterialDef(String id, ItemKind kind, String name, Rarity rarity, Appearance appearance,
                          List<String> lore, String maskId) implements ItemDef {
    @Override
    public Rarity rarity(int level) {
        return rarity;
    }

    @Override
    public Appearance appearance(int level) {
        return appearance;
    }
}
