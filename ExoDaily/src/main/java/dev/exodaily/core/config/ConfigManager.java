package dev.exodaily.core.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active configuration. A reload only replaces it when the new configuration is
 * completely valid; otherwise the last valid configuration stays active.
 */
public final class ConfigManager {

    private final ConfigLoader loader;
    private final AtomicReference<ConfigBundle> current = new AtomicReference<>();

    public ConfigManager(ConfigLoader loader) {
        this.loader = loader;
    }

    public ConfigLoader.Result reload(Map<String, String> files, Map<String, String> defaults) {
        ConfigLoader.Result result = loader.load(files, defaults);
        if (result.success()) {
            current.set(result.bundle());
        }
        return result;
    }

    public Optional<ConfigBundle> current() {
        return Optional.ofNullable(current.get());
    }

    /** The active configuration; only call once {@link #current()} is known to be present. */
    public ConfigBundle get() {
        ConfigBundle bundle = current.get();
        if (bundle == null) {
            throw new IllegalStateException("no valid configuration is loaded");
        }
        return bundle;
    }
}
