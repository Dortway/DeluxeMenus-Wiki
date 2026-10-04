package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;
import org.bukkit.entity.EntityType;

/** A mob value head used as a crafting ingredient. */
public record HeadDef(String id, EntityType mob, String name, Rarity rarity, Appearance appearance,
                      List<String> lore, double dropChance, int dropMin, int dropMax) implements ItemDef {
    @Override
    public ItemKind kind() {
        return ItemKind.HEAD;
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
