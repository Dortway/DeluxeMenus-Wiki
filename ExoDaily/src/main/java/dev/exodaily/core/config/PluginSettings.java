package dev.exodaily.core.config;

import java.time.ZoneId;
import java.util.Map;

/** Validated config.yml. */
public record PluginSettings(
        ZoneId timezone,
        int cycleLength,
        Storage storage,
        long clickCooldownMs,
        long openCooldownMs,
        int countdownUpdateTicks,
        Style style,
        boolean auditFile,
        boolean notifyAdminsOnJoin
) {

    /** Storage settings; changes take effect after a restart. */
    public record Storage(String type, String file, int busyTimeoutMs, int queueCapacity) {
    }

    /** Symbol and small-caps rendering, with plain-text alternatives. */
    public record Style(boolean smallCaps, boolean symbols, Map<String, Symbol> symbolMap) {

        public Style {
            symbolMap = Map.copyOf(symbolMap);
        }

        public String symbol(String name) {
            Symbol symbol = symbolMap.get(name);
            if (symbol == null) {
                return "";
            }
            return symbols ? symbol.fancy() : symbol.plain();
        }
    }

    public record Symbol(String fancy, String plain) {
    }
}
