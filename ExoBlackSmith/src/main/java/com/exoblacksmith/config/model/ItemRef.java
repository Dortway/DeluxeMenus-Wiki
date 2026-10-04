package com.exoblacksmith.config.model;

import org.bukkit.Material;

/**
 * Reference to an ingredient or output: either a plugin item ({@code exoId} + {@code level}) or a
 * plain vanilla material.
 */
public record ItemRef(String exoId, int level, Material vanilla) {

    public static ItemRef exo(String id, int level) {
        return new ItemRef(id, level, null);
    }

    public static ItemRef vanilla(Material material) {
        return new ItemRef(null, 1, material);
    }

    public boolean isVanilla() {
        return vanilla != null;
    }

    public String describe() {
        return isVanilla() ? "minecraft:" + vanilla.getKey().getKey() : exoId + (level > 1 ? "@" + level : "");
    }
}
