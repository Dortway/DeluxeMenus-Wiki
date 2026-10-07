package dev.exo.dailyspinner.config;

import dev.exo.dailyspinner.reward.RewardRegistry;

/**
 * One fully validated configuration generation. Bundles are immutable and swapped atomically on
 * reload, so in-flight menus and spins keep working with the generation they started with.
 *
 * @param rewardsYaml raw rewards.yml content this registry was built from (used by reward edits)
 */
public record ConfigBundle(Settings settings, TextService text, Messages messages, MenuSettings menus,
                           RewardRegistry rewards, String rewardsYaml) {
}
