package dev.exoquests.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens {@code messages.yml}. Keys missing from the server copy fall back to the bundled defaults
 * (reported as warnings) so an older file keeps working after an upgrade.
 */
public final class MessagesLoader {

    private MessagesLoader() {
    }

    public static Messages load(ConfigNode file, ConfigNode defaults) {
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        flatten("", defaults, strings, lists);
        Map<String, String> userStrings = new LinkedHashMap<>();
        Map<String, List<String>> userLists = new LinkedHashMap<>();
        flatten("", file, userStrings, userLists);
        for (String key : strings.keySet()) {
            if (!userStrings.containsKey(key)) {
                file.errors().warn(file.path(), "missing '" + key + "', using the bundled default");
            }
        }
        for (String key : lists.keySet()) {
            if (!userLists.containsKey(key)) {
                file.errors().warn(file.path(), "missing '" + key + "', using the bundled default");
            }
        }
        strings.putAll(userStrings);
        lists.putAll(userLists);
        return new Messages(Map.copyOf(strings), Map.copyOf(lists));
    }

    private static void flatten(String prefix, ConfigNode node, Map<String, String> strings,
                                Map<String, List<String>> lists) {
        for (String key : node.keys()) {
            Object v = node.get(key);
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (v instanceof Map<?, ?>) {
                flatten(full, node.section(key), strings, lists);
            } else if (v instanceof List<?>) {
                lists.put(full, node.stringList(key));
            } else if (v != null) {
                strings.put(full, node.string(key, ""));
            }
        }
    }
}
