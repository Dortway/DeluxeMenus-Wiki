package dev.exo.dailyspinner.util;

/**
 * Converts plain latin letters to their small-capital Unicode look-alikes.
 *
 * <p>Text wrapped in {@code <sc>...</sc>} is converted while MiniMessage tags inside the region
 * (for example {@code <primary>} or {@code <time>}) are left untouched. When small caps are
 * disabled the markers are simply removed, which gives a readable fallback.</p>
 */
public final class SmallCaps {

    private static final String OPEN = "<sc>";
    private static final String CLOSE = "</sc>";
    private static final char[] MAP = new char[26];

    static {
        String caps = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        for (int i = 0; i < 26; i++) {
            MAP[i] = caps.charAt(i);
        }
    }

    private SmallCaps() {
    }

    /** Converts every latin letter in {@code input}; other characters are kept. */
    public static String convert(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            out.append(convertChar(input.charAt(i)));
        }
        return out.toString();
    }

    private static char convertChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return MAP[c - 'a'];
        }
        if (c >= 'A' && c <= 'Z') {
            return MAP[c - 'A'];
        }
        return c;
    }

    /**
     * Processes {@code <sc>} regions in a MiniMessage string.
     *
     * @param input   raw MiniMessage text
     * @param enabled whether letters should be converted (otherwise markers are just removed)
     */
    public static String process(String input, boolean enabled) {
        if (input.indexOf(OPEN) < 0) {
            return input.replace(CLOSE, "");
        }
        StringBuilder out = new StringBuilder(input.length());
        int depth = 0;
        int i = 0;
        while (i < input.length()) {
            if (input.startsWith(OPEN, i)) {
                depth++;
                i += OPEN.length();
                continue;
            }
            if (input.startsWith(CLOSE, i)) {
                depth = Math.max(0, depth - 1);
                i += CLOSE.length();
                continue;
            }
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                // Escaped character: copy escape and character verbatim.
                out.append(c).append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = input.indexOf('>', i);
                if (end > i) {
                    out.append(input, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            out.append(depth > 0 && enabled ? convertChar(c) : c);
            i++;
        }
        return out.toString();
    }
}
