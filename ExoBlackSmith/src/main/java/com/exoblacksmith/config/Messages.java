package com.exoblacksmith.config;

import java.util.Map;

/** Raw MiniMessage templates from messages.yml. Rendering happens in {@code util.Text}. */
public final class Messages {
    private final Map<String, String> templates;

    public Messages(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
    }

    public String raw(String key) {
        String value = templates.get(key);
        return value == null ? "<red>missing message: " + key + "</red>" : value;
    }

    public boolean has(String key) {
        return templates.containsKey(key);
    }

    public Map<String, String> all() {
        return templates;
    }
}
