package dev.exodaily.core.config;

import java.util.Optional;

/**
 * Server-specific checks used during configuration validation. Each method returns an error
 * message, or empty when the value is valid. The Paper implementation must be called on the
 * server thread.
 */
public interface PlatformValidator {

    /** The material must exist, be an obtainable item and not be air or legacy. */
    Optional<String> checkItemMaterial(String material);

    /** Any material usable as a menu icon (must be an item). */
    default Optional<String> checkIconMaterial(String material) {
        return checkItemMaterial(material);
    }

    /** Maximum stack size of a valid item material. */
    int maxStackSize(String material);

    Optional<String> checkEnchantment(String key, int level);

    Optional<String> checkItemFlag(String flag);

    /** Base64 produced by {@code ItemStack#serializeAsBytes}. */
    Optional<String> checkSerializedItem(String base64);

    /** Maximum stack size of a serialized item, or -1 if unknown. */
    int serializedMaxStackSize(String base64);

    Optional<String> checkParticle(String particle);

    Optional<String> checkSound(String key);
}
