package dev.exoquests.paper;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.ConfigLoader;
import dev.exoquests.core.shop.ShopCatalog;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Owns the active configuration. A reload parses every file off the main thread; only a fully valid
 * result replaces the active bundle, otherwise the previous bundle stays in effect.
 */
public final class ConfigManager {

    private final ExoQuestsPlugin plugin;
    private final Executor io;
    private final Logger logger;
    private final PaperPlatformValidator validator = new PaperPlatformValidator();
    private final AtomicReference<ConfigBundle> current = new AtomicReference<>();

    public ConfigManager(ExoQuestsPlugin plugin, Executor io) {
        this.plugin = plugin;
        this.io = io;
        this.logger = plugin.getLogger();
    }

    public ConfigBundle current() {
        return current.get();
    }

    public PaperPlatformValidator validator() {
        return validator;
    }

    public Path directory() {
        return plugin.getDataFolder().toPath();
    }

    /** Writes any missing default file, then loads synchronously (startup only). */
    public void loadInitial() throws ConfigException {
        saveDefaults();
        ConfigBundle bundle = ConfigLoader.load(directory(), validator);
        logWarnings(bundle);
        current.set(bundle);
    }

    private void saveDefaults() {
        for (String file : ConfigLoader.FILES) {
            if (!new File(plugin.getDataFolder(), file).exists()) {
                plugin.saveResource(file, false);
            }
        }
    }

    /**
     * Reloads every file. Completes with the new bundle (already applied on the main thread) or
     * exceptionally with a {@link ConfigException}; in that case nothing changed.
     */
    public CompletableFuture<ConfigBundle> reload() {
        return CompletableFuture.supplyAsync(() -> {
            saveDefaults();
            try {
                return ConfigLoader.load(directory(), validator);
            } catch (ConfigException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, io).thenApplyAsync(bundle -> {
            ConfigBundle previous = current.get();
            if (!previous.settings().storage().equals(bundle.settings().storage())) {
                logger.warning("storage settings changed; they take effect after a restart");
            }
            logWarnings(bundle);
            current.set(bundle);
            plugin.onConfigApplied(bundle);
            return bundle;
        }, plugin.mainExecutor());
    }

    /** Replaces only the shop (after an admin edit that was validated and saved). Main thread. */
    public void applyShop(ShopCatalog shop) {
        ConfigBundle bundle = current.get().withShop(shop);
        current.set(bundle);
        plugin.onConfigApplied(bundle);
    }

    public Executor io() {
        return io;
    }

    private void logWarnings(ConfigBundle bundle) {
        for (String w : bundle.warnings()) {
            logger.warning("config: " + w);
        }
    }

    public static void logProblems(Logger logger, ConfigException e) {
        logger.severe("Configuration has " + e.problems().size() + " problem(s):");
        for (String p : e.problems()) {
            logger.severe("  - " + p);
        }
    }
}
