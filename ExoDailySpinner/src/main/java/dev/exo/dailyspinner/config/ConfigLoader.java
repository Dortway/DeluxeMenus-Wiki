package dev.exo.dailyspinner.config;

import dev.exo.dailyspinner.reward.CommandTemplate;
import dev.exo.dailyspinner.reward.Rarity;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.reward.RewardRegistry;
import dev.exo.dailyspinner.reward.RewardType;
import dev.exo.dailyspinner.reward.WeightedPicker;
import dev.exo.dailyspinner.storage.ConsumeOrder;
import dev.exo.dailyspinner.util.DurationParser;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds and validates a complete {@link ConfigBundle} from file contents. Nothing is applied
 * unless every file validates, so a bad reload never replaces the working configuration.
 * Must run on the server thread (it creates and serializes ItemStacks).
 */
public final class ConfigLoader {

    public static final String CONFIG = "config.yml";
    public static final String MESSAGES = "messages.yml";
    public static final String MENUS = "menus.yml";
    public static final String REWARDS = "rewards.yml";

    public static final Pattern REWARD_ID = Pattern.compile("[a-z0-9_\\-]{1,32}");
    public static final int MAX_ITEM_AMOUNT = 2304;
    public static final int MAX_COMMANDS = 20;

    public static final List<String> MAIN_SPINNER_STATES = List.of("ready", "resume", "cooldown", "running", "unavailable");
    public static final List<String> SPIN_BUTTON_STATES = List.of("ready", "resume", "cooldown", "running", "unavailable", "reserving");
    public static final List<String> SOUND_NAMES = List.of("menu-open", "click", "denied", "spin-start", "tick", "land", "win",
            "win-rare", "claim");

    public record Result(ConfigBundle bundle, ConfigErrors errors) {
        public boolean ok() {
            return bundle != null;
        }
    }

    private ConfigLoader() {
    }

    /**
     * @param files    current file contents keyed by file name
     * @param defaults bundled default contents (config, messages, menus) used for missing keys
     */
    public static Result load(Map<String, String> files, Map<String, String> defaults) {
        ConfigErrors errors = new ConfigErrors();
        YamlConfiguration config = parse(files, defaults, CONFIG, errors);
        YamlConfiguration messagesYaml = parse(files, defaults, MESSAGES, errors);
        YamlConfiguration menusYaml = parse(files, defaults, MENUS, errors);
        YamlConfiguration rewardsYaml = parse(files, Map.of(), REWARDS, errors);
        if (errors.hasErrors()) {
            return new Result(null, errors);
        }
        TextService text = loadText(config, errors);
        Settings settings = loadSettings(config, errors);
        Messages messages = loadMessages(messagesYaml, text);
        MenuSettings menus = loadMenus(menusYaml, text, errors);
        RewardRegistry registry = loadRewards(rewardsYaml, text, settings, errors);
        if (errors.hasErrors()) {
            return new Result(null, errors);
        }
        return new Result(new ConfigBundle(settings, text, messages, menus, registry, files.getOrDefault(REWARDS, "")), errors);
    }

