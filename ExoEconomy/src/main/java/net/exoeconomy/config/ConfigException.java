package net.exoeconomy.config;

import java.util.List;

/** Configuration could not be loaded; carries every problem found, not just the first. */
public final class ConfigException extends Exception {
    public final List<String> problems;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }
}
