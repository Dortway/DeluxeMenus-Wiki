package dev.exodaily.core;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardSelector;
import dev.exodaily.core.reward.RewardType;
import dev.exodaily.support.Fixtures;
import dev.exodaily.support.TestPlatform;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reward list shipped in rewards.yml. */
class ShippedRewardsTest {

    private static final Set<String> PREMIUM_ONLY = Set.of("lunar_box_1", "lunar_box_5", "lunar_box_10", "lunar_box_30",
            "premium_omega_key_5", "premium_vote_key_10", "premium_epic_key_6", "lantern_box_10", "lantern_box_15",
            "mystic_box_10", "mystic_box_15", "money_5m", "money_10m");

    private final ConfigBundle bundle = Fixtures.shippedConfig();
    private final RewardCatalog catalog = bundle.rewards();

    @Test
    void shippedConfigurationLoadsWithoutWarnings() {
        ConfigLoader.Result result = new ConfigLoader(new TestPlatform()).load(Fixtures.shippedFiles(), Fixtures.shippedFiles());
        assertTrue(result.success(), () -> result.errors().toString());
        assertEquals(List.of(), result.warnings());
        assertEquals(40, catalog.rewards().size());
    }

    @Test
    void everyRewardIsACommandRewardInAPool() {
        Set<String> pooled = new HashSet<>();
        catalog.pools().values().forEach(pool -> pool.entries().forEach(entry -> pooled.add(entry.rewardId())));
        for (RewardDefinition reward : catalog.rewards().values()) {
            assertEquals(RewardType.COMMAND, reward.type(), reward.id());
            assertEquals(1, reward.commands().size(), reward.id());
            assertTrue(pooled.contains(reward.id()), reward.id() + " is not in any pool");
        }
    }

    @Test
    void commandsMatchTheRequestedIntegrations() {
        Map<String, String> expected = Map.of(
                "lantern_box_1", "ebox give {player} lantern 1",
                "mystic_box_1", "ebox give {player} mystic 1",
                "lunar_box_1", "ebox give {player} lunar 1",
                "epic_key_1", "crate key give {player} epic 1",
                "omega_key_1", "crate key give {player} omega 1",
                "prime_key_8", "crate key give {player} prime 8",
                "rare_key_1", "crate key give {player} rare 1",
                "premium_vote_key_10", "crate key give {player} vote 10",
                "money_10m", "eco give {player} 10000000",
                "xp_1k", "xp add {player} 1000 points");
        expected.forEach((id, command) -> assertEquals(List.of(command), catalog.rewards().get(id).commands(), id));
    }

    @Test
    void premiumOnlyRewardsAreNeverReachableFromTheStandardPosition() {
        for (int day = 1; day <= bundle.settings().cycleLength(); day++) {
            DaySchedule schedule = catalog.scheduleFor(day);
            Set<String> visited = new HashSet<>();
            String pool = schedule.poolFor(RewardPosition.STANDARD);
            Set<String> reachable = new HashSet<>();
            while (pool != null && visited.add(pool)) {
                catalog.pools().get(pool).entries().stream().map(PoolEntry::rewardId).forEach(reachable::add);
                pool = catalog.pools().get(pool).fallback();
            }
            catalog.pools().get(catalog.fallbackPool()).entries().stream().map(PoolEntry::rewardId).forEach(reachable::add);
            for (String id : reachable) {
                assertFalse(PREMIUM_ONLY.contains(id), "day " + day + ": premium-only " + id + " reachable from position 1");
            }
        }
    }

    @Test
    void premiumPositionsDrawPremiumOnlyRewardsAndDaysAreDistinct() {
        SplittableRandom random = new SplittableRandom(99);
        for (int day = 1; day <= 30; day++) {
            for (int i = 0; i < 200; i++) {
                Map<RewardPosition, RewardSelector.Selection> picks = RewardSelector.select(catalog, day, random);
                assertTrue(PREMIUM_ONLY.contains(picks.get(RewardPosition.PREMIUM_ONE).reward().id()));
                assertTrue(PREMIUM_ONLY.contains(picks.get(RewardPosition.PREMIUM_TWO).reward().id()));
                assertFalse(PREMIUM_ONLY.contains(picks.get(RewardPosition.STANDARD).reward().id()));
                assertEquals(3, picks.values().stream().map(s -> s.reward().id()).distinct().count());
            }
        }
    }
}
