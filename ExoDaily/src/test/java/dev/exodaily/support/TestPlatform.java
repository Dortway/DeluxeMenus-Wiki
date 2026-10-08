package dev.exodaily.support;

import dev.exodaily.core.config.PlatformValidator;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;

import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Server-free stand-in for the Paper validator: materials come from the real Material enum,
 * registry-backed values (enchantments, particles, max stack sizes) from small known sets.
 */
public final class TestPlatform implements PlatformValidator {

    private static final Set<String> ENCHANTMENTS = Set.of("efficiency", "unbreaking", "fortune", "sharpness",
            "looting", "protection", "mending", "silk_touch", "minecraft:efficiency", "minecraft:unbreaking");
    private static final Set<String> SINGLE_STACK = Set.of("DIAMOND_PICKAXE", "DIAMOND_SWORD", "GOLDEN_HELMET",
            "TOTEM_OF_UNDYING", "SADDLE");
    private static final Set<String> PARTICLES = Set.of("HAPPY_VILLAGER", "END_ROD", "FLAME");

    @Override
    public Optional<String> checkItemMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            return Optional.of("unknown material '" + name + "'");
        }
        if (material.isLegacy() || material.name().endsWith("AIR")) {
            return Optional.of("material '" + name + "' is not an obtainable item");
        }
        return Optional.empty();
    }

    @Override
    public int maxStackSize(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (SINGLE_STACK.contains(upper)) {
            return 1;
        }
        return upper.equals("ENDER_PEARL") ? 16 : 64;
    }

    @Override
    public Optional<String> checkEnchantment(String key, int level) {
        return ENCHANTMENTS.contains(key.toLowerCase(Locale.ROOT)) ? Optional.empty()
                : Optional.of("unknown enchantment '" + key + "'");
    }

    @Override
    public Optional<String> checkItemFlag(String flag) {
        try {
            ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT));
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("unknown item flag '" + flag + "'");
        }
    }

    @Override
    public Optional<String> checkSerializedItem(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return bytes.length == 0 ? Optional.of("serialized item is empty") : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("serialized item is not valid Base64");
        }
    }

    @Override
    public int serializedMaxStackSize(String base64) {
        return 64;
    }

    @Override
    public Optional<String> checkParticle(String particle) {
        return PARTICLES.contains(particle.toUpperCase(Locale.ROOT)) ? Optional.empty()
                : Optional.of("unknown particle '" + particle + "'");
    }

    @Override
    public Optional<String> checkSound(String key) {
        return key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? Optional.empty() : Optional.of("invalid sound key '" + key + "'");
    }
}
