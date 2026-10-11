package net.exoeconomy.config;

import net.exoeconomy.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** messages.yml with fallback to the bundled defaults for any missing key. */
public final class Messages {
    private final Map<String, Object> values = new HashMap<>();
    private final String prefix;

    public Messages(ConfigurationSection user, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(true)) if (!defaults.isConfigurationSection(key)) values.put(key, defaults.get(key));
        for (String key : user.getKeys(true)) if (!user.isConfigurationSection(key)) values.put(key, user.get(key));
        this.prefix = String.valueOf(values.getOrDefault("prefix", ""));
    }

    public String raw(String key) {
        Object v = values.get(key);
        return v == null ? key : v instanceof List<?> list ? String.join("\n", list.stream().map(String::valueOf).toList()) : String.valueOf(v);
    }

    public List<String> lines(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return v == null ? List.of(key) : List.of(String.valueOf(v));
    }

    public boolean has(String key) { return values.containsKey(key); }

    public Component get(String key, String... placeholders) {
        return Text.color(format(key, placeholders));
    }

    public String format(String key, String... placeholders) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) map.put(placeholders[i], placeholders[i + 1]);
        map.put("prefix", prefix);
        return Text.replace(raw(key), map);
    }

    public void send(CommandSender to, String key, String... placeholders) {
        String text = format(key, placeholders);
        if (!text.isEmpty()) to.sendMessage(Text.color(text));
    }
}
