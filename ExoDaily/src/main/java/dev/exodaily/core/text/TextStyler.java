package dev.exodaily.core.text;

import dev.exodaily.core.config.PluginSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns configured MiniMessage templates into components. Adds two conveniences:
 * <ul>
 *     <li>{@code <sym:name>} inserts a symbol from config.yml, or its plain alternative.</li>
 *     <li>{@code <sc>text</sc>} renders small caps, or leaves the text as-is when disabled.</li>
 * </ul>
 * Small caps are applied to the template before placeholders are inserted, so player-provided
 * values are never transformed or parsed as MiniMessage.
 */
public final class TextStyler {

    private static final Pattern SMALL_CAPS = Pattern.compile("<sc>(.*?)</sc>", Pattern.DOTALL);
    private static final String FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PluginSettings.Style style;
    private final TagResolver symbolResolver;

    public TextStyler(PluginSettings.Style style) {
        this.style = style;
        this.symbolResolver = TagResolver.resolver("sym", (arguments, context) -> {
            String name = arguments.popOr("<sym> needs a symbol name, e.g. <sym:star>").value();
            return Tag.selfClosingInserting(Component.text(style.symbol(name)));
        });
    }

    public PluginSettings.Style style() {
        return style;
    }

    /** Parses a template; the result is not italic unless the template asks for it. */
    public Component render(String template, TagResolver... resolvers) {
        String prepared = applySmallCaps(template, style.smallCaps());
        Component component = miniMessage.deserialize(prepared, TagResolver.resolver(TagResolver.resolver(resolvers), symbolResolver));
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(component);
    }

    public String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String applySmallCaps(String template, boolean enabled) {
        Matcher matcher = SMALL_CAPS.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String inner = matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(enabled ? toSmallCaps(inner) : inner));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Converts letters outside MiniMessage tags to small caps; tags and placeholders are left intact. */
    public static String toSmallCaps(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inTag = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(++i));
                continue;
            }
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            }
            if (!inTag) {
                int index = FROM.indexOf(Character.toLowerCase(c));
                if (index >= 0) {
                    out.append(TO.charAt(index));
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Formats a countdown as "5h 24m", "24m" or "<1m" (the last via the given text). */
    public static String formatDuration(Duration duration, String underOneMinute) {
        long totalMinutes = Math.max(0, duration.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return underOneMinute;
    }
}
