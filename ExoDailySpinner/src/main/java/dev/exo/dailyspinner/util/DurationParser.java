package dev.exo.dailyspinner.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses human friendly durations such as {@code 24h}, {@code 1d 12h}, {@code 90m} or {@code 3600}. */
public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d{1,9})\\s*(d|h|m|s)");

    private DurationParser() {
    }

    /**
     * @return duration in milliseconds
     * @throws IllegalArgumentException when the text is not a valid duration
     */
    public static long parseMillis(String text) {
        if (text == null) {
            throw new IllegalArgumentException("duration is missing");
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("duration is empty");
        }
        if (value.matches("\\d{1,12}")) {
            return Math.multiplyExact(Long.parseLong(value), 1000L);
        }
        Matcher matcher = PART.matcher(value);
        long total = 0;
        int consumed = 0;
        while (matcher.find()) {
            if (!value.substring(consumed, matcher.start()).isBlank()) {
                throw new IllegalArgumentException("invalid duration '" + text + "'");
            }
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
            total = Math.addExact(total, Math.multiplyExact(amount, unit));
            consumed = matcher.end();
        }
        if (consumed == 0 || !value.substring(consumed).isBlank()) {
            throw new IllegalArgumentException("invalid duration '" + text + "' (use e.g. 24h, 1d 12h, 90m)");
        }
        return total;
    }
}
