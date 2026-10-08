package dev.exodaily.paper;

import dev.exodaily.core.config.PlatformValidator;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/** Validates configuration values against the running server's registries. Server thread only. */
public final class PaperPlatformValidator implements PlatformValidator {

    /** Off until the server has finished enabling plugins, so commands of later plugins are not reported. */
    private volatile boolean commandChecks;

    public void enableCommandChecks() {
        commandChecks = true;
    }

    @Override
    public Optional<String> checkItemMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            return Optional.of("unknown material '" + name + "'");
        }
        if (material.isLegacy()) {
            return Optional.of("legacy material '" + name + "' is not supported");
        }
        if (!material.isItem() || material.isAir()) {
            return Optional.of("material '" + name + "' is not an obtainable item");
        }
        return Optional.empty();
    }

    @Override
    public int maxStackSize(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? 64 : material.getMaxStackSize();
    }

    @Override
    public Optional<String> checkEnchantment(String key, int level) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        if (namespacedKey == null) {
            return Optional.of("invalid enchantment key '" + key + "'");
        }
        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(namespacedKey);
        if (enchantment == null) {
            return Optional.of("unknown enchantment '" + key + "'");
        }
        if (level < 1 || level > 255) {
            return Optional.of("enchantment level must be between 1 and 255");
        }
        return Optional.empty();
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
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
            if (item.isEmpty()) {
                return Optional.of("serialized item is empty");
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of("serialized item cannot be read on this server: " + e.getMessage());
        }
    }

    @Override
    public int serializedMaxStackSize(String base64) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64)).getMaxStackSize();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public Optional<String> checkParticle(String name) {
        try {
            Particle particle = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            if (particle.getDataType() != Void.class) {
                return Optional.of("particle '" + name + "' needs extra data; choose a simple particle such as HAPPY_VILLAGER");
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("unknown particle '" + name + "'");
        }
    }

    @Override
    public Optional<String> commandWarning(String label) {
        if (commandChecks && org.bukkit.Bukkit.getCommandMap().getCommand(label.toLowerCase(Locale.ROOT)) == null) {
            return Optional.of("command '" + label + "' is not registered right now; if no plugin provides it,"
                    + " claims of this reward will be flagged for review");
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> checkSound(String key) {
        try {
            Key.key(key);
            return Optional.empty();
        } catch (InvalidKeyException e) {
            return Optional.of("invalid sound key '" + key + "' (example: minecraft:block.amethyst_block.chime)");
        }
    }
}
