package dev.exoquests.core.config;

import java.util.ArrayList;
import java.util.List;

/** Accumulates validation problems so a single reload reports everything at once. */
public final class ConfigErrors {

    private final List<String> problems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void add(String path, String message) {
        problems.add(path + ": " + message);
    }

    public void warn(String path, String message) {
        warnings.add(path + ": " + message);
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public void throwIfAny() throws ConfigException {
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
    }
}
