package dev.exodaily.support;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.reward.RewardPosition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared test data. */
public final class Fixtures {

    private Fixtures() {
    }

    /** The configuration files shipped in the plugin JAR. */
    public static Map<String, String> bundledFiles() {
        Map<String, String> files = new HashMap<>();
        for (String file : ConfigLoader.FILES) {
            try (InputStream in = Fixtures.class.getResourceAsStream("/" + file)) {
                if (in == null) {
                    throw new IllegalStateException("missing bundled resource " + file);
                }
                files.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    public static ConfigBundle bundledConfig() {
        ConfigLoader.Result result = new ConfigLoader(new TestPlatform()).load(bundledFiles(), bundledFiles());
        if (!result.success()) {
            throw new IllegalStateException("bundled configuration is invalid: " + result.errors());
        }
        return result.bundle();
    }

    public static RewardDefinition reward(String id, String material, int amount, int weight) {
        return new RewardDefinition(id, material, amount, null, List.of(), Map.of(), List.of(), null,
                amount + " " + material.toLowerCase(), weight, null);
    }

    /** A small catalog: three standard rewards and three premium rewards. */
    public static RewardCatalog smallCatalog(boolean preventDuplicates) {
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        for (RewardDefinition reward : List.of(
                reward("iron", "IRON_INGOT", 16, 10),
                reward("coal", "COAL", 32, 10),
                reward("bread", "BREAD", 16, 10),
                reward("diamond", "DIAMOND", 4, 10),
                reward("emerald", "EMERALD", 16, 10),
                reward("apple", "GOLDEN_APPLE", 2, 10))) {
            rewards.put(reward.id(), reward);
        }
        Map<String, RewardPool> pools = new LinkedHashMap<>();
        pools.put("standard", new RewardPool("standard", "standard stuff",
                List.of(new PoolEntry("iron", 10), new PoolEntry("coal", 10), new PoolEntry("bread", 10)), null));
        pools.put("premium", new RewardPool("premium", "premium stuff",
                List.of(new PoolEntry("diamond", 10), new PoolEntry("emerald", 10), new PoolEntry("apple", 10)), "standard"));
        DaySchedule defaults = new DaySchedule(Map.of(
                RewardPosition.STANDARD, "standard",
                RewardPosition.PREMIUM_ONE, "premium",
                RewardPosition.PREMIUM_TWO, "premium"));
        return new RewardCatalog(rewards, pools, defaults, Map.of(), preventDuplicates, "standard", 4, Set.of(7));
    }
}
