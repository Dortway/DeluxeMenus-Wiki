package dev.exoquests.core.config;

/**
 * Validates platform identifiers (materials, entity types, enchantments...) that the core module
 * cannot know about. The Paper adapter implements this against the running server's registries.
 */
public interface PlatformValidator {

    /** Accepts any syntactically plausible identifier; used by unit tests. */
    PlatformValidator PERMISSIVE = new PlatformValidator() {
    };

    default boolean isBlockMaterial(String name) {
        return name.matches("[A-Z0-9_]+");
    }

    default boolean isItemMaterial(String name) {
        return name.matches("[A-Z0-9_]+");
    }

    default boolean isEntityType(String name) {
        return name.matches("[A-Z0-9_]+");
    }

    default boolean isEnchantment(String key) {
        return key.matches("[a-z0-9_.:-]+");
    }

    default boolean isSpawnReason(String name) {
        return name.matches("[A-Z0-9_]+");
    }

    default boolean isItemFlag(String name) {
        return name.matches("[A-Z0-9_]+");
    }

    default boolean isGameMode(String name) {
        return name.matches("SURVIVAL|ADVENTURE|CREATIVE|SPECTATOR");
    }
}
