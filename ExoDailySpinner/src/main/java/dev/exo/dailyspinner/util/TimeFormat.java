package dev.exo.dailyspinner.util;

/** Formats remaining time compactly, e.g. {@code 5h 12m 3s}. */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String compact(long millis) {
        long totalSeconds = Math.max(0, (millis + 999) / 1000);
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        if (days == 0 && (hours > 0 || minutes > 0)) {
            sb.append(minutes).append("m ");
        }
        if (days == 0 && hours == 0) {
            sb.append(seconds).append('s');
        }
        return sb.toString().trim();
    }

    /** Formats a probability (0..1) as a percentage with sensible precision. */
    public static String percent(double probability) {
        double pct = probability * 100.0;
        if (pct <= 0) {
            return "0%";
        }
        if (pct < 0.01) {
            return "<0.01%";
        }
        if (pct >= 10) {
            return String.format(java.util.Locale.ROOT, "%.1f%%", pct);
        }
        return String.format(java.util.Locale.ROOT, "%.2f%%", pct);
    }
}
