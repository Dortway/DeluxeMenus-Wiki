package dev.exodaily.core.config;

import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parses and validates the four configuration files. Loading never throws for bad content:
 * every problem becomes a {@link ConfigIssue} naming the file and entry, and any error means no
 * bundle is produced, so the caller keeps its previous valid configuration.
 */
public final class ConfigLoader {

    public static final String CONFIG = "config.yml";
    public static final String MESSAGES = "messages.yml";
    public static final String MENUS = "menus.yml";
    public static final String REWARDS = "rewards.yml";
    public static final List<String> FILES = List.of(CONFIG, MESSAGES, MENUS, REWARDS);

    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final int MAX_WEIGHT = 1_000_000;
    private static final int MAX_CYCLE_LENGTH = 366;
    private static final int MAX_STACKS_PER_REWARD = 36;

    private final PlatformValidator platform;

    public ConfigLoader(PlatformValidator platform) {
        this.platform = platform;
    }

    public record Result(ConfigBundle bundle, List<ConfigIssue> issues) {

        public boolean success() {
            return bundle != null;
        }

        public List<ConfigIssue> errors() {
            return issues.stream().filter(ConfigIssue::isError).toList();
        }

        public List<ConfigIssue> warnings() {
            return issues.stream().filter(issue -> !issue.isError()).toList();
        }
    }

    /**
     * @param files    file name to raw YAML content (null when the file is missing)
     * @param defaults bundled default contents, used to fill in missing message and line keys
     */
    public Result load(Map<String, String> files, Map<String, String> defaults) {
        List<ConfigIssue> issues = new ArrayList<>();
        Map<String, YamlConfiguration> parsed = new HashMap<>();
        for (String file : FILES) {
            YamlConfiguration yaml = parse(file, files.get(file), issues);
            if (yaml != null) {
                parsed.put(file, yaml);
            }
        }
        if (parsed.size() != FILES.size()) {
            return new Result(null, List.copyOf(issues));
        }
        YamlConfiguration defaultMessages = parseQuietly(defaults.get(MESSAGES));
        YamlConfiguration defaultMenus = parseQuietly(defaults.get(MENUS));

        PluginSettings settings = settings(new Ctx(CONFIG, issues), parsed.get(CONFIG));
        Messages messages = messages(new Ctx(MESSAGES, issues), parsed.get(MESSAGES), defaultMessages);
        MenuConfig menus = menus(new Ctx(MENUS, issues), parsed.get(MENUS), defaultMenus);
        RewardCatalog catalog = rewards(new Ctx(REWARDS, issues), parsed.get(REWARDS));

        if (settings != null && catalog != null) {
            crossCheck(settings, catalog, menus, issues);
        }
        boolean failed = issues.stream().anyMatch(ConfigIssue::isError)
                || settings == null || messages == null || menus == null || catalog == null;
        ConfigBundle bundle = failed ? null : new ConfigBundle(settings, messages, menus, catalog);
        return new Result(bundle, List.copyOf(issues));
    }

    // ===================================================================== parsing

    private static YamlConfiguration parse(String file, String content, List<ConfigIssue> issues) {
        if (content == null) {
            issues.add(ConfigIssue.error(file, "", "file is missing"));
            return null;
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(64 * 1024 * 1024);
        try {
            new Yaml(new SafeConstructor(options)).load(content);
        } catch (YAMLException e) {
            issues.add(ConfigIssue.error(file, "", "malformed YAML: " + oneLine(e.getMessage())));
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
        } catch (InvalidConfigurationException e) {
            issues.add(ConfigIssue.error(file, "", "malformed YAML: " + oneLine(e.getMessage())));
            return null;
        }
        return yaml;
    }

    private static YamlConfiguration parseQuietly(String content) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (content == null) {
            return yaml;
        }
        try {
            yaml.loadFromString(content);
        } catch (InvalidConfigurationException ignored) {
            // Bundled defaults are validated by tests; an empty fallback is acceptable here.
        }
        return yaml;
    }

    private static String oneLine(String message) {
        return message == null ? "unknown error" : message.replaceAll("\\s+", " ").trim();
    }

    // ===================================================================== config.yml

