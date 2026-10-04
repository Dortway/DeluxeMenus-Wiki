package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;
import java.util.Map;

/** A wearable mask (custom player head worn in the helmet slot). */
public record MaskDef(String id, String name, String ingredientHead, Appearance appearance, List<String> lore,
                      Map<Integer, MaskLevel> levels) implements ItemDef {
    @Override
    public ItemKind kind() {
        return ItemKind.MASK;
    }

    @Override
    public int maxLevel() {
        return levels.size();
    }

    public MaskLevel level(int level) {
        return levels.get(Math.max(1, Math.min(level, maxLevel())));
    }

    @Override
    public Rarity rarity(int level) {
        return level(level).rarity();
    }

    @Override
    public Appearance appearance(int level) {
        return appearance;
    }
}
