package dev.exoquests.paper.text;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.Messages;
import dev.exoquests.core.config.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Renders MiniMessage templates with the ExoQuests theme. Values inserted into templates go through
 * {@link #p(String, Object)}, which escapes them, so player names can never inject formatting or tags.
 */
public final class TextService {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private volatile State state;

    private record State(Messages messages, Settings settings, TagResolver theme, Component prefix) {
    }

    public void update(ConfigBundle bundle) {
        Settings settings = bundle.settings();
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, String> color : settings.style().colors().entrySet()) {
            resolvers.add(Placeholder.styling(color.getKey(), TextColor.fromHexString(color.getValue())));
        }
        Settings.Style style = settings.style();
        resolvers.add(TagResolver.resolver("icon", (args, ctx) -> {
            String name = args.hasNext() ? args.pop().lowerValue() : "";
            return Tag.inserting(Component.text(style.icon(name)));
        }));
        TagResolver theme = TagResolver.resolver(resolvers);
        Component prefix = mini.deserialize(bundle.messages().get("prefix"), theme);
        this.state = new State(bundle.messages(), settings, theme, prefix);
    }

    /** Escaped text placeholder: {@code <name>} is replaced by the literal value. */
    public static TagResolver p(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** Escaped number placeholder with thousands separators. */
    public static TagResolver n(String name, long value) {
        return Placeholder.unparsed(name, String.format(Locale.ROOT, "%,d", value));
    }

    public Component parse(String template, TagResolver... extra) {
        State s = state;
        return mini.deserialize(template, TagResolver.resolver(s.theme(),
                Placeholder.component("prefix", s.prefix()), TagResolver.resolver(extra)));
    }

    /** Parses text for item names and lore, which Minecraft would otherwise render italic. */
    public Component item(String template, TagResolver... extra) {
        return parse(template, extra).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> itemLines(List<String> templates, TagResolver... extra) {
        List<Component> out = new ArrayList<>(templates.size());
        for (String t : templates) {
            out.add(item(t, extra));
        }
        return out;
    }

    public Component message(String key, TagResolver... extra) {
        return parse(state.messages().get(key), extra);
    }

    public void send(CommandSender to, String key, TagResolver... extra) {
        to.sendMessage(message(key, extra));
    }

    public void sendList(CommandSender to, String key, TagResolver... extra) {
        for (String line : state.messages().list(key)) {
            to.sendMessage(parse(line, extra));
        }
    }

    public void actionBar(Player player, String key, TagResolver... extra) {
        player.sendActionBar(message(key, extra));
    }

    public void play(Player player, String soundKey) {
        Settings.SoundSpec spec = state.settings().sounds().get(soundKey);
        if (spec == null || !spec.enabled()) {
            return;
        }
        player.playSound(Sound.sound(Key.key(spec.key()), Sound.Source.MASTER, spec.volume(), spec.pitch()));
    }

    /** Progress bar such as {@code ██████░░░░} using accent and muted colors. */
    public Component bar(int progress, int target) {
        Settings.Style style = state.settings().style();
        Settings.ProgressBar bar = style.bar();
        int length = bar.length();
        int filled = target <= 0 ? length : (int) Math.min(length, (long) progress * length / target);
        if (progress >= target) {
            filled = length;
        }
        String full = style.unicode() ? bar.filled() : bar.fallbackFilled();
        String empty = style.unicode() ? bar.empty() : bar.fallbackEmpty();
        TextColor accent = TextColor.fromHexString(style.colors().get("accent"));
        TextColor muted = TextColor.fromHexString(style.colors().get("muted"));
        return Component.text()
                .append(Component.text(full.repeat(filled), accent))
                .append(Component.text(empty.repeat(length - filled), muted))
                .build();
    }

    public String icon(String name) {
        return state.settings().style().icon(name);
    }
}