    private PluginSettings settings(Ctx ctx, YamlConfiguration yaml) {
        String zoneName = ctx.string(yaml, "timezone", "Europe/London");
        ZoneId zone = ZoneId.of("Europe/London");
        try {
            zone = ZoneId.of(zoneName);
        } catch (DateTimeException e) {
            ctx.error("timezone", "unknown timezone '" + zoneName + "' (use an IANA id such as Europe/London)");
        }
        int cycleLength = ctx.integer(yaml, "cycle-length", 30, 1, MAX_CYCLE_LENGTH);

        String type = ctx.string(yaml, "storage.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(type)) {
            ctx.error("storage.type", "unsupported storage type '" + type + "'; only 'sqlite' is supported");
        }
        String file = ctx.string(yaml, "storage.file", "data.db");
        if (file.isBlank() || file.contains("..") || file.startsWith("/") || file.startsWith("\\") || file.contains(":")) {
            ctx.error("storage.file", "must be a simple file name inside the plugin folder, got '" + file + "'");
        }
        int busyTimeout = ctx.integer(yaml, "storage.busy-timeout-ms", 5000, 0, 60_000);
        int queueCapacity = ctx.integer(yaml, "storage.queue-capacity", 1024, 16, 100_000);

        long clickCooldown = ctx.integer(yaml, "claims.click-cooldown-ms", 350, 0, 10_000);
        long openCooldown = ctx.integer(yaml, "claims.open-cooldown-ms", 750, 0, 10_000);
        int countdownTicks = ctx.integer(yaml, "menus.countdown-update-ticks", 20, 5, 1200);

        boolean smallCaps = ctx.bool(yaml, "style.small-caps", true);
        boolean symbols = ctx.bool(yaml, "style.symbols", true);
        Map<String, PluginSettings.Symbol> symbolMap = new LinkedHashMap<>();
        ConfigurationSection symbolSection = yaml.getConfigurationSection("style.symbol-set");
        if (symbolSection == null) {
            ctx.error("style.symbol-set", "missing section");
        } else {
            for (String key : symbolSection.getKeys(false)) {
                String path = "style.symbol-set." + key;
                String fancy = ctx.string(yaml, path + ".fancy", null);
                String plain = ctx.string(yaml, path + ".plain", null);
                if (fancy != null && plain != null) {
                    symbolMap.put(key, new PluginSettings.Symbol(fancy, plain));
                }
            }
        }
        boolean auditFile = ctx.bool(yaml, "audit.file", true);
        boolean notifyAdmins = ctx.bool(yaml, "notifications.notify-admins-on-join", true);
        ctx.version(yaml);
        return new PluginSettings(zone, cycleLength,
                new PluginSettings.Storage(type.toLowerCase(Locale.ROOT), file, busyTimeout, queueCapacity),
                clickCooldown, openCooldown, countdownTicks,
                new PluginSettings.Style(smallCaps, symbols, symbolMap), auditFile, notifyAdmins);
    }

    // ===================================================================== messages.yml

    private Messages messages(Ctx ctx, YamlConfiguration yaml, YamlConfiguration defaults) {
        Map<String, String> templates = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>(defaults.getKeys(false));
        keys.addAll(yaml.getKeys(false));
        keys.remove("config-version");
        for (String key : keys) {
            Object value = yaml.get(key);
            boolean fromDefault = false;
            if (value == null) {
                value = defaults.get(key);
                fromDefault = true;
                ctx.warning(key, "missing; using the built-in default");
            }
            if (value instanceof String string) {
                templates.put(key, string);
            } else if (value instanceof List<?> list) {
                List<String> lines = new ArrayList<>();
                for (Object line : list) {
                    if (line instanceof String s) {
                        lines.add(s);
                    } else {
                        ctx.error(key, "list entries must be text");
                    }
                }
                lists.put(key, lines);
            } else if (!fromDefault) {
                ctx.error(key, "must be text or a list of text");
            }
        }
        ctx.version(yaml);
        return new Messages(templates, lists);
    }

    // ===================================================================== menus.yml

