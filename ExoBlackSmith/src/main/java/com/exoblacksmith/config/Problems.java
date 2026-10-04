package com.exoblacksmith.config;

import java.util.ArrayList;
import java.util.List;

/** Collects configuration errors and warnings with file/key context. */
public final class Problems {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String file, String key, String message) {
        errors.add(file + ": " + key + ": " + message);
    }

    public void warn(String file, String key, String message) {
        warnings.add(file + ": " + key + ": " + message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
