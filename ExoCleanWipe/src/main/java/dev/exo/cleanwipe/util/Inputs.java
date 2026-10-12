package dev.exo.cleanwipe.util;

import java.util.regex.Pattern;

/**
 * Validation of untrusted command input. Names and reasons are never passed to a command line or concatenated into
 * SQL; this class keeps them short, printable and free of formatting so they also cannot inject MiniMessage tags or
 * legacy colour codes into messages, logs or ban screens.
 */
public final class Inputs {

    /** Java edition names. Floodgate is not installed on dogo, so no prefix is accepted. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Z2-9]{6,10}$");
    private static final Pattern JOB_ID = Pattern.compile("^(?:ew-)?\\d{1,9}$");

    private Inputs() {
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** Accepts "12", "ew-000012". Returns -1 when invalid. */
    public static long parseReference(String input) {
        if (input == null || !JOB_ID.matcher(input.toLowerCase()).matches()) {
            return -1;
        }
        String digits = input.toLowerCase().startsWith("ew-") ? input.substring(3) : input;
        return Long.parseLong(digits);
    }

    /**
     * Normalises a staff-supplied reason: strips control characters, section signs and ampersand colour codes,
     * collapses whitespace and limits the length. The result is still treated as untrusted text and is only ever
     * inserted into messages as an unparsed placeholder.
     */
    public static String cleanReason(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++; // section-sign colour code: drop the sign and its code character
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString()
                .replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("&#[0-9a-fA-F]{6}", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > maxLength) {
            s = s.substring(0, maxLength).trim();
        }
        return s;
    }

    /** Masks the last part of an address for staff without the view-ip permission. */
    public static String maskIp(String ip) {
        if (ip == null) {
            return "?";
        }
        int v4 = ip.lastIndexOf('.');
        if (v4 > 0 && ip.indexOf(':') < 0) {
            int second = ip.lastIndexOf('.', v4 - 1);
            return (second > 0 ? ip.substring(0, second) : ip.substring(0, v4)) + ".x.x";
        }
        int colon = ip.indexOf(':', ip.indexOf(':') + 1);
        return (colon > 0 ? ip.substring(0, colon) : ip) + ":…";
    }
}
