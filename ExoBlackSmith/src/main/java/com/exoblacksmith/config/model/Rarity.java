package com.exoblacksmith.config.model;

/**
 * Configurable rarity presentation. {@code color} is a {@code #rrggbb} hex string. {@code glyph} is an
 * optional ItemsAdder font-image placeholder (e.g. {@code :exoblacksmith:rarity_epic:}) used only when
 * ItemsAdder has loaded; otherwise {@code symbol} (unicode) or {@code plainSymbol} is shown.
 */
public record Rarity(String id, String displayName, String color, String symbol, String plainSymbol, String glyph,
                     int weight) {
}
