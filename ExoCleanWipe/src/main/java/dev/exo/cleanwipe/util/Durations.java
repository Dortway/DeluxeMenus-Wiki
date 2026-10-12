package dev.exo.cleanwipe.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats readable durations: {@code 30days}, {@code 12hours}, {@code 45minutes}, {@code 1d12h},
 * {@code 2w}, {@code permanent}. Parsing is strict: anything that is not fully understood is rejected.
 */
public final class Durations {

    /** Marker returned for permanent punishments. */
    public static final Duration PERMANENT = Duration.ofSeconds(Long.MAX_VALUE);

    private static final Pattern PART = Pattern.compile("(\\d{1,6})([a-z]+)");

    private static final Map<String, Long> UNITS = Map.ofEntries(
            Map.entry("s", 1L), Map.entry("sec", 1L), Map.entry("secs", 1L), Map.entry("second", 1L), Map.entry("seconds", 1L),
            Map.entry("m", 60L), Map.entry("min", 60L), Map.entry("mins", 60L), Map.entry("minute", 60L), Map.entry("minutes", 60L),
            Map.entry("h", 3600L), Map.entry("hr", 3600L), Map.entry("hrs", 3600L), Map.entry("hour", 3600L), Map.entry("hours", 3600L),
            Map.entry("d", 86400L), Map.entry("day", 86400L), Map.entry("days", 86400L),
            Map.entry("w", 604800L), Map.entry("week", 604800L), Map.entry("weeks", 604800L),
            Map.entry("mo", 2592000L), Map.entry("month", 2592000L), Map.entry("months", 2592000L),
            Map.entry("y", 31536000L), Map.entry("year", 31536000L), Map.entry("years", 31536000L));

    private Durations() {
    }

    public static boolean isPermanentWord(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        return s.equals("perm") || s.equals("permanent") || s.equals("forever");
    }

    /**
     * @throws IllegalArgumentException with a lowercase, player-facing explanation
     */
    public static Duration parse(String input, Duration max) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("missing duration");
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (isPermanentWord(s)) {
            return PERMANENT;
        }
        if (s.length() > 32) {
            throw new IllegalArgumentException("duration is too long");
        }
        Matcher m = PART.matcher(s);
        int pos = 0;
        long seconds = 0;
        while (pos < s.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("could not read duration '" + input + "' (try 30days, 12hours or 45minutes)");
            }
            Long unit = UNITS.get(m.group(2));
            if (unit == null) {
                throw new IllegalArgumentException("unknown time unit '" + m.group(2) + "' (use minutes, hours, days, weeks)");
            }
            long amount = Long.parseLong(m.group(1));
            try {
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, unit));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("duration is too long");
            }
            pos = m.end();
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("duration must be longer than zero");
        }
        Duration d = Duration.ofSeconds(seconds);
        if (max != null && d.compareTo(max) > 0) {
            throw new IllegalArgumentException("duration is longer than the configured maximum (" + format(max) + ")");
        }
        return d;
    }

    /** "30 days", "1 day, 2 hours", "permanent". Shows at most {@code parts} units. */
    public static String format(Duration d, int parts) {
        if (d == null || d.equals(PERMANENT)) {
            return "permanent";
        }
        long total = Math.max(0, d.getSeconds());
        if (total < 60) {
            return total <= 1 ? (total == 1 ? "1 second" : "less than a minute") : total + " seconds";
        }
        long[] sizes = {86400, 3600, 60};
        String[] names = {"day", "hour", "minute"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sizes.length && out.size() < parts; i++) {
            long n = total / sizes[i];
            total %= sizes[i];
            if (n > 0) {
                out.add(n + " " + names[i] + (n == 1 ? "" : "s"));
            }
        }
        return String.join(", ", out);
    }

    public static String format(Duration d) {
        return format(d, 2);
    }
}
