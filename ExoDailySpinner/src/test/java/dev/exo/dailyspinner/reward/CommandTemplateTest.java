package dev.exo.dailyspinner.reward;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandTemplateTest {

    @Test
    void substitutesPlaceholders() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals("give Steve diamond 1 123e4567-e89b-12d3-a456-426614174000",
                CommandTemplate.apply("give {player} diamond 1 {uuid}", "Steve", id));
    }

    @Test
    void normalizesAndValidates() {
        assertEquals("say hi", CommandTemplate.normalize("  /say hi "));
        assertThrows(IllegalArgumentException.class, () -> CommandTemplate.normalize("/"));
        assertThrows(IllegalArgumentException.class, () -> CommandTemplate.normalize("say a\nop x"));
        assertThrows(IllegalArgumentException.class, () -> CommandTemplate.normalize("x".repeat(600)));
    }

    @Test
    void rejectsUnsafeNames() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandTemplate.apply("give {player} x", "a b", UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> CommandTemplate.apply("give {player} x", "a;op", UUID.randomUUID()));
        assertTrue(CommandTemplate.isSafePlayerName(".BedrockUser"));
    }
}
