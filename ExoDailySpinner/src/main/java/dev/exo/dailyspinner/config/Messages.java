package dev.exo.dailyspinner.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

/** Messages from messages.yml. Each entry may be a single line or a list of lines. */
public final class Messages {

    private final Map<String, List<String>> entries;
    private final TextService text;
    private final TagResolver prefix;

    public Messages(Map<String, List<String>> entries, TextService text) {
        this.entries = Map.copyOf(entries);
        this.text = text;
        List<String> prefixLines = entries.getOrDefault("prefix", List.of(""));
        this.prefix = Placeholder.component("prefix", text.parse(prefixLines.isEmpty() ? "" : prefixLines.get(0)));
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public List<String> raw(String key) {
        return entries.getOrDefault(key, List.of("<red>Missing message: " + key));
    }

    public Component line(String key, TagResolver... resolvers) {
        List<String> lines = raw(key);
        return text.parse(String.join("\n", lines), withPrefix(resolvers));
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        List<String> lines = raw(key);
        if (lines.isEmpty() || (lines.size() == 1 && lines.get(0).isEmpty())) {
            return;
        }
        TagResolver all = withPrefix(resolvers);
        for (String line : lines) {
            sender.sendMessage(text.parse(line, all));
        }
    }

    private TagResolver withPrefix(TagResolver... resolvers) {
        return TagResolver.resolver(prefix, TagResolver.resolver(resolvers));
    }

    public TextService text() {
        return text;
    }
}
