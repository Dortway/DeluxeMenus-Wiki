package dev.exoquests.core.config;

import dev.exoquests.core.time.ResetSchedule;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parses and validates {@code config.yml}. */
public final class SettingsLoader {

    public static final List<String> COLOR_KEYS =
            List.of("primary", "accent", "text", "muted", "success", "warning", "error");
    public static final List<String> SOUND_KEYS =
            List.of("click", "open", "page", "purchase", "denied", "quest-complete", "reset");
    public static final List<String> ICON_KEYS =
            List.of("check", "cross", "points", "clock", "arrow", "dot", "lock", "shop", "info", "star");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern SOUND_KEY = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private SettingsLoader() {
    }

    public static Settings load(ConfigNode root, PlatformValidator platform) {
        ConfigErrors errors = root.errors();

        ConfigNode reset = root.section("reset");
        ResetSchedule schedule = null;
        try {
            schedule = ResetSchedule.parse(reset.string("time", "00:00"), reset.string("timezone", "Europe/London"));
        } catch (IllegalArgumentException e) {
            errors.add(reset.path(), e.getMessage());
        }

        ConfigNode tracking = root.section("tracking");
        int flush = (int) tracking.integer("progress-flush-seconds", 5, 1, 300);
        Set<String> modes = new HashSet<>();
        List<String> modeList = tracking.has("allowed-gamemodes")
                ? tracking.stringList("allowed-gamemodes") : List.of("SURVIVAL", "ADVENTURE");
        for (String m : modeList) {
            String mode = m.toUpperCase(Locale.ROOT);
            if (!platform.isGameMode(mode)) {
                errors.add(tracking.child("allowed-gamemodes"), "unknown game mode '" + m + "'");
            } else if (mode.equals("CREATIVE") || mode.equals("SPECTATOR")) {
                errors.add(tracking.child("allowed-gamemodes"), mode + " can never earn progress");
            } else {
                modes.add(mode);
            }
        }
        ConfigNode worlds = tracking.section("worlds");
        String worldMode = worlds.string("mode", "blacklist").toLowerCase(Locale.ROOT);
        if (!worldMode.equals("blacklist") && !worldMode.equals("whitelist")) {
            errors.add(worlds.child("mode"), "must be 'blacklist' or 'whitelist'");
        }
        Settings.WorldFilter worldFilter = new Settings.WorldFilter(worldMode.equals("whitelist"),
                Set.copyOf(worlds.stringList("list")));

        ConfigNode kills = tracking.section("kills");
        Set<String> reasons = new HashSet<>();
        for (String r : kills.stringList("excluded-spawn-reasons")) {
            String reason = r.toUpperCase(Locale.ROOT);
            if (!platform.isSpawnReason(reason)) {
                errors.add(kills.child("excluded-spawn-reasons"), "unknown spawn reason '" + r + "'");
            } else {
                reasons.add(reason);
            }
        }
        Settings.KillPolicy killPolicy = new Settings.KillPolicy(
                kills.bool("require-direct-damage", true),
                kills.bool("allow-projectiles", true),
                kills.bool("allow-tamed-pets", false),
                Set.copyOf(reasons));

        ConfigNode trees = tracking.section("trees");
        Settings.TreeCredit credit = Settings.TreeCredit.PLANTER;
        String creditName = trees.string("credit", "PLANTER").toUpperCase(Locale.ROOT);
        try {
            credit = Settings.TreeCredit.valueOf(creditName);
        } catch (IllegalArgumentException e) {
            errors.add(trees.child("credit"), "must be PLANTER or BONEMEALER_IF_PRESENT");
        }
        Settings.TreePolicy treePolicy = new Settings.TreePolicy(credit, trees.bool("credit-offline-planter", true));

        ConfigNode stacked = tracking.section("stacked-plants");
        Settings.StackedPlantPolicy stackedPolicy = new Settings.StackedPlantPolicy(
                stacked.bool("count-support-breaks", true),
                (int) stacked.integer("max-scan-height", 32, 1, 384));

        ConfigNode points = root.section("points");
        long maxBalance = points.integer("max-balance", 1_000_000_000L, 1_000, 9_000_000_000_000_000L);

        ConfigNode shop = root.section("shop");
        String namePattern = shop.string("command-player-name-pattern", "^[A-Za-z0-9_]{1,16}$");
        Pattern compiled = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
        try {
            compiled = Pattern.compile(namePattern);
            if (compiled.matcher(" ").find() || compiled.matcher(";").matches()) {
                errors.add(shop.child("command-player-name-pattern"), "must not allow spaces or ';'");
            }
        } catch (PatternSyntaxException e) {
            errors.add(shop.child("command-player-name-pattern"), "invalid regular expression");
        }
        Settings.ShopSettings shopSettings = new Settings.ShopSettings(
                (int) shop.integer("confirm-timeout-seconds", 30, 5, 600),
                (int) shop.integer("click-cooldown-ms", 250, 0, 5_000),
                compiled);

        ConfigNode storage = root.section("storage");
        String file = storage.string("file", "exoquests.db");
        if (file.isBlank() || file.contains("..") || file.startsWith("/") || file.contains("\\")) {
            errors.add(storage.child("file"), "must be a plain file name inside the plugin folder");
        }
        String sync = storage.string("synchronous", "FULL").toUpperCase(Locale.ROOT);
        if (!Set.of("NORMAL", "FULL", "EXTRA").contains(sync)) {
            errors.add(storage.child("synchronous"), "must be NORMAL, FULL or EXTRA");
        }
        Settings.StorageSettings storageSettings = new Settings.StorageSettings(file, sync,
                (int) storage.integer("busy-timeout-ms", 5_000, 100, 60_000),
                (int) storage.integer("assignment-retention-days", 30, 1, 3_650));

        Settings.Style style = loadStyle(root.section("style"), errors);
        Map<String, Settings.SoundSpec> sounds = loadSounds(root.section("sounds"), errors);

        if (errors.hasProblems()) {
            return null;
        }
        return new Settings(schedule, flush, Set.copyOf(modes), worldFilter, killPolicy, treePolicy,
                stackedPolicy, maxBalance, shopSettings, storageSettings, style, sounds);
    }

