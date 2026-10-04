package com.exoblacksmith.integration;

import org.bukkit.inventory.meta.ItemMeta;

/** Supplies resource-pack visuals. Never used for identity. */
public interface VisualProvider {
    /** Copies visual components for {@code namespacedId} onto {@code meta}; returns false if unavailable. */
    boolean applyVisuals(ItemMeta meta, String namespacedId);

    /** Replaces font-image placeholders (e.g. {@code :exoblacksmith:rarity_epic:}) when supported. */
    String replaceGlyphs(String text);

    boolean ready();

    String name();

    VisualProvider NONE = new VisualProvider() {
        @Override
        public boolean applyVisuals(ItemMeta meta, String namespacedId) {
            return false;
        }

        @Override
        public String replaceGlyphs(String text) {
            return text;
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String name() {
            return "none";
        }
    };
}
