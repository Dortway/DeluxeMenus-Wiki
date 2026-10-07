package dev.exo.dailyspinner.reward;

import org.bukkit.Material;

/**
 * A cosmetic reward tier.
 *
 * @param name     MiniMessage display name
 * @param pane     glass pane used to frame the reel when this rarity wins
 * @param order    sort order (higher = rarer)
 * @param announce broadcast wins of this rarity by default
 * @param celebrate play the special win sound and effects
 */
public record Rarity(String id, String name, Material pane, int order, boolean announce, boolean celebrate) {
}