    private MenuConfig menus(Ctx ctx, YamlConfiguration yaml, YamlConfiguration defaults) {
        MenuConfig.Frame mainFrame = frame(ctx, yaml, "main", 3, 3);
        Map<String, MenuConfig.ItemSpec> mainItems = items(ctx, yaml, "main.items", MenuConfig.Main.REQUIRED, true);
        SlotMap mainSlots = new SlotMap(ctx, "main", mainFrame == null ? 27 : mainFrame.size());
        mainItems.forEach((key, spec) -> mainSlots.claim(spec.slot(), "main.items." + key));

        MenuConfig.Frame detailsFrame = frame(ctx, yaml, "details", 1, 6);
        Map<String, MenuConfig.ItemSpec> detailsItems = items(ctx, yaml, "details.items", MenuConfig.Details.REQUIRED, true);
        SlotMap detailSlots = new SlotMap(ctx, "details", detailsFrame == null ? 27 : detailsFrame.size());
        detailsItems.forEach((key, spec) -> detailSlots.claim(spec.slot(), "details.items." + key));
        Map<RewardPosition, MenuConfig.PositionSlots> positions = new EnumMap<>(RewardPosition.class);
        for (RewardPosition position : RewardPosition.values()) {
            String base = "details.positions." + position.number();
            if (!yaml.isConfigurationSection(base)) {
                ctx.error(base, "missing position " + position.number());
                continue;
            }
            int rewardSlot = ctx.integer(yaml, base + ".reward-slot", -1, 0, 53);
            int buttonSlot = ctx.integer(yaml, base + ".button-slot", -1, 0, 53);
            detailSlots.claim(rewardSlot, base + ".reward-slot");
            detailSlots.claim(buttonSlot, base + ".button-slot");
            positions.put(position, new MenuConfig.PositionSlots(rewardSlot, buttonSlot));
        }
        List<String> loreBefore = ctx.stringList(yaml, "details.reward-item.lore-before");
        List<String> loreAfter = ctx.stringList(yaml, "details.reward-item.lore-after");
        boolean showRewardLore = ctx.bool(yaml, "details.reward-item.show-reward-lore", true);
        MenuConfig.ItemSpec claimButton = item(ctx, yaml, "details.claim-button", false);

        MenuConfig.Frame overviewFrame = frame(ctx, yaml, "overview", 1, 6);
        Map<String, MenuConfig.ItemSpec> overviewItems = items(ctx, yaml, "overview.items", MenuConfig.Overview.REQUIRED, true);
        SlotMap overviewSlots = new SlotMap(ctx, "overview", overviewFrame == null ? 54 : overviewFrame.size());
        overviewItems.forEach((key, spec) -> overviewSlots.claim(spec.slot(), "overview.items." + key));
        List<Integer> daySlots = new ArrayList<>();
        List<?> rawDaySlots = yaml.getList("overview.day-slots");
        if (rawDaySlots == null) {
            ctx.error("overview.day-slots", "missing list of slots");
        } else {
            for (int i = 0; i < rawDaySlots.size(); i++) {
                Object raw = rawDaySlots.get(i);
                if (raw instanceof Integer slot) {
                    daySlots.add(slot);
                    overviewSlots.claim(slot, "overview.day-slots[" + i + "]");
                } else {
                    ctx.error("overview.day-slots[" + i + "]", "must be a whole number");
                }
            }
        }
        MenuConfig.ItemSpec dayItem = item(ctx, yaml, "overview.day-item", false);

        Map<String, MenuConfig.SoundSpec> sounds = new HashMap<>();
        ConfigurationSection soundSection = yaml.getConfigurationSection("sounds");
        if (soundSection == null) {
            ctx.warning("sounds", "missing section; sounds are disabled");
        } else {
            for (String key : soundSection.getKeys(false)) {
                String path = "sounds." + key;
                String sound = ctx.string(yaml, path + ".sound", "none");
                float volume = (float) ctx.decimal(yaml, path + ".volume", 0.6, 0.0, 10.0);
                float pitch = (float) ctx.decimal(yaml, path + ".pitch", 1.0, 0.5, 2.0);
                if ("none".equalsIgnoreCase(sound) || sound.isBlank()) {
                    sounds.put(key, new MenuConfig.SoundSpec(null, volume, pitch));
                    continue;
                }
                platform.checkSound(sound).ifPresent(error -> ctx.error(path + ".sound", error));
                sounds.put(key, new MenuConfig.SoundSpec(sound, volume, pitch));
            }
        }

        boolean effectEnabled = ctx.bool(yaml, "claim-effect.enabled", true);
        String particle = ctx.string(yaml, "claim-effect.particle", "HAPPY_VILLAGER");
        if (effectEnabled) {
            platform.checkParticle(particle).ifPresent(error -> ctx.error("claim-effect.particle", error));
        }
        int count = ctx.integer(yaml, "claim-effect.count", 12, 1, 100);
        double spread = ctx.decimal(yaml, "claim-effect.spread", 0.4, 0.0, 3.0);

        Map<String, String> lines = new HashMap<>();
        ConfigurationSection defaultLines = defaults.getConfigurationSection("lines");
        ConfigurationSection lineSection = yaml.getConfigurationSection("lines");
        Set<String> lineKeys = new LinkedHashSet<>();
        if (defaultLines != null) {
            lineKeys.addAll(defaultLines.getKeys(false));
        }
        if (lineSection != null) {
            lineKeys.addAll(lineSection.getKeys(false));
        }
        for (String key : lineKeys) {
            String value = lineSection == null ? null : lineSection.getString(key);
            if (value == null && defaultLines != null) {
                value = defaultLines.getString(key);
                ctx.warning("lines." + key, "missing; using the built-in default");
            }
            if (value != null) {
                lines.put(key, value);
            }
        }
        String dateFormat = lines.get("date-format");
        if (dateFormat != null) {
            try {
                java.time.format.DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
            } catch (IllegalArgumentException e) {
                ctx.error("lines.date-format", "invalid date pattern '" + dateFormat + "': " + e.getMessage());
            }
        }
        ctx.version(yaml);

        if (mainFrame == null || detailsFrame == null || overviewFrame == null || claimButton == null || dayItem == null) {
            return null;
        }
        return new MenuConfig(
                new MenuConfig.Main(mainFrame, mainItems),
                new MenuConfig.Details(detailsFrame, detailsItems, positions, loreBefore, loreAfter, showRewardLore, claimButton),
                new MenuConfig.Overview(overviewFrame, overviewItems, daySlots, dayItem),
                sounds,
                new MenuConfig.ClaimEffect(effectEnabled, particle, count, spread),
                lines);
    }

