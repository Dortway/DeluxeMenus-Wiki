package dev.exodaily.core;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.ConfigIssue;
import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.config.ConfigManager;
import dev.exodaily.support.Fixtures;
import dev.exodaily.support.TestPlatform;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader(new TestPlatform());

    private ConfigLoader.Result loadWith(String file, String search, String replacement) {
        Map<String, String> files = new HashMap<>(Fixtures.bundledFiles());
        String original = files.get(file);
        assertTrue(original.contains(search), "fixture text not found: " + search);
        files.put(file, original.replace(search, replacement));
        return loader.load(files, Fixtures.bundledFiles());
    }

    private static void assertIssue(ConfigLoader.Result result, String file, String pathFragment, String messageFragment) {
        assertFalse(result.success(), "expected the configuration to be rejected");
        List<ConfigIssue> errors = result.errors();
        assertTrue(errors.stream().anyMatch(issue -> issue.file().equals(file)
                        && issue.path().contains(pathFragment) && issue.message().contains(messageFragment)),
                "no error in " + file + " at " + pathFragment + " containing '" + messageFragment + "'; got " + errors);
    }

    @Test
    void bundledConfigurationIsValidWithoutWarnings() {
        ConfigLoader.Result result = loader.load(Fixtures.bundledFiles(), Fixtures.bundledFiles());
        assertTrue(result.success(), () -> "errors: " + result.errors());
        assertEquals(List.of(), result.warnings());
        ConfigBundle bundle = result.bundle();
        assertEquals("Europe/London", bundle.settings().timezone().getId());
        assertEquals(30, bundle.settings().cycleLength());
        assertEquals(13, bundle.menus().main().items().get("today").slot());
        assertEquals(27, bundle.menus().main().frame().size());
    }

    @Test
    void reportsInvalidMaterial() {
        assertIssue(loadWith(ConfigLoader.REWARDS, "material: COAL, amount: 32", "material: COALZ, amount: 32"),
                ConfigLoader.REWARDS, "rewards.coal_32.material", "unknown material 'COALZ'");
    }

    @Test
    void reportsInvalidAmount() {
        assertIssue(loadWith(ConfigLoader.REWARDS, "material: COAL, amount: 32", "material: COAL, amount: 0"),
                ConfigLoader.REWARDS, "rewards.coal_32.amount", "between 1 and");
        assertIssue(loadWith(ConfigLoader.REWARDS, "material: COAL, amount: 32", "material: COAL, amount: lots"),
                ConfigLoader.REWARDS, "rewards.coal_32.amount", "whole number");
    }

    @Test
    void reportsInvalidWeight() {
        assertIssue(loadWith(ConfigLoader.REWARDS, "summary: \"32 coal\", weight: 30", "summary: \"32 coal\", weight: -5"),
                ConfigLoader.REWARDS, "rewards.coal_32.weight", "between 1 and");
        assertIssue(loadWith(ConfigLoader.REWARDS, "{ reward: enchanted_apple_1, weight: 5 }", "{ reward: enchanted_apple_1, weight: 0 }"),
                ConfigLoader.REWARDS, "pools.milestone_premium.entries[5].weight", "weight must be");
    }

    @Test
    void reportsInvalidEnchantmentAndFlag() {
        assertIssue(loadWith(ConfigLoader.REWARDS, "      efficiency: 3\n", "      speedy: 3\n"),
                ConfigLoader.REWARDS, "rewards.milestone_pickaxe.enchantments.speedy", "unknown enchantment");
        assertIssue(loadWith(ConfigLoader.REWARDS, "flags: [HIDE_ENCHANTS]", "flags: [HIDE_EVERYTHING]"),
                ConfigLoader.REWARDS, "rewards.cycle_crown.flags", "unknown item flag");
    }

    @Test
    void reportsEmptyPoolsAndUnknownReferences() {
        assertIssue(loadWith(ConfigLoader.REWARDS,
                        "entries: [netherite_ingot_2, diamonds_32, nether_star_1, cycle_crown]", "entries: []"),
                ConfigLoader.REWARDS, "pools.finale_premium.entries", "pool is empty");
        assertIssue(loadWith(ConfigLoader.REWARDS, "entries: [bread_16,", "entries: [bread_17,"),
                ConfigLoader.REWARDS, "pools.standard_food.entries[0]", "unknown reward 'bread_17'");
        assertIssue(loadWith(ConfigLoader.REWARDS, "    position-1: standard_common\n    position-2: premium_common",
                        "    position-1: nope\n    position-2: premium_common"),
                ConfigLoader.REWARDS, "schedule.default.position-1", "unknown pool 'nope'");
    }

    @Test
    void reportsDuplicateIds() {
        // Exact duplicate keys are rejected by the YAML parser with a line number...
        ConfigLoader.Result exact = loadWith(ConfigLoader.REWARDS, "  coal_32:", "  iron_16: { material: COAL }\n  coal_32:");
        assertIssue(exact, ConfigLoader.REWARDS, "", "duplicate key");
        // ...and ids differing only by case are rejected by validation.
        assertIssue(loadWith(ConfigLoader.REWARDS, "  coal_32:", "  Iron_16: { material: COAL }\n  coal_32:"),
                ConfigLoader.REWARDS, "rewards.Iron_16", "invalid reward id");
    }

    @Test
    void reportsMalformedYaml() {
        assertIssue(loadWith(ConfigLoader.MENUS, "main:\n", "main:\n  - [unclosed\n"), ConfigLoader.MENUS, "", "malformed YAML");
    }

    @Test
    void reportsConflictingMenuSlots() {
        assertIssue(loadWith(ConfigLoader.MENUS, "    instructions:\n      slot: 22", "    instructions:\n      slot: 13"),
                ConfigLoader.MENUS, "main.items", "slot 13 conflicts with");
        assertIssue(loadWith(ConfigLoader.MENUS, "1: { reward-slot: 11, button-slot: 20 }", "1: { reward-slot: 11, button-slot: 22 }"),
                ConfigLoader.MENUS, "details.positions", "slot 22 conflicts with");
        assertIssue(loadWith(ConfigLoader.MENUS, "      slot: 26\n      material: CLOCK\n      name: \"<#FCD34D><sym:hourglass> <sc>next reset</sc>\"\n      lore:\n        - \"<#F3F4F6>new rewards in <#FCD34D><reset>\"\n        - \"<#9CA3AF>days change",
                        "      slot: 27\n      material: CLOCK\n      name: \"<#FCD34D><sym:hourglass> <sc>next reset</sc>\"\n      lore:\n        - \"<#F3F4F6>new rewards in <#FCD34D><reset>\"\n        - \"<#9CA3AF>days change"),
                ConfigLoader.MENUS, "main.items.countdown", "outside the main menu");
    }

    @Test
    void reportsBadSettings() {
        assertIssue(loadWith(ConfigLoader.CONFIG, "timezone: \"Europe/London\"", "timezone: \"Mars/Olympus\""),
                ConfigLoader.CONFIG, "timezone", "unknown timezone");
        assertIssue(loadWith(ConfigLoader.CONFIG, "cycle-length: 30", "cycle-length: 31"),
                ConfigLoader.MENUS, "overview.day-slots", "cycle-length");
        assertIssue(loadWith(ConfigLoader.CONFIG, "type: sqlite", "type: mysql"),
                ConfigLoader.CONFIG, "storage.type", "only 'sqlite'");
    }

    @Test
    void missingMessageFallsBackToDefaultWithWarning() {
        ConfigLoader.Result result = loadWith(ConfigLoader.MESSAGES,
                "claim-already: \"<prefix><#9CA3AF>you have already claimed that reward today.\"\n", "");
        assertTrue(result.success());
        assertTrue(result.warnings().stream().anyMatch(issue -> issue.path().equals("claim-already")));
        assertTrue(result.bundle().messages().get("claim-already").contains("already claimed"));
    }

    @Test
    void failedReloadKeepsThePreviousValidConfiguration() {
        ConfigManager manager = new ConfigManager(loader);
        ConfigLoader.Result first = manager.reload(Fixtures.bundledFiles(), Fixtures.bundledFiles());
        assertTrue(first.success());
        ConfigBundle valid = manager.get();

        Map<String, String> broken = new HashMap<>(Fixtures.bundledFiles());
        broken.put(ConfigLoader.REWARDS, broken.get(ConfigLoader.REWARDS).replace("material: COAL,", "material: NOT_REAL,"));
        ConfigLoader.Result second = manager.reload(broken, Fixtures.bundledFiles());
        assertFalse(second.success());
        assertSame(valid, manager.get());

        Map<String, String> missing = new HashMap<>(Fixtures.bundledFiles());
        missing.put(ConfigLoader.MENUS, null);
        assertFalse(manager.reload(missing, Fixtures.bundledFiles()).success());
        assertSame(valid, manager.get());

        Map<String, String> changed = new HashMap<>(Fixtures.bundledFiles());
        changed.put(ConfigLoader.CONFIG, changed.get(ConfigLoader.CONFIG).replace("Europe/London", "America/New_York"));
        assertTrue(manager.reload(changed, Fixtures.bundledFiles()).success());
        assertNotNull(manager.get());
        assertEquals("America/New_York", manager.get().settings().timezone().getId());
    }

    private ConfigLoader.Result withReward(String yaml) {
        return loadWith(ConfigLoader.REWARDS, "  iron_16:", yaml + "\n  iron_16:");
    }

    @Test
    void parsesCommandRewards() {
        ConfigLoader.Result result = withReward("""
                  money_500:
                    type: command
                    material: GOLD_NUGGET
                    summary: "$500"
                    commands: ["/eco give {player} 500", "log {claim_id} {uuid} {cycle} {day} {position} {reward}"]
                  crate_key:
                    type: both
                    material: TRIPWIRE_HOOK
                    summary: "1 crate key"
                    commands: ["say {player}"]""".indent(2).stripTrailing());
        assertTrue(result.success(), () -> result.errors().toString());
        var money = result.bundle().rewards().rewards().get("money_500");
        assertEquals(dev.exodaily.core.reward.RewardType.COMMAND, money.type());
        assertEquals(List.of("eco give {player} 500", "log {claim_id} {uuid} {cycle} {day} {position} {reward}"),
                money.commands());
        assertEquals(dev.exodaily.core.reward.RewardType.BOTH, result.bundle().rewards().rewards().get("crate_key").type());
        assertEquals(dev.exodaily.core.reward.RewardType.ITEM, result.bundle().rewards().rewards().get("iron_16").type());
    }

    @Test
    void rejectsMalformedCommandRewards() {
        assertIssue(withReward("  m1: { type: command, material: GOLD_NUGGET, summary: \"$1\" }"),
                ConfigLoader.REWARDS, "rewards.m1.commands", "needs at least one command");
        assertIssue(withReward("  m2: { type: command, material: GOLD_NUGGET, commands: [\"say hi\"] }"),
                ConfigLoader.REWARDS, "rewards.m2.summary", "need a summary");
        assertIssue(withReward("  m3: { material: GOLD_NUGGET, commands: [\"say hi\"] }"),
                ConfigLoader.REWARDS, "rewards.m3.commands", "only run for type");
        assertIssue(withReward("  m4: { type: command, material: GOLD_NUGGET, summary: x, commands: [\"say {nope}\"] }"),
                ConfigLoader.REWARDS, "rewards.m4.commands[0]", "unknown placeholder {nope}");
        assertIssue(withReward("  m5: { type: money, material: GOLD_NUGGET, summary: x, commands: [\"say hi\"] }"),
                ConfigLoader.REWARDS, "rewards.m5.type", "unknown type 'money'");
        assertIssue(withReward("  m6: { type: command, material: GOLD_NUGGET, summary: x, commands: [\"/\"] }"),
                ConfigLoader.REWARDS, "rewards.m6.commands[0]", "command is empty");
    }
}
