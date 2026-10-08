package dev.exodaily.core;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardSelector;
import dev.exodaily.core.reward.RewardSnapshot;
import dev.exodaily.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardSelectorTest {

    @Test
    void weightsControlSelectionFrequency() {
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        rewards.put("common", Fixtures.reward("common", "COAL", 1, 90));
        rewards.put("rare", Fixtures.reward("rare", "DIAMOND", 1, 10));
        RewardPool pool = new RewardPool("p", "", List.of(new PoolEntry("common", 90), new PoolEntry("rare", 10)), null);
        DaySchedule schedule = new DaySchedule(Map.of(RewardPosition.STANDARD, "p",
                RewardPosition.PREMIUM_ONE, "p", RewardPosition.PREMIUM_TWO, "p"));
        RewardCatalog catalog = new RewardCatalog(rewards, Map.of("p", pool), schedule, Map.of(), false, null, 4, Set.of());
        SplittableRandom random = new SplittableRandom(42);
        int rare = 0;
        int draws = 30_000;
        for (int i = 0; i < draws / 3; i++) {
            for (RewardSelector.Selection selection : RewardSelector.select(catalog, 1, random).values()) {
                if (selection.reward().id().equals("rare")) {
                    rare++;
                }
            }
        }
        double share = rare / (double) draws;
        assertTrue(share > 0.08 && share < 0.12, "rare share was " + share);
    }

    @Test
    void preventsDuplicatesWithinOneDay() {
        RewardCatalog catalog = Fixtures.smallCatalog(true);
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 2_000; i++) {
            Map<RewardPosition, RewardSelector.Selection> day = RewardSelector.select(catalog, 1, random);
            Set<String> ids = new HashSet<>();
            day.values().forEach(selection -> ids.add(selection.reward().id()));
            assertEquals(3, ids.size());
        }
    }

    @Test
    void duplicatesAreAllowedWhenPreventionIsOff() {
        RewardCatalog catalog = Fixtures.smallCatalog(false);
        SplittableRandom random = new SplittableRandom(11);
        boolean sawDuplicate = false;
        for (int i = 0; i < 2_000 && !sawDuplicate; i++) {
            Map<RewardPosition, RewardSelector.Selection> day = RewardSelector.select(catalog, 1, random);
            sawDuplicate = day.get(RewardPosition.PREMIUM_ONE).reward().id()
                    .equals(day.get(RewardPosition.PREMIUM_TWO).reward().id());
        }
        assertTrue(sawDuplicate);
    }

    @Test
    void fallsBackWhenAPoolRunsOutOfUniqueEntries() {
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        rewards.put("only", Fixtures.reward("only", "DIAMOND", 1, 10));
        rewards.put("backup", Fixtures.reward("backup", "COAL", 1, 10));
        rewards.put("last", Fixtures.reward("last", "IRON_INGOT", 1, 10));
        Map<String, RewardPool> pools = new HashMap<>();
        pools.put("tiny", new RewardPool("tiny", "", List.of(new PoolEntry("only", 1)), "backup_pool"));
        pools.put("backup_pool", new RewardPool("backup_pool", "", List.of(new PoolEntry("backup", 1)), null));
        pools.put("global", new RewardPool("global", "", List.of(new PoolEntry("last", 1)), null));
        DaySchedule schedule = new DaySchedule(Map.of(RewardPosition.STANDARD, "tiny",
                RewardPosition.PREMIUM_ONE, "tiny", RewardPosition.PREMIUM_TWO, "tiny"));
        RewardCatalog catalog = new RewardCatalog(rewards, pools, schedule, Map.of(), true, "global", 4, Set.of());

        Map<RewardPosition, RewardSelector.Selection> day = RewardSelector.select(catalog, 1, new SplittableRandom(1));
        assertEquals("only", day.get(RewardPosition.STANDARD).reward().id());
        assertFalse(day.get(RewardPosition.STANDARD).fallbackUsed());
        assertEquals("backup", day.get(RewardPosition.PREMIUM_ONE).reward().id());
        assertTrue(day.get(RewardPosition.PREMIUM_ONE).fallbackUsed());
        assertEquals("last", day.get(RewardPosition.PREMIUM_TWO).reward().id());
        assertTrue(day.get(RewardPosition.PREMIUM_TWO).fallbackUsed());
    }

    @Test
    void allowsADuplicateOnlyWhenEveryPoolIsExhausted() {
        Map<String, RewardDefinition> rewards = Map.of("only", Fixtures.reward("only", "DIAMOND", 1, 10));
        RewardPool pool = new RewardPool("tiny", "", List.of(new PoolEntry("only", 1)), null);
        DaySchedule schedule = new DaySchedule(Map.of(RewardPosition.STANDARD, "tiny",
                RewardPosition.PREMIUM_ONE, "tiny", RewardPosition.PREMIUM_TWO, "tiny"));
        RewardCatalog catalog = new RewardCatalog(rewards, Map.of("tiny", pool), schedule, Map.of(), true, null, 4, Set.of());
        Map<RewardPosition, RewardSelector.Selection> day = RewardSelector.select(catalog, 1, new SplittableRandom(1));
        assertEquals("only", day.get(RewardPosition.PREMIUM_TWO).reward().id());
        assertTrue(day.get(RewardPosition.PREMIUM_TWO).duplicateAllowed());
    }

    @Test
    void milestoneDaysUseTheirOverridePools() {
        ConfigBundle bundle = Fixtures.bundledConfig();
        RewardCatalog catalog = bundle.rewards();
        Set<String> milestoneIds = new HashSet<>();
        catalog.pools().get("milestone_premium").entries().forEach(entry -> milestoneIds.add(entry.rewardId()));
        Set<String> finaleIds = new HashSet<>();
        catalog.pools().get("finale_standard").entries().forEach(entry -> finaleIds.add(entry.rewardId()));
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 200; i++) {
            assertTrue(milestoneIds.contains(RewardSelector.select(catalog, 7, random).get(RewardPosition.PREMIUM_ONE).reward().id()));
            assertTrue(finaleIds.contains(RewardSelector.select(catalog, 30, random).get(RewardPosition.STANDARD).reward().id()));
        }
        assertEquals("standard_common", catalog.scheduleFor(1).poolFor(RewardPosition.STANDARD));
        assertEquals("milestone_standard", catalog.scheduleFor(14).poolFor(RewardPosition.STANDARD));
    }

    @Test
    void bundledScheduleCoversEveryDayOfTheCycle() {
        ConfigBundle bundle = Fixtures.bundledConfig();
        for (int day = 1; day <= bundle.settings().cycleLength(); day++) {
            assertTrue(bundle.rewards().days().containsKey(day), "day " + day + " missing from schedule.days");
            SplittableRandom random = new SplittableRandom(day);
            Map<RewardPosition, RewardSelector.Selection> selection = RewardSelector.select(bundle.rewards(), day, random);
            assertEquals(3, selection.size());
            assertFalse(selection.values().stream().anyMatch(RewardSelector.Selection::duplicateAllowed));
        }
    }

    @Test
    void snapshotsRoundTripEveryField() {
        RewardDefinition reward = new RewardDefinition("pick", "DIAMOND_PICKAXE", 1, "<#67E8F9>pick",
                List.of("<gray>line"), Map.of("efficiency", 3), List.of("HIDE_ENCHANTS"), 1001f, "pickaxe", 15, null);
        assertEquals(reward, RewardSnapshot.decode(RewardSnapshot.encode(reward)));
    }

    @Test
    void commandSnapshotsRoundTripAndOldSnapshotsReadAsItems() {
        RewardDefinition command = new RewardDefinition("money", "GOLD_NUGGET", 1, null, List.of(), Map.of(), List.of(),
                null, "$500", 10, null, dev.exodaily.core.reward.RewardType.COMMAND, List.of("eco give {player} 500"));
        assertEquals(command, RewardSnapshot.decode(RewardSnapshot.encode(command)));
        // A snapshot written before command rewards existed has neither field.
        String legacy = "{\"version\":1,\"reward\":{\"id\":\"coal\",\"material\":\"COAL\",\"amount\":32,"
                + "\"lore\":[],\"enchantments\":{},\"flags\":[],\"summary\":\"32 coal\",\"weight\":10}}";
        RewardDefinition decoded = RewardSnapshot.decode(legacy);
        assertEquals(dev.exodaily.core.reward.RewardType.ITEM, decoded.type());
        assertTrue(decoded.commands().isEmpty());
    }
}