    private MenuConfig.Frame frame(Ctx ctx, YamlConfiguration yaml, String menu, int minRows, int maxRows) {
        if (!yaml.isConfigurationSection(menu)) {
            ctx.error(menu, "missing menu section");
            return null;
        }
        String title = ctx.string(yaml, menu + ".title", "");
        int rows = ctx.integer(yaml, menu + ".rows", minRows, minRows, maxRows);
        boolean enabled = ctx.bool(yaml, menu + ".border.enabled", true);
        String material = ctx.string(yaml, menu + ".border.material", "BLACK_STAINED_GLASS_PANE");
        String accent = ctx.string(yaml, menu + ".border.accent-material", material);
        if (enabled) {
            platform.checkIconMaterial(material).ifPresent(e -> ctx.error(menu + ".border.material", e));
            platform.checkIconMaterial(accent).ifPresent(e -> ctx.error(menu + ".border.accent-material", e));
        }
        Set<Integer> accentSlots = new HashSet<>();
        for (Object raw : yaml.getList(menu + ".border.accent-slots", List.of())) {
            if (raw instanceof Integer slot && slot >= 0 && slot < rows * 9) {
                accentSlots.add(slot);
            } else {
                ctx.error(menu + ".border.accent-slots", "invalid slot '" + raw + "' (0-" + (rows * 9 - 1) + ")");
            }
        }
        return new MenuConfig.Frame(title, rows, new MenuConfig.Border(enabled, material, accent, accentSlots));
    }

