package dev.exodaily.paper.menu;

import dev.exodaily.core.reward.RewardPosition;

/** What a slot does. Resolved from the server-side holder, never from item names or lore. */
public sealed interface MenuAction {

    record Open(MenuType type) implements MenuAction {
    }

    record Claim(RewardPosition position) implements MenuAction {
    }
}
