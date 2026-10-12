package dev.exo.cleanwipe.wipe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staff decisions recorded with a job (stored as {@code key=value;...} in the jobs table so a resumed job behaves the
 * same after a restart). {@code restoreFrom} is the job whose backups a RESTORE job applies (0 otherwise).
 */
public record WipeOptions(boolean disbandSharedIslands, boolean keepSharedIslands, long restoreFrom) {

    public static final WipeOptions DEFAULT = new WipeOptions(false, false, 0);

    public String encode() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("shared", Boolean.toString(disbandSharedIslands));
        m.put("keep-shared", Boolean.toString(keepSharedIslands));
        m.put("from", Long.toString(restoreFrom));
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }

    public static WipeOptions decode(String s) {
        if (s == null || s.isBlank()) {
            return DEFAULT;
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        long from;
        try {
            from = Long.parseLong(m.getOrDefault("from", "0"));
        } catch (NumberFormatException e) {
            from = 0;
        }
        return new WipeOptions(Boolean.parseBoolean(m.get("shared")), Boolean.parseBoolean(m.get("keep-shared")), from);
    }
}
