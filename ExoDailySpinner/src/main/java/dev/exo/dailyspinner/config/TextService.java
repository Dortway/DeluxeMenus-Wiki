package dev.exo.dailyspinner.config;

import dev.exo.dailyspinner.util.SmallCaps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniMessage rendering with the configured theme: colour tags ({@code <primary>}, {@code <accent>}...),
 * symbol tags ({@code <sym:star>}) and small-caps regions ({@code <sc>...</sc>}).
 */
public final class TextService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TagResolver theme;
    private final Map<String, String> symbols;
    private final boolean smallCaps;

    public TextService(Map<String, TextColor> colors, Map<String, String> symbols, boolean smallCaps) {
        this.symbols = Map.copyOf(symbols);
        this.smallCaps = smallCaps;
        TagResolver.Builder builder = TagResolver.builder();
        colors.forEach((name, color) -> builder.tag(name, Tag.styling(color)));
        builder.resolver(TagResolver.resolver("sym", (args, context) -> {
            String name = args.popOr("sym tag requires a symbol name, e.g. <sym:star>").value();
            return Tag.selfClosingInserting(Component.text(this.symbols.getOrDefault(name, "")));
        }));
        this.theme = builder.build();
    }

    public static MiniMessage mini() {
        return MINI;
    }

    public TagResolver themeResolver() {
        return theme;
    }

    public String symbol(String name) {
        return symbols.getOrDefault(name, "");
    }

    /** Parses chat text. */
    public Component parse(String raw, TagResolver... extra) {
        return MINI.deserialize(SmallCaps.process(raw, smallCaps), TagResolver.resolver(theme, TagResolver.resolver(extra)));
    }

    /** Parses item names/lore: no default italics. */
    public Component item(String raw, TagResolver... extra) {
        return parse(raw, extra).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lore(List<String> lines, TagResolver... extra) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(line, extra));
        }
        return out;
    }

    /** Validates MiniMessage syntax strictly (used during config validation). */
    public String validate(String raw) {
        try {
            MiniMessage.builder().strict(false).build().deserialize(SmallCaps.process(raw, smallCaps), theme);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
