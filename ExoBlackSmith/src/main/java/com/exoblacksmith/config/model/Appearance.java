package com.exoblacksmith.config.model;

import org.bukkit.Material;

/**
 * Visual-only item settings. None of these fields participate in item identity.
 *
 * @param material      base material
 * @param itemModel     optional item_model override (namespaced key), e.g. {@code minecraft:echo_shard}
 * @param customModelData optional custom model data float
 * @param itemsAdderId  optional ItemsAdder namespaced id whose model/equippable visuals are copied
 * @param texture       optional player-head base64 texture (PLAYER_HEAD only)
 * @param glint         enchantment glint override
 */
public record Appearance(Material material, String itemModel, Float customModelData, String itemsAdderId,
                         String texture, boolean glint) {
}