    private static Settings.Style loadStyle(ConfigNode style, ConfigErrors errors) {
        Map<String, String> colors = new LinkedHashMap<>();
        ConfigNode c = style.section("colors");
        Map<String, String> defaults = Map.of("primary", "#5CE1E6", "accent", "#7DFFB3", "text", "#FFFFFF",
                "muted", "#8A9BA8", "success", "#7DFFB3", "warning", "#FFD166", "error", "#FF6B6B");
        for (String key : COLOR_KEYS) {
            String v = c.string(key, defaults.get(key));
            if (!HEX.matcher(v).matches()) {
                errors.add(c.child(key), "must be a hex color like #5CE1E6");
                v = defaults.get(key);
            }
            colors.put(key, v);
        }
        ConfigNode bar = style.section("progress-bar");
        Settings.ProgressBar progressBar = new Settings.ProgressBar(
                (int) bar.integer("length", 10, 4, 40),
                nonEmpty(bar, "filled", "█"), nonEmpty(bar, "empty", "░"),
                nonEmpty(bar, "fallback-filled", "|"), nonEmpty(bar, "fallback-empty", "."));
        Map<String, String> icons = new LinkedHashMap<>();
        Map<String, String> fallback = new LinkedHashMap<>();
        ConfigNode iconNode = style.section("icons");
        ConfigNode fallbackNode = style.section("fallback-icons");
        for (String key : ICON_KEYS) {
            icons.put(key, iconNode.string(key, ""));
            fallback.put(key, fallbackNode.string(key, ""));
        }
        return new Settings.Style(style.bool("unicode", true), Map.copyOf(colors), progressBar,
                Map.copyOf(icons), Map.copyOf(fallback));
    }

    private static String nonEmpty(ConfigNode node, String key, String fallback) {
        String v = node.string(key, fallback);
        if (v.isEmpty()) {
            node.errors().add(node.child(key), "must not be empty");
            return fallback;
        }
        return v;
    }

    private static Map<String, Settings.SoundSpec> loadSounds(ConfigNode sounds, ConfigErrors errors) {
        Map<String, Settings.SoundSpec> out = new LinkedHashMap<>();
        for (String key : SOUND_KEYS) {
            ConfigNode s = sounds.section(key);
            String soundKey = s.string("sound", "minecraft:ui.button.click").toLowerCase(Locale.ROOT);
            if (!SOUND_KEY.matcher(soundKey).matches()) {
                errors.add(s.child("sound"), "invalid sound key '" + soundKey + "'");
            }
            out.put(key, new Settings.SoundSpec(s.bool("enabled", true), soundKey,
                    (float) s.decimal("volume", 0.6, 0.0, 10.0), (float) s.decimal("pitch", 1.0, 0.5, 2.0)));
        }
        return Map.copyOf(out);
    }
}