    private Map<String, MenuConfig.ItemSpec> items(Ctx ctx, YamlConfiguration yaml, String path, List<String> required,
                                                   boolean needsSlot) {
        Map<String, MenuConfig.ItemSpec> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            ctx.error(path, "missing section");
            return result;
        }
        for (String key : required) {
            if (!section.isConfigurationSection(key)) {
                ctx.error(path + "." + key, "required item is missing");
            }
        }
        for (String key : section.getKeys(false)) {
            if (!required.contains(key)) {
                ctx.warning(path + "." + key, "unknown item key; it is ignored (known: " + String.join(", ", required) + ")");
                continue;
            }
            MenuConfig.ItemSpec spec = item(ctx, yaml, path + "." + key, needsSlot);
            if (spec != null) {
                result.put(key, spec);
            }
        }
        return result;
    }

    private MenuConfig.ItemSpec item(Ctx ctx, YamlConfiguration yaml, String path, boolean needsSlot) {
        if (!yaml.isConfigurationSection(path)) {
            ctx.error(path, "missing item section");
            return null;
        }
        int slot = -1;
        if (needsSlot) {
            if (!yaml.isInt(path + ".slot")) {
                ctx.error(path + ".slot", "missing or not a whole number");
            } else {
                slot = yaml.getInt(path + ".slot");
            }
        }
        MenuConfig.Variant base = variant(ctx, yaml, path, true);
        Map<String, MenuConfig.Variant> states = new HashMap<>();
        ConfigurationSection stateSection = yaml.getConfigurationSection(path + ".states");
        if (stateSection != null) {
            for (String state : stateSection.getKeys(false)) {
                states.put(state, variant(ctx, yaml, path + ".states." + state, false));
            }
        }
        return new MenuConfig.ItemSpec(slot, base, states);
    }

    private MenuConfig.Variant variant(Ctx ctx, YamlConfiguration yaml, String path, boolean base) {
        String material = yaml.getString(path + ".material");
        if (material == null && base) {
            ctx.error(path + ".material", "missing material");
        } else if (material != null) {
            platform.checkIconMaterial(material).ifPresent(e -> ctx.error(path + ".material", e));
        }
        String name = yaml.isSet(path + ".name") ? ctx.string(yaml, path + ".name", "") : null;
        List<String> lore = yaml.isSet(path + ".lore") ? ctx.stringList(yaml, path + ".lore") : null;
        Boolean glow = yaml.isSet(path + ".glow") ? ctx.bool(yaml, path + ".glow", false) : null;
        return new MenuConfig.Variant(material, name, lore, glow);
    }

    /** Detects slot collisions and out-of-range slots within one menu. */
    private static final class SlotMap {
        private final Ctx ctx;
        private final String menu;
        private final int size;
        private final Map<Integer, String> owners = new TreeMap<>();

        SlotMap(Ctx ctx, String menu, int size) {
            this.ctx = ctx;
            this.menu = menu;
            this.size = size;
        }

        void claim(int slot, String owner) {
            if (slot < 0) {
                return;
            }
            if (slot >= size) {
                ctx.error(owner, "slot " + slot + " is outside the " + menu + " menu (0-" + (size - 1) + ")");
                return;
            }
            String previous = owners.putIfAbsent(slot, owner);
            if (previous != null) {
                ctx.error(owner, "slot " + slot + " conflicts with " + previous);
            }
        }
    }

    // ===================================================================== rewards.yml

    private RewardCatalog rewards(Ctx ctx, YamlConfiguration yaml) {
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        ConfigurationSection rewardSection = yaml.getConfigurationSection("rewards");
        if (rewardSection == null || rewardSection.getKeys(false).isEmpty()) {
            ctx.error("rewards", "no rewards are defined");
        } else {
            for (String id : rewardSection.getKeys(false)) {
                String path = "rewards." + id;
                if (!ID_PATTERN.matcher(id).matches()) {
                    ctx.error(path, "invalid reward id '" + id + "' (use lowercase letters, digits, '_' or '-', max 64)");
                    continue;
                }
                if (!yaml.isConfigurationSection(path)) {
                    ctx.error(path, "must be a section");
                    continue;
                }
                RewardDefinition reward = reward(ctx, yaml, id, path);
                if (reward != null) {
                    rewards.put(id, reward);
                }
            }
        }

        Map<String, RewardPool> pools = new LinkedHashMap<>();
        ConfigurationSection poolSection = yaml.getConfigurationSection("pools");
        if (poolSection == null || poolSection.getKeys(false).isEmpty()) {
            ctx.error("pools", "no pools are defined");
        } else {
            for (String id : poolSection.getKeys(false)) {
                String path = "pools." + id;
                if (!ID_PATTERN.matcher(id).matches()) {
                    ctx.error(path, "invalid pool id '" + id + "'");
                    continue;
                }
                RewardPool pool = pool(ctx, yaml, id, path, rewards);
                if (pool != null) {
                    pools.put(id, pool);
                }
            }
            for (RewardPool pool : pools.values()) {
                if (pool.fallback() != null && !pools.containsKey(pool.fallback())) {
                    ctx.error("pools." + pool.id() + ".fallback", "unknown pool '" + pool.fallback() + "'");
                }
            }
        }

        boolean preventDuplicates = ctx.bool(yaml, "settings.prevent-duplicates-per-day", true);
        String fallbackPool = yaml.getString("settings.fallback-pool");
        if (fallbackPool != null && !pools.containsKey(fallbackPool)) {
            ctx.error("settings.fallback-pool", "unknown pool '" + fallbackPool + "'");
        }
        int previewMax = ctx.integer(yaml, "settings.preview-max-entries", 4, 0, 20);
        Set<Integer> milestones = new HashSet<>();
        for (Object raw : yaml.getList("settings.milestone-days", List.of())) {
            if (raw instanceof Integer day && day >= 1) {
                milestones.add(day);
            } else {
                ctx.error("settings.milestone-days", "invalid day '" + raw + "'");
            }
        }

        DaySchedule defaults = schedule(ctx, yaml, "schedule.default", pools, true);
        Map<Integer, DaySchedule> days = new TreeMap<>();
        ConfigurationSection daySection = yaml.getConfigurationSection("schedule.days");
        if (daySection != null) {
            for (String key : daySection.getKeys(false)) {
                String path = "schedule.days." + key;
                int day;
                try {
                    day = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    ctx.error(path, "day keys must be whole numbers");
                    continue;
                }
                if (day < 1 || day > MAX_CYCLE_LENGTH) {
                    ctx.error(path, "day must be between 1 and " + MAX_CYCLE_LENGTH);
                    continue;
                }
                DaySchedule schedule = schedule(ctx, yaml, path, pools, false);
                if (schedule != null) {
                    days.put(day, schedule);
                }
            }
        }
        ctx.version(yaml);
        if (defaults == null) {
            return null;
        }
        return new RewardCatalog(rewards, pools, defaults, days, preventDuplicates, fallbackPool, previewMax, milestones);
    }

    private RewardDefinition reward(Ctx ctx, YamlConfiguration yaml, String id, String path) {
        int errorsBefore = ctx.errorCount();
        String serialized = yaml.getString(path + ".serialized-item");
        int maxStack = 64;
        String material;
        if (serialized != null) {
            Optional<String> error = platform.checkSerializedItem(serialized);
            error.ifPresent(e -> ctx.error(path + ".serialized-item", e));
            material = yaml.getString(path + ".material", "STONE");
            if (error.isEmpty()) {
                maxStack = Math.max(1, platform.serializedMaxStackSize(serialized));
            }
        } else {
            material = yaml.getString(path + ".material");
            if (material == null) {
                ctx.error(path + ".material", "missing material");
                material = "STONE";
            } else {
                Optional<String> error = platform.checkItemMaterial(material);
                error.ifPresent(e -> ctx.error(path + ".material", e));
                if (error.isEmpty()) {
                    maxStack = platform.maxStackSize(material);
                }
            }
        }
        int maxAmount = maxStack * MAX_STACKS_PER_REWARD;
        int amount = ctx.integer(yaml, path + ".amount", 1, 1, maxAmount);
        int weight = ctx.integer(yaml, path + ".weight", 10, 1, MAX_WEIGHT);
        String name = yaml.isSet(path + ".name") ? ctx.string(yaml, path + ".name", null) : null;
        List<String> lore = yaml.isSet(path + ".lore") ? ctx.stringList(yaml, path + ".lore") : List.of();

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        if (yaml.isSet(path + ".enchantments")) {
            ConfigurationSection enchantSection = yaml.getConfigurationSection(path + ".enchantments");
            if (enchantSection == null) {
                ctx.error(path + ".enchantments", "must be a map of enchantment: level");
            } else {
                for (String key : enchantSection.getKeys(false)) {
                    String enchantPath = path + ".enchantments." + key;
                    int level = ctx.integer(yaml, enchantPath, 1, 1, 255);
                    platform.checkEnchantment(key, level).ifPresent(e -> ctx.error(enchantPath, e));
                    enchantments.put(key.toLowerCase(Locale.ROOT), level);
                }
            }
        }
        List<String> flags = new ArrayList<>();
        for (String flag : yaml.isSet(path + ".flags") ? ctx.stringList(yaml, path + ".flags") : List.<String>of()) {
            platform.checkItemFlag(flag).ifPresent(e -> ctx.error(path + ".flags", e));
            flags.add(flag.toUpperCase(Locale.ROOT));
        }
        Float customModelData = null;
        if (yaml.isSet(path + ".custom-model-data")) {
            Object raw = yaml.get(path + ".custom-model-data");
            if (raw instanceof Number number) {
                customModelData = number.floatValue();
            } else {
                ctx.error(path + ".custom-model-data", "must be a number");
            }
        }
        String typeName = ctx.string(yaml, path + ".type", RewardType.ITEM.key());
        RewardType type = RewardType.parse(typeName).orElse(null);
        if (type == null) {
            ctx.error(path + ".type", "unknown type '" + typeName + "' (use item, command or both)");
            type = RewardType.ITEM;
        }
        List<String> commands = commands(ctx, yaml, path, type);
        String summary = yaml.getString(path + ".summary");
        if (summary == null) {
            if (type == RewardType.COMMAND) {
                ctx.error(path + ".summary", "command rewards need a summary describing what the player gets");
            }
            summary = amount + " " + material.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        if (ctx.errorCount() > errorsBefore) {
            return null;
        }
        return new RewardDefinition(id, material.toUpperCase(Locale.ROOT), amount, name, lore, enchantments, flags,
                customModelData, summary, weight, serialized, type, commands);
    }

    private static final int MAX_COMMANDS = 16;
    private static final int MAX_COMMAND_LENGTH = 1024;

    private List<String> commands(Ctx ctx, YamlConfiguration yaml, String path, RewardType type) {
        List<String> raw = yaml.isSet(path + ".commands") ? ctx.stringList(yaml, path + ".commands") : List.of();
        if (!type.runsCommands()) {
            if (!raw.isEmpty()) {
                ctx.error(path + ".commands", "commands are only run for type 'command' or 'both' (type is 'item')");
            }
            return List.of();
        }
        if (raw.isEmpty()) {
            ctx.error(path + ".commands", "type '" + type.key() + "' needs at least one command");
            return List.of();
        }
        if (raw.size() > MAX_COMMANDS) {
            ctx.error(path + ".commands", "at most " + MAX_COMMANDS + " commands per reward");
        }
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String command = raw.get(i).strip();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            String entry = path + ".commands[" + i + "]";
            if (command.isBlank()) {
                ctx.error(entry, "command is empty");
                continue;
            }
            if (command.length() > MAX_COMMAND_LENGTH || command.contains("\n") || command.contains("\r")) {
                ctx.error(entry, "command must be a single line of at most " + MAX_COMMAND_LENGTH + " characters");
                continue;
            }
            String unknown = unknownPlaceholder(command);
            if (unknown != null) {
                ctx.error(entry, "unknown placeholder {" + unknown + "} (use " + String.join(", ", COMMAND_PLACEHOLDERS) + ")");
            }
            String label = command.split(" ", 2)[0];
            platform.commandWarning(label).ifPresent(warning -> ctx.warning(entry, warning));
            commands.add(command);
        }
        return commands;
    }

    /** Placeholders available in reward commands. */
    public static final List<String> COMMAND_PLACEHOLDERS =
            List.of("{player}", "{uuid}", "{claim_id}", "{cycle}", "{day}", "{position}", "{reward}");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private static String unknownPlaceholder(String command) {
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(command);
        while (matcher.find()) {
            if (!COMMAND_PLACEHOLDERS.contains("{" + matcher.group(1) + "}")) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private RewardPool pool(Ctx ctx, YamlConfiguration yaml, String id, String path, Map<String, RewardDefinition> rewards) {
        String description = ctx.string(yaml, path + ".description", "");
        String fallback = yaml.getString(path + ".fallback");
        List<?> rawEntries = yaml.getList(path + ".entries");
        if (rawEntries == null || rawEntries.isEmpty()) {
            ctx.error(path + ".entries", "pool is empty; add at least one reward");
            return null;
        }
        List<PoolEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawEntries.size(); i++) {
            Object raw = rawEntries.get(i);
            String entryPath = path + ".entries[" + i + "]";
            String rewardId;
            Integer weight = null;
            if (raw instanceof String s) {
                rewardId = s;
            } else if (raw instanceof Map<?, ?> map && map.get("reward") instanceof String s) {
                rewardId = s;
                Object rawWeight = map.get("weight");
                if (rawWeight != null) {
                    if (rawWeight instanceof Integer w && w >= 1 && w <= MAX_WEIGHT) {
                        weight = w;
                    } else {
                        ctx.error(entryPath + ".weight", "weight must be a whole number between 1 and " + MAX_WEIGHT);
                        continue;
                    }
                }
            } else {
                ctx.error(entryPath, "entry must be a reward id or {reward: id, weight: n}");
                continue;
            }
            RewardDefinition reward = rewards.get(rewardId);
            if (reward == null) {
                ctx.error(entryPath, "unknown reward '" + rewardId + "'");
                continue;
            }
            if (!seen.add(rewardId)) {
                ctx.warning(entryPath, "reward '" + rewardId + "' is listed more than once in this pool");
            }
            entries.add(new PoolEntry(rewardId, weight != null ? weight : reward.weight()));
        }
        if (entries.isEmpty()) {
            ctx.error(path + ".entries", "pool has no valid entries");
            return null;
        }
        return new RewardPool(id, description, entries, fallback);
    }

    private DaySchedule schedule(Ctx ctx, YamlConfiguration yaml, String path, Map<String, RewardPool> pools, boolean complete) {
        if (!yaml.isConfigurationSection(path)) {
            ctx.error(path, "missing section");
            return null;
        }
        Map<RewardPosition, String> map = new EnumMap<>(RewardPosition.class);
        for (RewardPosition position : RewardPosition.values()) {
            String key = path + ".position-" + position.number();
            String pool = yaml.getString(key);
            if (pool == null) {
                if (complete) {
                    ctx.error(key, "missing pool for position " + position.number());
                }
                continue;
            }
            if (!pools.containsKey(pool)) {
                ctx.error(key, "unknown pool '" + pool + "'");
                continue;
            }
            map.put(position, pool);
        }
        ConfigurationSection section = yaml.getConfigurationSection(path);
        for (String key : section.getKeys(false)) {
            if (!key.matches("position-[1-3]")) {
                ctx.warning(path + "." + key, "unknown key; expected position-1, position-2 or position-3");
            }
        }
        return new DaySchedule(map);
    }

    // ===================================================================== cross-file checks

    private static void crossCheck(PluginSettings settings, RewardCatalog catalog, MenuConfig menus, List<ConfigIssue> issues) {
        for (int day : catalog.days().keySet()) {
            if (day > settings.cycleLength()) {
                issues.add(ConfigIssue.warning(REWARDS, "schedule.days." + day,
                        "day is beyond cycle-length " + settings.cycleLength() + " and is never used"));
            }
        }
        if (menus != null && menus.overview().daySlots().size() < settings.cycleLength()) {
            issues.add(ConfigIssue.error(MENUS, "overview.day-slots", "has " + menus.overview().daySlots().size()
                    + " slots but cycle-length in " + CONFIG + " is " + settings.cycleLength()
                    + "; add slots (or use more rows) so every day can be shown"));
        }
        if (catalog.preventDuplicates()) {
            Set<DaySchedule> checked = new HashSet<>();
            for (int day = 1; day <= settings.cycleLength(); day++) {
                DaySchedule schedule = catalog.scheduleFor(day);
                if (!checked.add(schedule)) {
                    continue;
                }
                Set<String> unique = new HashSet<>();
                for (RewardPosition position : RewardPosition.values()) {
                    Set<String> visited = new HashSet<>();
                    String pool = schedule.poolFor(position);
                    while (pool != null && visited.add(pool) && catalog.pools().containsKey(pool)) {
                        catalog.pools().get(pool).entries().forEach(entry -> unique.add(entry.rewardId()));
                        pool = catalog.pools().get(pool).fallback();
                    }
                }
                if (catalog.fallbackPool() != null && catalog.pools().containsKey(catalog.fallbackPool())) {
                    catalog.pools().get(catalog.fallbackPool()).entries().forEach(entry -> unique.add(entry.rewardId()));
                }
                if (unique.size() < RewardPosition.values().length) {
                    issues.add(ConfigIssue.warning(REWARDS, "schedule (day " + day + ")",
                            "fewer than 3 distinct rewards are reachable; duplicates will be assigned despite prevent-duplicates-per-day"));
                }
            }
        }
    }

    // ===================================================================== helpers

    private static final class Ctx {
        private final String file;
        private final List<ConfigIssue> issues;

        Ctx(String file, List<ConfigIssue> issues) {
            this.file = file;
            this.issues = issues;
        }

        void error(String path, String message) {
            issues.add(ConfigIssue.error(file, path, message));
        }

        void warning(String path, String message) {
            issues.add(ConfigIssue.warning(file, path, message));
        }

        int errorCount() {
            return (int) issues.stream().filter(ConfigIssue::isError).count();
        }

        void version(YamlConfiguration yaml) {
            if (!yaml.isSet("config-version")) {
                warning("config-version", "missing; assuming version 1");
            } else if (!yaml.isInt("config-version") || yaml.getInt("config-version") != 1) {
                warning("config-version", "unexpected version '" + yaml.get("config-version") + "'; expected 1");
            }
        }

        String string(ConfigurationSection section, String path, String fallback) {
            if (!section.isSet(path)) {
                if (fallback == null) {
                    error(path, "missing value");
                }
                return fallback;
            }
            Object value = section.get(path);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            error(path, "must be text");
            return fallback;
        }

        List<String> stringList(ConfigurationSection section, String path) {
            Object value = section.get(path);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                error(path, "must be a list of text");
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (entry instanceof String || entry instanceof Number) {
                    result.add(String.valueOf(entry));
                } else {
                    error(path + "[" + i + "]", "must be text");
                }
            }
            return result;
        }

        int integer(ConfigurationSection section, String path, int fallback, int min, int max) {
            if (!section.isSet(path)) {
                if (fallback < min) {
                    error(path, "missing value");
                }
                return fallback;
            }
            Object value = section.get(path);
            if (!(value instanceof Integer number)) {
                error(path, "must be a whole number, got '" + value + "'");
                return fallback;
            }
            if (number < min || number > max) {
                error(path, "must be between " + min + " and " + max + ", got " + number);
                return fallback;
            }
            return number;
        }

        double decimal(ConfigurationSection section, String path, double fallback, double min, double max) {
            if (!section.isSet(path)) {
                return fallback;
            }
            Object value = section.get(path);
            if (!(value instanceof Number number)) {
                error(path, "must be a number, got '" + value + "'");
                return fallback;
            }
            double d = number.doubleValue();
            if (d < min || d > max) {
                error(path, "must be between " + min + " and " + max + ", got " + d);
                return fallback;
            }
            return d;
        }

        boolean bool(ConfigurationSection section, String path, boolean fallback) {
            if (!section.isSet(path)) {
                return fallback;
            }
            Object value = section.get(path);
            if (!(value instanceof Boolean b)) {
                error(path, "must be true or false, got '" + value + "'");
                return fallback;
            }
            return b;
        }
    }
}
