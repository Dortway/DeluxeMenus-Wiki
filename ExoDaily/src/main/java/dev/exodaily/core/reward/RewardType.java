package dev.exodaily.core.reward;

import java.util.Locale;
import java.util.Optional;

/**
 * What claiming a reward does. Items are delivered all-or-nothing and can be rolled back;
 * console commands cannot be rolled back, so command rewards have weaker guarantees (see README).
 */
public enum RewardType {
    /** Gives the item (default). */
    ITEM("item"),
    /** Runs console commands only; the material is just the menu icon. */
    COMMAND("command"),
    /** Gives the item, then runs the commands. */
    BOTH("both");

    private final String key;

    RewardType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean givesItems() {
        return this != COMMAND;
    }

    public boolean runsCommands() {
        return this != ITEM;
    }

    public static Optional<RewardType> parse(String value) {
        for (RewardType type : values()) {
            if (type.key.equals(value.toLowerCase(Locale.ROOT))) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
