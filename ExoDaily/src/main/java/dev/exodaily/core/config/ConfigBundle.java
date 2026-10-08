package dev.exodaily.core.config;

import dev.exodaily.core.reward.RewardCatalog;

/** One complete, validated configuration. Replaced atomically on a successful reload. */
public record ConfigBundle(PluginSettings settings, Messages messages, MenuConfig menus, RewardCatalog rewards) {
}
