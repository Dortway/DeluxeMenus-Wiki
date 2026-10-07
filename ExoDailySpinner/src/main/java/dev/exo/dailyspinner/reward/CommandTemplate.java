package dev.exo.dailyspinner.reward;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates administrator command rewards and substitutes placeholders.
 *
 * <p>Supported placeholders: {@code {player}} (player name) and {@code {uuid}} (player UUID).
 * Commands are executed by the console; they are trusted administrator configuration.</p>
 */
public final class CommandTemplate {

    public static final int MAX_LENGTH = 512;
    /** Names allowed for substitution (Java names plus common Bedrock-bridge prefixes). */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.*\\-]{1,32}");

    private CommandTemplate() {
    }

    /** Normalises (strips a leading slash) and validates a configured command. */
    public static String normalize(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command is missing");
        }
        String value = command.trim();
        while (value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("command is longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new IllegalArgumentException("command must be a single line");
            }
        }
        return value;
    }

    public static boolean isSafePlayerName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /**
     * @throws IllegalArgumentException if the player name contains characters that could alter the command
     */
    public static String apply(String command, String playerName, UUID playerId) {
        if (!isSafePlayerName(playerName)) {
            throw new IllegalArgumentException("unsafe player name for command substitution: " + playerName);
        }
        return command
                .replace("{player}", playerName)
                .replace("{uuid}", playerId.toString().toLowerCase(Locale.ROOT));
    }
}
