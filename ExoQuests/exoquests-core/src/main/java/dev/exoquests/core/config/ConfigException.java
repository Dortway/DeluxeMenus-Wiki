package dev.exoquests.core.config;

import java.util.List;

/** Thrown when one or more configuration files are invalid. Carries every problem found. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
