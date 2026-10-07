package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.cooldown.CooldownCalculator;
import dev.exo.dailyspinner.storage.PlayerData;
import dev.exo.dailyspinner.util.TimeFormat;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Shared MiniMessage placeholders for player state. */
public final class Placeholders {

    private Placeholders() {
    }

    public static long remaining(ConfigBundle bundle, PlayerData data) {
        if (data == null) {
            return 0;
        }
        return CooldownCalculator.remaining(data.lastDailySpin(), bundle.settings().cooldownMillis(), System.currentTimeMillis());
    }

    public static TagResolver player(ConfigBundle bundle, PlayerData data) {
        long remaining = remaining(bundle, data);
        return TagResolver.resolver(
                Placeholder.unparsed("time", TimeFormat.compact(remaining)),
                Placeholder.unparsed("cooldown", TimeFormat.compact(bundle.settings().cooldownMillis())),
                Placeholder.unparsed("bonus", String.valueOf(data == null ? 0 : data.bonusSpins())),
                Placeholder.unparsed("pending", String.valueOf(data == null ? 0 : data.pendingItems())),
                Placeholder.unparsed("rewards", String.valueOf(bundle.rewards().rewards().size())),
                Placeholder.parsed("daily_status", remaining == 0
                        ? "<success><sym:check> " + bundle.messages().raw("status-ready").get(0)
                        : "<muted><sym:hourglass> " + TimeFormat.compact(remaining)));
    }
}
