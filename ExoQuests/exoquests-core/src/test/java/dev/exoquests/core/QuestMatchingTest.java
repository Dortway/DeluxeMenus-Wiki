package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestMatchingTest {

    private static QuestDefinition def(QuestType type, Set<String> keys, boolean naturalOnly) {
        return new QuestDefinition("q", "mining", type, keys, naturalOnly, 10, 10, "STONE", "q", "q", true, 10);
    }

    @Test
    void naturalOnlyQuestsIgnorePlacedBlocks() {
        QuestDefinition natural = def(QuestType.BLOCK_BREAK, Set.of("COBBLESTONE"), true);
        QuestDefinition any = def(QuestType.BLOCK_BREAK, Set.of("COBBLESTONE"), false);
        QuestAction placed = new QuestAction(QuestType.BLOCK_BREAK, "COBBLESTONE", 1, false);
        QuestAction generated = new QuestAction(QuestType.BLOCK_BREAK, "COBBLESTONE", 1, true);
        assertFalse(natural.matches(placed));
        assertTrue(natural.matches(generated));
        assertTrue(any.matches(placed));
        assertTrue(any.matches(generated));
    }

    @Test
    void typeAndKeyMustMatch() {
        QuestDefinition ore = def(QuestType.BLOCK_BREAK, Set.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"), true);
        assertTrue(ore.matches(new QuestAction(QuestType.BLOCK_BREAK, "DEEPSLATE_DIAMOND_ORE", 1)));
        assertFalse(ore.matches(new QuestAction(QuestType.BLOCK_BREAK, "IRON_ORE", 1)));
        assertFalse(ore.matches(new QuestAction(QuestType.CROP_HARVEST, "DIAMOND_ORE", 1)));
        QuestDefinition anyFish = def(QuestType.FISH_CATCH, Set.of(), false);
        assertTrue(anyFish.matches(new QuestAction(QuestType.FISH_CATCH, "NAME_TAG", 1)));
    }
}
