package dev.exoquests.core.config;

import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.shop.ShopCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads every configuration file into a {@link ConfigBundle}. Either all files are valid and a bundle is
 * returned, or a {@link ConfigException} lists every problem and the caller keeps its previous bundle.
 */
public final class ConfigLoader {

    public static final List<String> FILES = List.of("config.yml", "quests.yml", "shop.yml", "messages.yml",
            "menus.yml");
    public static final int CONFIG_VERSION = 1;

    private ConfigLoader() {
    }

    public static ConfigBundle load(Path dir, PlatformValidator platform) throws ConfigException {
        ConfigErrors errors = new ConfigErrors();
        ConfigNode config = YamlFiles.load(dir.resolve("config.yml"), errors);
        long version = config.integer("config-version", CONFIG_VERSION, 1, 1_000);
        if (version > CONFIG_VERSION) {
            errors.add("config.yml", "config-version " + version + " is newer than this build supports");
        }
        Settings settings = SettingsLoader.load(config, platform);
        QuestPool quests = QuestPoolLoader.load(YamlFiles.load(dir.resolve("quests.yml"), errors), platform);
        ShopCatalog shop = ShopLoader.load(YamlFiles.load(dir.resolve("shop.yml"), errors), platform);
        Messages messages = MessagesLoader.load(YamlFiles.load(dir.resolve("messages.yml"), errors),
                bundledDefaults("messages.yml", errors));
        MenuLayouts menus = MenuLayoutsLoader.load(YamlFiles.load(dir.resolve("menus.yml"), errors), platform);
        errors.throwIfAny();
        return new ConfigBundle(settings, quests, shop, messages, menus, new ArrayList<>(errors.warnings()));
    }

    /** Loads only {@code shop.yml}, used after admin shop edits. */
    public static ShopCatalog loadShop(Path file, PlatformValidator platform) throws ConfigException {
        ConfigErrors errors = new ConfigErrors();
        ShopCatalog shop = ShopLoader.load(YamlFiles.load(file, errors), platform);
        errors.throwIfAny();
        return shop;
    }

    static ConfigNode bundledDefaults(String name, ConfigErrors errors) {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return ConfigNode.empty(name, errors);
            }
            ConfigErrors ignored = new ConfigErrors();
            return YamlFiles.load(name + " (bundled)", in, ignored);
        } catch (IOException e) {
            return ConfigNode.empty(name, errors);
        }
    }
}
