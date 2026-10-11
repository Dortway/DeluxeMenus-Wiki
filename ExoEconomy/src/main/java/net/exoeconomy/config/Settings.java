package net.exoeconomy.config;

import net.exoeconomy.integration.CubeHarvestBridge;
import net.exoeconomy.sell.PriceCatalogue;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Immutable snapshot of every configuration file. Reloading builds a new snapshot and swaps it in
 * only if it is valid; a menu or transaction keeps the values it captured when it was created.
 */
public record Settings(int generation, String currencyId, String scale, String storageFile, int storageQueue,
                       boolean shardsCommand, String shardsCommandName, List<String> shardsAliases,
                       boolean exoSkills, boolean exoSpawners, boolean placeholderApi,
                       CubeHarvestBridge.Rule exoCubes, CubeHarvestBridge.Rule exoWoodCube,
                       SellSettings selling, RewardSettings rewards, BoosterSettings boosters,
                       PriceCatalogue catalogue, Messages messages, List<String> warnings) {

    public static final int CONFIG_VERSION = 1;

    /**
     * @param resources reads a bundled default file (for message fallbacks)
     */
    public static Settings load(File folder, int generation, Function<String, InputStream> resources) throws ConfigException {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        YamlConfiguration config = read(new File(folder, "config.yml"), problems);
        YamlConfiguration rewards = read(new File(folder, "rewards.yml"), problems);
        YamlConfiguration boosters = read(new File(folder, "boosters.yml"), problems);
        YamlConfiguration prices = read(new File(folder, "prices.yml"), problems);
        YamlConfiguration messages = read(new File(folder, "messages.yml"), problems);
        if (!problems.isEmpty()) throw new ConfigException(problems);

        int version = config.getInt("config-version", 0);
        if (version != CONFIG_VERSION) warnings.add("config.yml config-version is " + version + ", expected " + CONFIG_VERSION + "; compare with the bundled default for new options");
        String currency = config.getString("economy.currency-id", "");
        if (currency.isBlank()) problems.add("config.yml economy.currency-id must name your ExcellentEconomy Shards currency");
        String scale = String.valueOf(config.get("economy.scale", "auto"));
        if (!scale.equalsIgnoreCase("auto") && !scale.matches("[0-4]")) problems.add("config.yml economy.scale must be auto or 0..4");
        int queue = config.getInt("storage.write-queue-capacity", 4096);
        if (queue < 256 || queue > 1_000_000) problems.add("storage.write-queue-capacity must be 256..1000000");
        String shardsName = config.getString("commands.shards.name", "shards");
        if (!shardsName.matches("[a-z0-9_\\-]{1,32}")) problems.add("commands.shards.name is not a valid command name");

        SellSettings selling = SellSettings.load(RewardSettings.section(config, "selling"), problems);
        RewardSettings rewardSettings = RewardSettings.load(rewards, problems, warnings);
        BoosterSettings boosterSettings = BoosterSettings.load(boosters, selling.boostersEnabled(), problems, warnings);
        PriceCatalogue catalogue = PriceCatalogue.load(prices, problems, warnings);
        CubeHarvestBridge.Rule exoCubes = cubeRule(config, "exocubes", RewardSettings.MINING, rewardSettings, problems, warnings);
        CubeHarvestBridge.Rule exoWoodCube = cubeRule(config, "exowoodcube", RewardSettings.WOODCUTTING, rewardSettings, problems, warnings);
        Messages msgs;
        try (InputStream in = resources.apply("messages.yml")) {
            YamlConfiguration defaults = new YamlConfiguration();
            if (in != null) defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            msgs = new Messages(messages, defaults);
        } catch (IOException | InvalidConfigurationException e) {
            problems.add("bundled messages.yml unreadable: " + e.getMessage());
            msgs = null;
        }
        if (!problems.isEmpty()) throw new ConfigException(problems);
        return new Settings(generation, currency, scale, config.getString("storage.file", "exoeconomy.db"), queue,
                config.getBoolean("commands.shards.enabled", false), shardsName, config.getStringList("commands.shards.aliases"),
                config.getBoolean("integrations.exoskills", true), config.getBoolean("integrations.exospawners", true),
                config.getBoolean("integrations.placeholderapi", true), exoCubes, exoWoodCube, selling, rewardSettings, boosterSettings, catalogue,
                msgs, List.copyOf(warnings));
    }

    /** integrations.&lt;key&gt;: enabled, activity, reward-multiplier. */
    private static CubeHarvestBridge.Rule cubeRule(YamlConfiguration config, String key, String defaultActivity,
                                                   RewardSettings rewards, List<String> problems, List<String> warnings) {
        String path = "integrations." + key;
        String activity = config.getString(path + ".activity", defaultActivity).toLowerCase(java.util.Locale.ROOT);
        java.math.BigDecimal multiplier = RewardSettings.amount(config.get(path + ".reward-multiplier", 1.0), path + ".reward-multiplier", problems);
        if (multiplier.compareTo(java.math.BigDecimal.valueOf(100)) > 0) problems.add(path + ".reward-multiplier must be 0..100");
        boolean enabled = config.getBoolean(path + ".enabled", true);
        if (enabled && rewards.activity(activity) == null)
            warnings.add(path + ".activity '" + activity + "' has no section in rewards.yml; cube harvests pay nothing");
        return new CubeHarvestBridge.Rule(enabled, activity, multiplier);
    }

    private static YamlConfiguration read(File file, List<String> problems) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException e) {
            problems.add(file.getName() + ": cannot read (" + e.getMessage() + ")");
        } catch (InvalidConfigurationException e) {
            problems.add(file.getName() + ": invalid YAML (" + e.getMessage().lines().findFirst().orElse("") + ")");
        }
        return yaml;
    }
}
