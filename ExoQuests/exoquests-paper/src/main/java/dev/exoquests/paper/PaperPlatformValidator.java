package dev.exoquests.paper;

import dev.exoquests.core.config.PlatformValidator;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemFlag;

/** Validates identifiers against the running server's materials and registries. */
public final class PaperPlatformValidator implements PlatformValidator {

    @Override
    public boolean isBlockMaterial(String name) {
        Material m = Material.getMaterial(name);
        return m != null && m.isBlock() && !m.isAir();
    }

    @Override
    public boolean isItemMaterial(String name) {
        Material m = Material.getMaterial(name);
        return m != null && m.isItem() && !m.isAir();
    }

    @Override
    public boolean isEntityType(String name) {
        try {
            EntityType type = EntityType.valueOf(name);
            return type != EntityType.UNKNOWN && type.isAlive();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean isEnchantment(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k != null && RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k) != null;
    }

    @Override
    public boolean isSpawnReason(String name) {
        try {
            CreatureSpawnEvent.SpawnReason.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean isItemFlag(String name) {
        try {
            ItemFlag.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean isGameMode(String name) {
        try {
            GameMode.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
