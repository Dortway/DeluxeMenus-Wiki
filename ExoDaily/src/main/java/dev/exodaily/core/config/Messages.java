package dev.exodaily.core.config;

import java.util.List;
import java.util.Map;

/** Validated messages.yml: MiniMessage templates by key. */
public record Messages(Map<String, String> templates, Map<String, List<String>> lists) {

    public Messages {
        templates = Map.copyOf(templates);
        lists = Map.copyOf(lists);
    }

    public String get(String key) {
        String value = templates.get(key);
        return value == null ? "<red>missing message: " + key : value;
    }

    public List<String> list(String key) {
        List<String> value = lists.get(key);
        return value == null ? List.of("<red>missing message: " + key) : value;
    }
}