    /** Re-validates only the rewards (used by in-game reward editing). */
    public static RewardRegistry loadRewardsOnly(String rewardsText, ConfigBundle current, ConfigErrors errors) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(rewardsText);
        } catch (InvalidConfigurationException e) {
            errors.error(REWARDS, "<file>", "YAML syntax error: " + e.getMessage());
            return null;
        }
        RewardRegistry registry = loadRewards(yaml, current.text(), current.settings(), errors);
        return errors.hasErrors() ? null : registry;
    }

    // ------------------------------------------------------------------ files

    private static YamlConfiguration parse(Map<String, String> files, Map<String, String> defaults, String name,
                                           ConfigErrors errors) {
        YamlConfiguration yaml = new YamlConfiguration();
        String content = files.get(name);
        if (content == null) {
            content = defaults.getOrDefault(name, "");
        }
        try {
            yaml.loadFromString(content);
        } catch (InvalidConfigurationException e) {
            errors.error(name, "<file>", "YAML syntax error: " + e.getMessage());
        }
        String def = defaults.get(name);
        if (def != null) {
            YamlConfiguration defaultsYaml = new YamlConfiguration();
            try {
                defaultsYaml.loadFromString(def);
                yaml.setDefaults(defaultsYaml);
            } catch (InvalidConfigurationException e) {
                errors.error(name, "<bundled defaults>", e.getMessage());
            }
        }
        return yaml;
    }

    // ------------------------------------------------------------------ config.yml

    private static TextService loadText(YamlConfiguration config, ConfigErrors errors) {
        Map<String, TextColor> colors = new LinkedHashMap<>();
        ConfigurationSection colorSection = config.getConfigurationSection("theme.colors");
        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                String hex = colorSection.getString(key, "");
                TextColor color = TextColor.fromHexString(hex.trim());
                if (color == null) {
                    errors.error(CONFIG, "theme.colors." + key, "'" + hex + "' is not a hex colour like #22D3EE");
                } else if (!key.matches("[a-z0-9_\\-]+")) {
                    errors.error(CONFIG, "theme.colors." + key, "colour names must be lowercase letters, digits, - or _");
                } else {
                    colors.put(key, color);
                }
            }
        }
        boolean unicode = config.getBoolean("symbols.use-unicode", true);
        Map<String, String> symbols = new LinkedHashMap<>();
        ConfigurationSection symbolSection = config.getConfigurationSection("symbols.list");
        if (symbolSection != null) {
            for (String key : symbolSection.getKeys(false)) {
                String uni = symbolSection.getString(key + ".unicode", "");
                String fallback = symbolSection.getString(key + ".fallback", "");
                String value = unicode ? uni : fallback;
                if (value.length() > 16) {
                    errors.error(CONFIG, "symbols.list." + key, "symbol text must be at most 16 characters");
                    value = value.substring(0, 16);
                }
                symbols.put(key, value);
            }
        }
        return new TextService(colors, symbols, config.getBoolean("theme.small-caps", true));
    }

    private static Settings loadSettings(YamlConfiguration c, ConfigErrors e) {
        long cooldown;
        try {
            cooldown = DurationParser.parseMillis(c.getString("spins.daily-cooldown", "24h"));
            if (cooldown < 1_000L || cooldown > 365L * 86_400_000L) {
                e.error(CONFIG, "spins.daily-cooldown", "must be between 1s and 365d");
            }
        } catch (IllegalArgumentException | ArithmeticException ex) {
            e.error(CONFIG, "spins.daily-cooldown", ex.getMessage());
            cooldown = 86_400_000L;
        }
        ConsumeOrder order;
        try {
            order = ConsumeOrder.valueOf(c.getString("spins.consume-order", "DAILY_FIRST").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            e.error(CONFIG, "spins.consume-order", "must be DAILY_FIRST or BONUS_FIRST");
            order = ConsumeOrder.DAILY_FIRST;
        }
        int maxBonus = intRange(c, "spins.max-bonus-spins", 10_000, 1, 1_000_000, e);
        long click = intRange(c, "protection.click-cooldown-ms", 150, 0, 5_000, e);
        long command = intRange(c, "protection.command-cooldown-ms", 600, 0, 10_000, e);
        String dbFile = c.getString("database.file", "data.db");
        if (dbFile == null || !dbFile.matches("[A-Za-z0-9_.\\-]{1,64}") || dbFile.contains("..")) {
            e.error(CONFIG, "database.file", "must be a simple file name like data.db");
            dbFile = "data.db";
        }
        int busy = intRange(c, "database.busy-timeout-ms", 5000, 100, 60_000, e);
        int steps = intRange(c, "animation.steps", 46, 5, 200, e);
        int minDelay = intRange(c, "animation.min-delay-ticks", 1, 1, 20, e);
        int maxDelay = intRange(c, "animation.max-delay-ticks", 9, 1, 40, e);
        if (maxDelay < minDelay) {
            e.error(CONFIG, "animation.max-delay-ticks", "must be >= min-delay-ticks");
        }
        double easing = c.getDouble("animation.easing", 3.0);
        if (!Double.isFinite(easing) || easing < 0.2 || easing > 10) {
            e.error(CONFIG, "animation.easing", "must be between 0.2 and 10");
        }
        if ((long) steps * maxDelay > 1200) {
            e.error(CONFIG, "animation", "steps x max-delay-ticks is too long (max 1200 ticks)");
        }
        Particle particle = null;
        if (c.getBoolean("effects.particles.enabled", true)) {
            String name = c.getString("effects.particles.particle", "TOTEM_OF_UNDYING");
            try {
                particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
                if (particle.getDataType() != Void.class) {
                    e.error(CONFIG, "effects.particles.particle", "particle '" + name + "' needs extra data; pick a simple particle");
                    particle = null;
                }
            } catch (IllegalArgumentException ex) {
                e.error(CONFIG, "effects.particles.particle", "unknown particle '" + name + "'");
            }
        }
        int count = intRange(c, "effects.particles.count", 40, 1, 200, e);
        double spread = c.getDouble("effects.particles.spread", 0.6);
        double speed = c.getDouble("effects.particles.speed", 0.3);
        if (!Double.isFinite(spread) || spread < 0 || spread > 5) {
            e.error(CONFIG, "effects.particles.spread", "must be between 0 and 5");
        }
        if (!Double.isFinite(speed) || speed < 0 || speed > 2) {
            e.error(CONFIG, "effects.particles.speed", "must be between 0 and 2");
        }
        double defaultWeight = c.getDouble("admin.default-weight", 10);
        try {
            WeightedPicker.validateWeight(defaultWeight);
        } catch (IllegalArgumentException ex) {
            e.error(CONFIG, "admin.default-weight", ex.getMessage());
            defaultWeight = 10;
        }
        Material cmdMaterial = ItemTemplate.parseMaterial(c.getString("admin.command-display-material", "NAME_TAG"),
                CONFIG, "admin.command-display-material", e);
        Map<String, SoundSpec> sounds = new LinkedHashMap<>();
        if (c.getBoolean("sounds.enabled", true)) {
            for (String name : SOUND_NAMES) {
                String path = "sounds." + name;
                ConfigurationSection s = c.getConfigurationSection(path);
                if (s == null || !s.getBoolean("enabled", true)) {
                    continue;
                }
                String key = s.getString("sound", "");
                if (key == null || key.isBlank()) {
                    continue;
                }
                float volume = (float) s.getDouble("volume", 1.0);
                float pitch = (float) s.getDouble("pitch", 1.0);
                if (!Float.isFinite(volume) || volume < 0 || volume > 10) {
                    e.error(CONFIG, path + ".volume", "must be between 0 and 10");
                    continue;
                }
                if (!Float.isFinite(pitch) || pitch < 0.5f || pitch > 2f) {
                    e.error(CONFIG, path + ".pitch", "must be between 0.5 and 2.0");
                    continue;
                }
                try {
                    sounds.put(name, SoundSpec.of(key.trim().toLowerCase(Locale.ROOT), volume, pitch));
                } catch (InvalidKeyException ex) {
                    e.error(CONFIG, path + ".sound", "'" + key + "' is not a valid sound key (e.g. minecraft:entity.player.levelup)");
                }
            }
        }
        return new Settings(
                c.getBoolean("debug", false),
                cooldown, order, maxBonus, click, command, dbFile, busy,
                steps, minDelay, maxDelay, easing,
                c.getBoolean("effects.border-cycle", true),
                c.getBoolean("effects.title", true),
                particle, count, spread, speed,
                c.getBoolean("announcements.enabled", true),
                intRange(c, "spins.resume-delay-ticks", 40, 1, 1200, e),
                defaultWeight,
                c.getString("admin.default-rarity", "common"),
                cmdMaterial == null ? Material.NAME_TAG : cmdMaterial,
                Map.copyOf(sounds));
    }

    private static int intRange(ConfigurationSection c, String path, int def, int min, int max, ConfigErrors e) {
        Object raw = c.get(path, def);
        if (!(raw instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            e.error(CONFIG, path, "must be a whole number");
            return def;
        }
        long value = number.longValue();
        if (value < min || value > max) {
            e.error(CONFIG, path, "must be between " + min + " and " + max);
            return def;
        }
        return (int) value;
    }

    // ------------------------------------------------------------------ messages.yml

    private static Messages loadMessages(YamlConfiguration yaml, TextService text) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        if (yaml.getDefaults() != null) {
            keys.addAll(yaml.getDefaults().getKeys(true));
        }
        keys.addAll(yaml.getKeys(true));
        for (String key : keys) {
            if (yaml.isConfigurationSection(key)) {
                continue;
            }
            if (yaml.isList(key)) {
                entries.put(key, List.copyOf(yaml.getStringList(key)));
            } else {
                String value = yaml.getString(key);
                if (value != null) {
                    entries.put(key, List.of(value));
                }
            }
        }
        return new Messages(entries, text);
    }

    // ------------------------------------------------------------------ menus.yml

    private static MenuSettings loadMenus(YamlConfiguration y, TextService text, ConfigErrors e) {
        // ---- main menu (9 slots)
        Set<Integer> used = new HashSet<>();
        String mainTitle = y.getString("main-menu.title", "Daily Spinner");
        ItemTemplate mainFiller = tpl(y, "main-menu.filler", text, e);
        List<MenuSettings.Decoration> mainDecor = decorations(y, "main-menu.decorations", 9, text, e);
        int spinnerSlot = slot(y, "main-menu.spinner-button.slot", 9, used, e);
        Map<String, ItemTemplate> spinnerStates = states(y, "main-menu.spinner-button", MAIN_SPINNER_STATES, text, e);
        int previewSlot = slot(y, "main-menu.preview-button.slot", 9, used, e);
        ItemTemplate previewButton = tpl(y, "main-menu.preview-button", text, e);
        int pendingSlot = slot(y, "main-menu.pending-button.slot", 9, used, e);
        ItemTemplate pendingAvailable = tpl(y, "main-menu.pending-button.has-pending", text, e);
        ItemTemplate pendingEmpty = tpl(y, "main-menu.pending-button.empty", text, e);
        MenuSettings.Main main = new MenuSettings.Main(mainTitle, mainFiller, mainDecor, spinnerSlot, spinnerStates,
                previewSlot, previewButton, pendingSlot, pendingAvailable, pendingEmpty);

        // ---- spinner (27 slots)
        String sp = "spinner-menu.";
        Set<Integer> spUsed = new HashSet<>();
        List<Integer> reel = slotList(y, sp + "reel-slots", 27, e);
        if (reel.size() < 3) {
            e.error(MENUS, sp + "reel-slots", "at least 3 reel slots are required");
        }
        spUsed.addAll(reel);
        int winning = y.getInt(sp + "winning-slot", 13);
        if (!reel.contains(winning)) {
            e.error(MENUS, sp + "winning-slot", "must be one of the reel-slots");
        }
        int pointerTop = slot(y, sp + "pointer-top.slot", 27, spUsed, e);
        int pointerBottom = slot(y, sp + "pointer-bottom.slot", 27, spUsed, e);
        int spinSlot = y.getInt(sp + "spin-button.slot", 22);
        if (spinSlot < 0 || spinSlot >= 27 || (reel.contains(spinSlot))) {
            e.error(MENUS, sp + "spin-button.slot", "must be 0-26 and outside the reel");
        }
        if (spinSlot != pointerBottom && spinSlot != pointerTop && !spUsed.add(spinSlot)) {
            e.error(MENUS, sp + "spin-button.slot", "slot already used");
        }
        int back = slot(y, sp + "back-button.slot", 27, spUsed, e);
        int info = slot(y, sp + "info.slot", 27, spUsed, e);
        List<Integer> border = slotList(y, sp + "border-slots", 27, e);
        for (int b : border) {
            if (spUsed.contains(b) || b == spinSlot) {
                e.error(MENUS, sp + "border-slots", "slot " + b + " overlaps another spinner element");
            }
        }
        MenuSettings.Spinner spinner = new MenuSettings.Spinner(
                y.getString(sp + "title", "Daily Spinner"),
                tpl(y, sp + "filler", text, e), tpl(y, sp + "border", text, e), tpl(y, sp + "border-alt", text, e),
                border, reel, winning,
                pointerTop, tpl(y, sp + "pointer-top", text, e),
                pointerBottom, tpl(y, sp + "pointer-bottom", text, e),
                spinSlot, states(y, sp + "spin-button", SPIN_BUTTON_STATES, text, e),
                back, tpl(y, sp + "back-button", text, e),
                info, tpl(y, sp + "info", text, e),
                y.getStringList(sp + "reel-lore"), y.getStringList(sp + "winner-lore"));

        MenuSettings.Paged preview = paged(y, "preview-menu", text, e, List.of());
        MenuSettings.Paged admin = paged(y, "admin-menu", text, e, List.of("add-hand", "reload"));
        return new MenuSettings(main, spinner, preview, admin);
    }

    private static MenuSettings.Paged paged(YamlConfiguration y, String base, TextService text, ConfigErrors e,
                                            List<String> extras) {
        int rows = y.getInt(base + ".rows", 6);
        if (rows < 2 || rows > 6) {
            e.error(MENUS, base + ".rows", "must be between 2 and 6");
            rows = 6;
        }
        int size = rows * 9;
        Set<Integer> used = new HashSet<>();
        List<Integer> content = slotList(y, base + ".content-slots", size, e);
        if (content.isEmpty()) {
            e.error(MENUS, base + ".content-slots", "at least one content slot is required");
        }
        used.addAll(content);
        int prev = slot(y, base + ".previous-page.slot", size, used, e);
        int next = slot(y, base + ".next-page.slot", size, used, e);
        int back = slot(y, base + ".back-button.slot", size, used, e);
        int info = slot(y, base + ".info.slot", size, used, e);
        Map<String, Integer> extraSlots = new LinkedHashMap<>();
        Map<String, ItemTemplate> extraItems = new LinkedHashMap<>();
        for (String extra : extras) {
            extraSlots.put(extra, slot(y, base + "." + extra + ".slot", size, used, e));
            extraItems.put(extra, tpl(y, base + "." + extra, text, e));
        }
        return new MenuSettings.Paged(y.getString(base + ".title", base), rows,
                tpl(y, base + ".filler", text, e), decorations(y, base + ".decorations", size, text, e), content,
                prev, tpl(y, base + ".previous-page", text, e),
                next, tpl(y, base + ".next-page", text, e),
                back, tpl(y, base + ".back-button", text, e),
                info, tpl(y, base + ".info", text, e),
                y.getStringList(base + ".reward-lore"), extraSlots, extraItems);
    }

    private static ItemTemplate tpl(YamlConfiguration y, String path, TextService text, ConfigErrors e) {
        return ItemTemplate.parse(y.getConfigurationSection(path), MENUS, path, e, text, null);
    }

    private static Map<String, ItemTemplate> states(YamlConfiguration y, String path, List<String> states,
                                                    TextService text, ConfigErrors e) {
        Map<String, ItemTemplate> map = new LinkedHashMap<>();
        for (String state : states) {
            map.put(state, tpl(y, path + "." + state, text, e));
        }
        return map;
    }

    private static List<MenuSettings.Decoration> decorations(YamlConfiguration y, String path, int size,
                                                             TextService text, ConfigErrors e) {
        List<MenuSettings.Decoration> list = new ArrayList<>();
        ConfigurationSection section = y.getConfigurationSection(path);
        if (section == null) {
            return list;
        }
        for (String key : section.getKeys(false)) {
            List<Integer> slots = slotList(y, path + "." + key + ".slots", size, e);
            list.add(new MenuSettings.Decoration(slots, tpl(y, path + "." + key, text, e)));
        }
        return list;
    }

    private static int slot(YamlConfiguration y, String path, int size, Set<Integer> used, ConfigErrors e) {
        if (!y.contains(path)) {
            e.error(MENUS, path, "slot is missing");
            return 0;
        }
        int slot = y.getInt(path, -1);
        if (slot < 0 || slot >= size) {
            e.error(MENUS, path, "slot must be between 0 and " + (size - 1));
            return 0;
        }
        if (!used.add(slot)) {
            e.error(MENUS, path, "slot " + slot + " is already used by another button");
        }
        return slot;
    }

    private static List<Integer> slotList(YamlConfiguration y, String path, int size, ConfigErrors e) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object raw : y.getList(path, List.of())) {
            List<Integer> values = new ArrayList<>();
            if (raw instanceof Number n) {
                values.add(n.intValue());
            } else if (raw instanceof String s && s.matches("\\s*\\d{1,2}\\s*-\\s*\\d{1,2}\\s*")) {
                String[] parts = s.split("-");
                int from = Integer.parseInt(parts[0].trim());
                int to = Integer.parseInt(parts[1].trim());
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    values.add(i);
                }
            } else {
                e.error(MENUS, path, "'" + raw + "' is not a slot number or range like 10-16");
                continue;
            }
            for (int v : values) {
                if (v < 0 || v >= size) {
                    e.error(MENUS, path, "slot " + v + " is outside 0-" + (size - 1));
                } else if (!seen.add(v)) {
                    e.error(MENUS, path, "slot " + v + " is listed twice");
                } else {
                    result.add(v);
                }
            }
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ rewards.yml

    private static RewardRegistry loadRewards(YamlConfiguration y, TextService text, Settings settings, ConfigErrors e) {
        Map<String, Rarity> rarities = new LinkedHashMap<>();
        ConfigurationSection raritySection = y.getConfigurationSection("rarities");
        if (raritySection != null) {
            for (String id : raritySection.getKeys(false)) {
                String p = "rarities." + id;
                if (!REWARD_ID.matcher(id).matches()) {
                    e.error(REWARDS, p, "rarity ids must be lowercase letters, digits, - or _");
                    continue;
                }
                String name = raritySection.getString(id + ".name", id);
                String problem = text.validate(name);
                if (problem != null) {
                    e.error(REWARDS, p + ".name", "invalid MiniMessage: " + problem);
                }
                Material pane = ItemTemplate.parseMaterial(raritySection.getString(id + ".pane", "GRAY_STAINED_GLASS_PANE"),
                        REWARDS, p + ".pane", e);
                rarities.put(id, new Rarity(id, name, pane == null ? Material.GRAY_STAINED_GLASS_PANE : pane,
                        raritySection.getInt(id + ".order", rarities.size() + 1),
                        raritySection.getBoolean(id + ".announce", false),
                        raritySection.getBoolean(id + ".celebrate", false)));
            }
        }
        if (rarities.isEmpty()) {
            rarities.put("common", new Rarity("common", "<gray>Common", Material.GRAY_STAINED_GLASS_PANE, 1, false, false));
        }
        List<RewardDefinition> rewards = new ArrayList<>();
        ConfigurationSection section = y.getConfigurationSection("rewards");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                RewardDefinition def = loadReward(section.getConfigurationSection(id), id, rarities, text, settings, e);
                if (def != null) {
                    rewards.add(def);
                }
            }
        }
        if (e.hasErrors()) {
            return null;
        }
        if (rewards.isEmpty()) {
            e.warn(REWARDS, "rewards", "no rewards are configured; spins are disabled until rewards are added");
        }
        return new RewardRegistry(rewards, rarities);
    }

    private static RewardDefinition loadReward(ConfigurationSection s, String id, Map<String, Rarity> rarities,
                                               TextService text, Settings settings, ConfigErrors e) {
        String p = "rewards." + id;
        if (s == null) {
            e.error(REWARDS, p, "must be a section");
            return null;
        }
        if (!REWARD_ID.matcher(id).matches()) {
            e.error(REWARDS, p, "reward ids must be 1-32 lowercase letters, digits, - or _");
            return null;
        }
        Object rawWeight = s.get("weight");
        if (!(rawWeight instanceof Number weightNumber)) {
            e.error(REWARDS, p + ".weight", "weight is required and must be a number");
            return null;
        }
        double weight = weightNumber.doubleValue();
        try {
            WeightedPicker.validateWeight(weight);
        } catch (IllegalArgumentException ex) {
            e.error(REWARDS, p + ".weight", ex.getMessage());
            return null;
        }
        String rarityId = s.getString("rarity", rarities.keySet().iterator().next());
        Rarity rarity = rarities.get(rarityId);
        if (rarity == null) {
            e.error(REWARDS, p + ".rarity", "unknown rarity '" + rarityId + "' (defined: " + rarities.keySet() + ")");
            return null;
        }
        String typeName = s.getString("type", s.contains("commands") ? "command" : "item");
        RewardType type;
        try {
            type = RewardType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            e.error(REWARDS, p + ".type", "must be 'item' or 'command'");
            return null;
        }

        ItemStack template = null;
        byte[] itemData = null;
        int amount = 1;
        List<String> commands = List.of();
        if (type == RewardType.ITEM) {
            ConfigurationSection item = s.getConfigurationSection("item");
            if (item == null) {
                e.error(REWARDS, p + ".item", "item rewards need an 'item' section");
                return null;
            }
            if (item.contains("serialized")) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(item.getString("serialized", "").trim());
                    template = ItemStack.deserializeBytes(bytes);
                } catch (RuntimeException ex) {
                    e.error(REWARDS, p + ".item.serialized", "could not read stored item: " + ex.getMessage());
                    return null;
                }
                if (template == null || template.getType().isAir()) {
                    e.error(REWARDS, p + ".item.serialized", "stored item is empty");
                    return null;
                }
                amount = item.getInt("amount", template.getAmount());
            } else {
                ConfigErrors local = new ConfigErrors();
                ItemTemplate t = ItemTemplate.parse(item, REWARDS, p + ".item", local, text, null);
                e.errors().addAll(local.errors());
                if (local.hasErrors()) {
                    return null;
                }
                template = t.render(text);
                amount = item.getInt("amount", 1);
            }
            if (amount < 1 || amount > MAX_ITEM_AMOUNT) {
                e.error(REWARDS, p + ".item.amount", "amount must be between 1 and " + MAX_ITEM_AMOUNT);
                return null;
            }
            template = template.clone();
            template.setAmount(1);
            try {
                itemData = template.serializeAsBytes();
            } catch (RuntimeException ex) {
                e.error(REWARDS, p + ".item", "item cannot be serialized: " + ex.getMessage());
                return null;
            }
        } else {
            List<String> raw = s.getStringList("commands");
            if (raw.isEmpty()) {
                e.error(REWARDS, p + ".commands", "command rewards need at least one command");
                return null;
            }
            if (raw.size() > MAX_COMMANDS) {
                e.error(REWARDS, p + ".commands", "at most " + MAX_COMMANDS + " commands per reward");
                return null;
            }
            List<String> normalized = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                try {
                    normalized.add(CommandTemplate.normalize(raw.get(i)));
                } catch (IllegalArgumentException ex) {
                    e.error(REWARDS, p + ".commands[" + i + "]", ex.getMessage());
                    return null;
                }
            }
            commands = List.copyOf(normalized);
        }

        // Display item
        ItemStack display;
        ConfigurationSection displaySection = s.getConfigurationSection("display");
        if (displaySection != null) {
            ConfigErrors local = new ConfigErrors();
            ItemTemplate dt = ItemTemplate.parse(displaySection, REWARDS, p + ".display", local, text, null);
            e.errors().addAll(local.errors());
            if (local.hasErrors()) {
                return null;
            }
            display = dt.render(text);
            if (!displaySection.contains("amount") && type == RewardType.ITEM) {
                display.setAmount(Math.min(amount, display.getMaxStackSize()));
            }
        } else if (type == RewardType.ITEM) {
            display = template.clone();
            display.setAmount(Math.max(1, Math.min(amount, display.getMaxStackSize())));
        } else {
            display = new ItemStack(settings.commandDisplayMaterial());
            ItemMeta meta = display.getItemMeta();
            meta.displayName(text.item("<body>" + id));
            display.setItemMeta(meta);
        }

        // Display name
        Component displayName;
        String displayNameRaw = s.getString("display-name");
        if (displayNameRaw != null) {
            String problem = text.validate(displayNameRaw);
            if (problem != null) {
                e.error(REWARDS, p + ".display-name", "invalid MiniMessage: " + problem);
                return null;
            }
            displayName = text.item(displayNameRaw);
        } else {
            ItemMeta meta = display.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                displayName = meta.displayName();
            } else {
                displayName = Component.translatable(display.translationKey())
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            }
            displayNameRaw = TextService.mini().serialize(displayName);
        }

        Boolean announce = s.contains("announce") ? s.getBoolean("announce") : null;
        String announcement = s.getString("announcement", "");
        if (!announcement.isEmpty()) {
            String problem = text.validate(announcement);
            if (problem != null) {
                e.error(REWARDS, p + ".announcement", "invalid MiniMessage: " + problem);
                return null;
            }
        }
        return new RewardDefinition(id, weight, rarity, type, template, itemData, type == RewardType.ITEM ? amount : 1,
                commands, display, displayName, displayNameRaw, announce, announcement);
    }
}
