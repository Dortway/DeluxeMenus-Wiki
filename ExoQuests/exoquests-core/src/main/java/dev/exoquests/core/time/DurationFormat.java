package dev.exoquests.core.time;

import java.time.Duration;

/** Compact, lowercase duration formatting such as {@code 5h 23m} or {@code 42s}. */
public final class DurationFormat {

    private DurationFormat() {
    }

    public static String compact(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
