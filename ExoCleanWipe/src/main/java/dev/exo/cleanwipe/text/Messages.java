package dev.exo.cleanwipe.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Renders the configured MiniMessage templates. Only the palette colours ({@code <red> <gray> <white> <accent>}),
 * decorations, click/hover and {@code <sym:...>} are available to templates. Every dynamic value is inserted with
 * {@link Placeholder#unparsed}, so names and reasons can never add formatting, click events or fake lines.
 */
public final class Messages {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_-]+$");

    /** name -> {unicode, ascii fallback} */
    private static final Map<String, String[]> SYMBOLS = Map.of(
            "stop", new String[]{"⛔", "[x]"},
            "check", new String[]{"✔", "+"},
            "warn", new String[]{"⚠", "!"},
            "arrow", new String[]{"›", ">"},
            "dot", new String[]{"•", "*"},
            "lock", new String[]{"🔒", "[lock]"});

    private final Map<String, String> templates;
    private final MiniMessage mini;
    private final String prefix;

    public Messages(Map<String, String> templates, Map<String, String> colors, boolean symbols) {
        this.templates = new HashMap<>(templates);
        TagResolver.Builder palette = TagResolver.builder();
        for (String name : List.of("red", "gray", "white", "accent")) {
            String hex = colors.getOrDefault(name, defaultColor(name));
            TextColor color = TextColor.fromHexString(hex);
            if (color == null) {
                throw new IllegalArgumentException("appearance.colors." + name + " is not a #rrggbb colour: " + hex);
            }
            palette.tag(name, Tag.styling(color));
        }
        TagResolver sym = TagResolver.resolver("sym", (args, ctx) -> {
            String name = args.hasNext() ? args.pop().value() : "dot";
            String[] pair = SYMBOLS.getOrDefault(name, new String[]{"", ""});
            return Tag.inserting(Component.text(symbols ? pair[0] : pair[1]));
        });
        this.mini = MiniMessage.builder()
                .tags(TagResolver.builder()
                        .resolver(StandardTags.decorations())
                        .resolver(StandardTags.clickEvent())
                        .resolver(StandardTags.hoverEvent())
                        .resolver(StandardTags.newline())
                        .resolver(StandardTags.reset())
                        .resolver(palette.build())
                        .resolver(sym)
                        .build())
                .build();
        this.prefix = this.templates.getOrDefault("prefix", "");
    }

    private static String defaultColor(String name) {
        return switch (name) {
            case "red" -> "#ff5555";
            case "gray" -> "#aaaaaa";
            case "white" -> "#ffffff";
            default -> "#55cfff";
        };
    }

    /** Builds an unparsed (literal) placeholder. */
    public static TagResolver text(String name, Object value) {
        if (!KEY.matcher(name).matches()) {
            throw new IllegalArgumentException("bad placeholder name " + name);
        }
        return Placeholder.unparsed(name, value == null ? "" : String.valueOf(value));
    }

    public static TagResolver[] values(Object... nameValue) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            list.add(text((String) nameValue[i], nameValue[i + 1]));
        }
        return list.toArray(new TagResolver[0]);
    }

    public boolean has(String key) {
        return templates.containsKey(key);
    }

    /** Renders a template without the chat prefix (ban screens, kick screens). */
    public Component screen(String key, TagResolver... values) {
        return mini.deserialize(templates.getOrDefault(key, key), values);
    }

    /** Renders a template with the chat prefix. */
    public Component chat(String key, TagResolver... values) {
        return mini.deserialize(prefix + templates.getOrDefault(key, key), values);
    }

    /** Renders a raw (internal, trusted) template line, used for reports built in code. */
    public Component line(String template, TagResolver... values) {
        return mini.deserialize(template, values);
    }

    public void send(Audience to, String key, TagResolver... values) {
        to.sendMessage(chat(key, values));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
