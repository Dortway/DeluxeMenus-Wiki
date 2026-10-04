package com.exoblacksmith.util;

import com.exoblacksmith.config.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/**
 * MiniMessage rendering with {@code {placeholder}} substitution.
 * <ul>
 *   <li>{@link #render} escapes placeholder values, so player names and other runtime values can never
 *       inject formatting.</li>
 *   <li>{@link #trusted} inserts values verbatim; only used for values that come from config files.</li>
 *   <li>{@code {sym:name}} expands to a configured symbol (unicode or plain-text fallback).</li>
 * </ul>
 */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_:-]+)}");

    private Text() {
    }

    private static String substitute(Registry registry, String template, Map<String, String> values, boolean escape) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if (key.startsWith("sym:")) {
                replacement = registry.settings.symbol(key.substring(4));
            } else if (values.containsKey(key)) {
                String v = values.get(key);
                replacement = v == null ? "" : (escape ? MM.escapeTags(v) : v);
            } else {
                replacement = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return registry.settings.smallCaps ? SmallCaps.convert(out) : out;
    }

    public static Component render(Registry registry, String template, Map<String, String> values) {
        return MM.deserialize(substitute(registry, template, values, true));
    }

    public static Component trusted(Registry registry, String template, Map<String, String> values) {
        return MM.deserialize(substitute(registry, template, values, false));
    }

    /** Item names and lore (trusted config values): italics off unless the template sets them. */
    public static Component item(Registry registry, String template, Map<String, String> values) {
        return trusted(registry, template, values).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lines(Registry registry, List<String> templates, Map<String, String> values) {
        List<Component> out = new ArrayList<>();
        for (String template : templates) {
            for (String line : template.split("\n", -1)) {
                out.add(item(registry, line, values));
            }
        }
        return out;
    }

    public static Component message(Registry registry, String key, Map<String, String> values) {
        String template = registry.messages.raw(key).replace("{prefix}", registry.messages.raw("prefix"));
        return render(registry, template, values);
    }

    public static void send(CommandSender to, Registry registry, String key, Map<String, String> values) {
        if (registry.messages.raw(key).isBlank()) {
            return;
        }
        to.sendMessage(message(registry, key, values));
    }

    public static void send(CommandSender to, Registry registry, String key) {
        send(to, registry, key, Map.of());
    }

    public static void actionBar(org.bukkit.entity.Player to, Registry registry, String key, Map<String, String> values) {
        if (registry.messages.raw(key).isBlank()) {
            return;
        }
        to.sendActionBar(render(registry, registry.messages.raw(key).replace("{prefix}", ""), values));
    }

    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Plain text of a config name template (tags stripped), for chat placeholders. */
    public static String plainName(String template) {
        return MM.stripTags(template);
    }
}
