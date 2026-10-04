package com.exoblacksmith.util;

public final class Durations {
    private Durations() {
    }

    /** Formats milliseconds as a short human string, e.g. "1m 05s" or "12s". */
    public static String format(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds >= 60) {
            return (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
        }
        return seconds + "s";
    }

    public static String seconds(double seconds) {
        if (seconds == Math.floor(seconds)) {
            return ((long) seconds) + "s";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
    }

    public static String percent(double fraction) {
        double pct = fraction * 100.0;
        if (Math.abs(pct - Math.round(pct)) < 1e-9) {
            return Math.round(pct) + "%";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", pct);
    }

    public static String number(double value) {
        if (Math.abs(value - Math.round(value)) < 1e-9) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
