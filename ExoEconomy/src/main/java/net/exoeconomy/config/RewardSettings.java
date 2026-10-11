package net.exoeconomy.config;

import net.exoeconomy.core.Money;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.math.BigDecimal;
import java.util.*;

/** rewards.yml */
public record RewardSettings(Map<String, Activity> activities, BigDecimal totalDailyCap, int flushTicks,
                             Notifications notifications, Mining mining, Farming farming, Slaying slaying) {
    public static final String MINING = "mining", FARMING = "farming", SLAYING = "slaying", SELLING = "selling";

    /**
     * @param worlds          worlds where the activity pays; empty means every world not in disabledWorlds
     * @param rewards         base Shards per block or kill, keyed by Material/EntityType name (custom activities: free-form keys)
     * @param defaultReward   base reward for API calls that do not pass an amount and have no keyed entry
     */
    public record Activity(String id, boolean enabled, String permission, Set<String> worlds, Set<String> disabledWorlds,
                           Set<GameMode> gameModes, BigDecimal dailyCap, Map<String, BigDecimal> rewards, BigDecimal defaultReward) {
        public BigDecimal reward(String key) { return rewards.getOrDefault(key, BigDecimal.ZERO); }
        public boolean worldAllowed(String world) {
            return !disabledWorlds.contains(world) && (worlds.isEmpty() || worlds.contains(world));
        }
    }

    public record Notifications(boolean actionBar, String actionBarFormat, boolean chat, String chatFormat,
                                boolean sound, String soundKey, float volume, float pitch, long unavailableCooldownMs) {}

    public record Mining(boolean rewardGeneratedBlocks) {}

    public record Farming(boolean allowFertilized, int stemScanLimit) {}

    /**
     * Origin groups (spawn reasons compared by name so new Paper reasons do not break loading):
     * spawner = SPAWNER, TRIAL_SPAWNER, Paper's Entity#fromMobSpawner(), ExoSpawners-managed mobs;
     * bred = BREEDING, EGG, DUPLICATION; summoned = SPAWNER_EGG, DISPENSE_EGG, BUCKET, COMMAND, CUSTOM, BUILD_*;
     * unknown = no spawn reason recorded (spawned before ExoEconomy tracked it, or by a plugin bypassing events).
     */
    public record Slaying(boolean allowSpawner, boolean allowBred, boolean allowSummoned, boolean allowUnknown,
                          boolean rewardPlayers, Set<String> blockedReasons, boolean exoSpawnersStackKills) {
        public static final Set<String> SPAWNER = Set.of("SPAWNER", "TRIAL_SPAWNER");
        public static final Set<String> BRED = Set.of("BREEDING", "EGG", "DUPLICATION");
        public static final Set<String> SUMMONED_EXACT = Set.of("SPAWNER_EGG", "DISPENSE_EGG", "BUCKET", "COMMAND", "CUSTOM");

        public boolean allows(String reason, boolean fromSpawner) {
            if (fromSpawner || SPAWNER.contains(reason)) return allowSpawner;
            if (reason == null) return allowUnknown;
            if (blockedReasons.contains(reason)) return false;
            if (BRED.contains(reason)) return allowBred;
            if (SUMMONED_EXACT.contains(reason) || reason.startsWith("BUILD_")) return allowSummoned;
            return true;
        }
    }

    public Activity activity(String id) { return activities.get(id); }

    static RewardSettings load(ConfigurationSection root, List<String> problems, List<String> warnings) {
        ConfigurationSection defaults = section(root, "defaults");
        Set<GameMode> defaultModes = modes(defaults.getStringList("game-modes"), "defaults.game-modes", problems);
        Set<String> defaultDisabled = Set.copyOf(defaults.getStringList("disabled-worlds"));
        BigDecimal totalCap = amount(defaults.get("daily-cap", 0), "defaults.daily-cap", problems);

        Map<String, Activity> activities = new LinkedHashMap<>();
        ConfigurationSection acts = section(root, "activities");
        for (String id : acts.getKeys(false)) {
            if (!id.matches("[a-z0-9_\\-]{1,32}")) { problems.add("rewards.yml activities." + id + ": ids must be lowercase letters, digits, _ or -"); continue; }
            ConfigurationSection a = acts.getConfigurationSection(id);
            if (a == null) continue;
            Set<GameMode> modes = a.contains("game-modes") ? modes(a.getStringList("game-modes"), "activities." + id + ".game-modes", problems) : defaultModes;
            Set<String> disabled = new HashSet<>(defaultDisabled);
            disabled.addAll(a.getStringList("disabled-worlds"));
            Map<String, BigDecimal> rewards = new LinkedHashMap<>();
            ConfigurationSection r = a.getConfigurationSection("rewards");
            if (r != null) for (String key : r.getKeys(false)) {
                String norm = key.toUpperCase(Locale.ROOT);
                if (!validKey(id, norm)) { warnings.add("rewards.yml activities." + id + ".rewards." + key + " is not a valid " + (SLAYING.equals(id) ? "entity type" : "material") + " on this server; ignored"); continue; }
                BigDecimal v = amount(r.get(key), "activities." + id + ".rewards." + key, problems);
                if (v.signum() > 0) rewards.put(norm, v);
            }
            activities.put(id, new Activity(id, a.getBoolean("enabled", true), a.getString("permission", "exoeconomy.earn." + id),
                    Set.copyOf(a.getStringList("worlds")), Set.copyOf(disabled), modes,
                    amount(a.get("daily-cap", 0), "activities." + id + ".daily-cap", problems), Map.copyOf(rewards),
                    amount(a.get("default-reward", 0), "activities." + id + ".default-reward", problems)));
        }
        for (String required : List.of(MINING, FARMING, SLAYING))
            if (!activities.containsKey(required)) warnings.add("rewards.yml has no activities." + required + " section; that activity pays nothing");

        ConfigurationSection n = section(root, "notifications");
        int flush = n.getInt("flush-interval-ticks", 40);
        if (flush < 1 || flush > 1200) problems.add("notifications.flush-interval-ticks must be 1..1200");
        Notifications notifications = new Notifications(n.getBoolean("action-bar.enabled", true),
                n.getString("action-bar.format", "&a+%amount% &7(%activities%)"), n.getBoolean("chat.enabled", false),
                n.getString("chat.format", "%prefix%&a+%amount% &7(%activities%)"), n.getBoolean("sound.enabled", true),
                n.getString("sound.key", "entity.experience_orb.pickup"), (float) n.getDouble("sound.volume", 0.4),
                (float) n.getDouble("sound.pitch", 1.6), n.getLong("unavailable-message-cooldown-seconds", 300) * 1000);

        ConfigurationSection m = acts.getConfigurationSection(MINING);
        ConfigurationSection f = acts.getConfigurationSection(FARMING);
        ConfigurationSection s = acts.getConfigurationSection(SLAYING);
        Mining mining = new Mining(m == null || m.getBoolean("reward-generated-blocks", true));
        Farming farming = new Farming(f == null || f.getBoolean("allow-fertilized", true), f == null ? 32 : Math.max(1, Math.min(384, f.getInt("stem-scan-limit", 32))));
        Slaying slaying = s == null ? new Slaying(false, false, false, false, false, Set.of(), false)
                : new Slaying(s.getBoolean("origins.spawner", false), s.getBoolean("origins.bred", false),
                s.getBoolean("origins.summoned", false), s.getBoolean("origins.unknown", false), s.getBoolean("reward-players", false),
                Set.copyOf(s.getStringList("blocked-spawn-reasons").stream().map(x -> x.toUpperCase(Locale.ROOT)).toList()),
                s.getBoolean("exospawners-stack-kills", false));
        return new RewardSettings(Map.copyOf(activities), totalCap, flush, notifications, mining, farming, slaying);
    }

    private static boolean validKey(String activity, String key) {
        try {
            if (SLAYING.equals(activity)) { EntityType.valueOf(key); return true; }
            if (MINING.equals(activity) || FARMING.equals(activity)) return Material.getMaterial(key) != null;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection s = root.getConfigurationSection(path);
        return s != null ? s : root.createSection(path);
    }

    static Set<GameMode> modes(List<String> names, String path, List<String> problems) {
        Set<GameMode> out = new HashSet<>();
        for (String n : names) {
            try { out.add(GameMode.valueOf(n.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { problems.add(path + ": unknown game mode " + n); }
        }
        return out;
    }

    static BigDecimal amount(Object raw, String path, List<String> problems) {
        try {
            return Money.fromConfig(raw, path);
        } catch (IllegalArgumentException e) {
            problems.add(path + ": " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
