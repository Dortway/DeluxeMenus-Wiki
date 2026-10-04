package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;

/** One custom armor piece. Base armor/toughness come from the vanilla material. */
public record ArmorPieceDef(String id, String name, String setId, EquipSlot slot, Rarity rarity,
                            Appearance appearance, List<String> lore, List<Reduction> reductions,
                            double speedBonus) implements ItemDef {
    @Override
    public ItemKind kind() {
        return ItemKind.ARMOR;
    }

    @Override
    public Rarity rarity(int level) {
        return rarity;
    }

    @Override
    public Appearance appearance(int level) {
        return appearance;
    }
}
