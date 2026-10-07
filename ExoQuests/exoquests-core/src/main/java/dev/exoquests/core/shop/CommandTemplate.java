package dev.exoquests.core.shop;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Administrator-written console command with an allowlisted set of placeholders. Placeholder values
 * are produced by the server (validated name, UUID, ids, numbers) and never contain player-supplied text.
 */
public final class CommandTemplate {

    public static final Set<String> PLACEHOLDERS = Set.of("player", "uuid", "item_id", "purchase_id", "price");
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");
    public static final int MAX_LENGTH = 256;

    private CommandTemplate() {
    }

    /** Returns {@code null} when valid, otherwise a description of the problem. */
    public static String validate(String template) {
        if (template == null || template.isBlank()) {
            return "command must not be empty";
        }
        String t = normalize(template);
        if (t.length() > MAX_LENGTH) {
            return "command is longer than " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isISOControl(t.charAt(i))) {
                return "command must not contain control characters or line breaks";
            }
        }
        Matcher m = TOKEN.matcher(t);
        while (m.find()) {
            if (!PLACEHOLDERS.contains(m.group(1))) {
                return "unknown placeholder {" + m.group(1) + "}; allowed: " + PLACEHOLDERS;
            }
        }
        String stripped = TOKEN.matcher(t).replaceAll("");
        if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
            return "unbalanced braces";
        }
        return null;
    }

    public static String normalize(String template) {
        String t = template.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t;
    }

    /**
     * Fills placeholders. Every value must already be validated by the caller; values are inserted
     * verbatim and never re-scanned for placeholders.
     */
    public static String render(String template, Map<String, String> values) {
        String t = normalize(template);
        Matcher m = TOKEN.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException("no value for placeholder " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
