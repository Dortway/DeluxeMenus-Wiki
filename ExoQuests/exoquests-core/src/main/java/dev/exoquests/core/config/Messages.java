package dev.exoquests.core.config;

import java.util.List;
import java.util.Map;

/** MiniMessage templates from {@code messages.yml}, keyed by dotted path. */
public record Messages(Map<String, String> templates, Map<String, List<String>> lists) {

    public String get(String key) {
        String v = templates.get(key);
        return v == null ? "<red>missing message: " + key : v;
    }

    public List<String> list(String key) {
        List<String> v = lists.get(key);
        return v == null ? List.of("<red>missing message: " + key) : v;
    }
}
