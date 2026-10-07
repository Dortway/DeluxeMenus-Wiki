package dev.exoquests.core.quest;

import java.util.Set;

/**
 * One entry of the quest pool.
 *
 * @param keys        materials, entity types or wood types (upper case); empty means "any" where allowed
 * @param naturalOnly reject blocks recorded as player-placed (block-break style quests only)
 */
public record QuestDefinition(
        String id,
        String category,
        QuestType type,
        Set<String> keys,
        boolean naturalOnly,
        int target,
        int reward,
        String icon,
        String name,
        String description,
        boolean enabled,
        int weight) {

    public QuestDefinition {
        keys = Set.copyOf(keys);
    }

    public boolean matches(QuestAction action) {
        return action.type() == type && (keys.isEmpty() || keys.contains(action.key()))
                && (!naturalOnly || action.natural());
    }

    /** Objective text with {@code {target}} substituted, e.g. {@code mine 1,000 cobblestone}. */
    public String objective() {
        return description.replace("{target}", String.format(java.util.Locale.ROOT, "%,d", target));
    }
}
