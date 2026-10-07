package dev.exo.dailyspinner.config;

import java.util.ArrayList;
import java.util.List;

/** Collects validation problems while loading configuration. Any error rejects the load. */
public final class ConfigErrors {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String file, String path, String message) {
        errors.add(file + " -> " + path + ": " + message);
    }

    public void warn(String file, String path, String message) {
        warnings.add(file + " -> " + path + ": " + message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> errors() {
        return errors;
    }

    public List<String> warnings() {
        return warnings;
    }
}
