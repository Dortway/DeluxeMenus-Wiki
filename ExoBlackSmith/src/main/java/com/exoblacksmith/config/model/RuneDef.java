package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;
import java.util.Map;

/**
 * A rune with three tiers. {@code params} holds mechanic-specific extras such as
 * {@code dash-force} or {@code cooldown}.
 */
public record RuneDef(String id, String name, RuneMechanic mechanic, EquipSlot slot, Appearance appearance,
                      List<String> lore, String effectLine, Map<Integer, Tier> tiers,
                      Map<String, Double> params) implements ItemDef {

    public record Tier(int tier, Rarity rarity, double value) {
    }

    @Override
    public ItemKind kind() {
        return ItemKind.RUNE;
    }

    @Override
    public int maxLevel() {
        return tiers.size();
    }

    public Tier tier(int tier) {
        return tiers.get(Math.max(1, Math.min(tier, maxLevel())));
    }

    public double param(String key, double fallback) {
        return params.getOrDefault(key, fallback);
    }

    @Override
    public Rarity rarity(int level) {
        return tier(level).rarity();
    }

    @Override
    public Appearance appearance(int level) {
        return appearance;
    }
}
