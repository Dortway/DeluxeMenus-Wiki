package net.exoeconomy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;
import java.util.Map;

/** Colour formatting compatible with ExoSkills: '&' codes plus '&#RRGGBB' hex colours. */
public final class Text {
    /** Holder so the duration helpers work without Adventure loaded (unit tests). */
    private static final class Colors {
        static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
                .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    }

    private Text() {}

    public static Component color(String text) {
        return Colors.SERIALIZER.deserialize(text == null ? "" : text);
    }

    /** For item names and lore: same, without the default italics. */
    public static Component item(String text) {
        return color(text).decoration(TextDecoration.ITALIC, false);
    }

    public static String replace(String text, Map<String, String> values) {
        String out = text;
        for (Map.Entry<String, String> e : values.entrySet()) out = out.replace("%" + e.getKey() + "%", e.getValue());
        return out;
    }

    /** "1h 05m", "4m 09s", "12s". */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return String.format(Locale.ROOT, "%dd %02dh", d, h);
        if (h > 0) return String.format(Locale.ROOT, "%dh %02dm", h, m);
        if (m > 0) return String.format(Locale.ROOT, "%dm %02ds", m, sec);
        return sec + "s";
    }

    /** Parses "30m", "1h30m", "45s", "2d", "1500ms". */
    public static long parseDuration(String text) {
        if (text == null || !text.trim().toLowerCase(Locale.ROOT).matches("(\\d+(ms|d|h|m|s))+"))
            throw new IllegalArgumentException("Invalid duration '" + text + "' (use e.g. 30m, 1h30m, 2d)");
        long total = 0;
        var matcher = java.util.regex.Pattern.compile("(\\d+)(ms|d|h|m|s)").matcher(text.trim().toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            long n = Long.parseLong(matcher.group(1));
            total = Math.addExact(total, Math.multiplyExact(n, switch (matcher.group(2)) {
                case "d" -> 86_400_000L; case "h" -> 3_600_000L; case "m" -> 60_000L; case "s" -> 1000L; default -> 1L;
            }));
        }
        return total;
    }

    public static String title(String id) {
        if (id.isEmpty()) return id;
        String s = id.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
