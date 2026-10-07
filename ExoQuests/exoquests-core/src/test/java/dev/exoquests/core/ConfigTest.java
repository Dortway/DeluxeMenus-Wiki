package dev.exoquests.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.ConfigErrors;
import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.ConfigLoader;
import dev.exoquests.core.config.PlatformValidator;
import dev.exoquests.core.config.ShopLoader;
import dev.exoquests.core.config.YamlFiles;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.quest.QuestType;
import dev.exoquests.core.shop.CommandTemplate;
import dev.exoquests.core.shop.PriceRules;
import dev.exoquests.core.shop.ShopCatalog;
import dev.exoquests.core.shop.ShopEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @TempDir
    Path dir;

    /** id, type, keys, target, reward exactly as required. */
    private static final List<Object[]> REQUIRED = List.of(
            new Object[]{"mining_cobblestone", "BLOCK_BREAK", Set.of("COBBLESTONE"), 1000, 10},
            new Object[]{"mining_deepslate", "BLOCK_BREAK", Set.of("DEEPSLATE", "COBBLED_DEEPSLATE"), 700, 12},
            new Object[]{"mining_diamond_ore", "BLOCK_BREAK", Set.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"), 100, 19},
            new Object[]{"mining_iron_ore", "BLOCK_BREAK", Set.of("IRON_ORE", "DEEPSLATE_IRON_ORE"), 100, 13},
            new Object[]{"mining_gold_ore", "BLOCK_BREAK", Set.of("GOLD_ORE", "DEEPSLATE_GOLD_ORE"), 100, 14},
            new Object[]{"mining_coal_ore", "BLOCK_BREAK", Set.of("COAL_ORE", "DEEPSLATE_COAL_ORE"), 500, 9},
            new Object[]{"farming_potatoes", "CROP_HARVEST", Set.of("POTATOES"), 200, 12},
            new Object[]{"farming_wheat", "CROP_HARVEST", Set.of("WHEAT"), 500, 12},
            new Object[]{"farming_sugar_cane", "STACKED_PLANT_HARVEST", Set.of("SUGAR_CANE"), 300, 15},
            new Object[]{"farming_cactus", "STACKED_PLANT_HARVEST", Set.of("CACTUS"), 1000, 12},
            new Object[]{"farming_carrots", "CROP_HARVEST", Set.of("CARROTS"), 900, 12},
            new Object[]{"farming_melons", "BLOCK_BREAK", Set.of("MELON"), 2000, 12},
            new Object[]{"farming_bamboo", "STACKED_PLANT_HARVEST", Set.of("BAMBOO"), 300, 12},
            new Object[]{"breeding_chickens", "ENTITY_BREED", Set.of("CHICKEN"), 20, 20},
            new Object[]{"breeding_pigs", "ENTITY_BREED", Set.of("PIG"), 10, 20},
            new Object[]{"breeding_cows", "ENTITY_BREED", Set.of("COW"), 15, 20},
            new Object[]{"breeding_sheep", "ENTITY_BREED", Set.of("SHEEP"), 18, 20},
            new Object[]{"kills_sheep", "ENTITY_KILL", Set.of("SHEEP"), 100, 20},
            new Object[]{"kills_pigs", "ENTITY_KILL", Set.of("PIG"), 100, 20},
            new Object[]{"kills_cows", "ENTITY_KILL", Set.of("COW"), 100, 20},
            new Object[]{"kills_chickens", "ENTITY_KILL", Set.of("CHICKEN"), 100, 20},
            new Object[]{"trees_jungle", "TREE_GROW", Set.of("JUNGLE"), 10, 16},
            new Object[]{"trees_oak", "TREE_GROW", Set.of("OAK"), 10, 16},
            new Object[]{"trees_spruce", "TREE_GROW", Set.of("SPRUCE"), 10, 16},
            new Object[]{"trees_birch", "TREE_GROW", Set.of("BIRCH"), 10, 16},
            new Object[]{"trees_acacia", "TREE_GROW", Set.of("ACACIA"), 10, 16},
            new Object[]{"trees_dark_oak", "TREE_GROW", Set.of("DARK_OAK"), 10, 16},
            new Object[]{"logging_jungle", "BLOCK_BREAK", Set.of("JUNGLE_LOG"), 300, 19},
            new Object[]{"logging_oak", "BLOCK_BREAK", Set.of("OAK_LOG"), 200, 19},
            new Object[]{"logging_spruce", "BLOCK_BREAK", Set.of("SPRUCE_LOG"), 200, 19},
            new Object[]{"logging_birch", "BLOCK_BREAK", Set.of("BIRCH_LOG"), 200, 19},
            new Object[]{"logging_acacia", "BLOCK_BREAK", Set.of("ACACIA_LOG"), 200, 19},
            new Object[]{"logging_dark_oak", "BLOCK_BREAK", Set.of("DARK_OAK_LOG"), 300, 19});

    @Test
    void bundledConfigurationIsValid() throws Exception {
        ConfigBundle bundle = TestSupport.defaults(dir);
        assertNotNull(bundle.settings());
        assertEquals("Europe/London", bundle.settings().reset().zone().getId());
        assertEquals("00:00", bundle.settings().reset().resetTime().toString());
        assertTrue(bundle.warnings().isEmpty(), "unexpected warnings: " + bundle.warnings());
    }

    @Test
    void requiredQuestsHaveExactTargetsAndRewards() throws Exception {
        QuestPool pool = TestSupport.defaults(dir).quests();
        for (Object[] r : REQUIRED) {
            QuestDefinition d = pool.get((String) r[0]).orElseThrow(() -> new AssertionError("missing " + r[0]));
            assertEquals(QuestType.valueOf((String) r[1]), d.type(), (String) r[0]);
            assertEquals(r[2], d.keys(), (String) r[0]);
            assertEquals(r[3], d.target(), (String) r[0]);
            assertEquals(r[4], d.reward(), (String) r[0]);
            assertTrue(d.enabled());
            if (d.type().supportsNaturalOnly()) {
                assertTrue(d.naturalOnly(), d.id() + " must reject player-placed blocks");
            }
        }
    }

    @Test
    void atLeastTwelveExtraBalancedQuests() throws Exception {
        QuestPool pool = TestSupport.defaults(dir).quests();
        Set<String> required = new HashSet<>();
        REQUIRED.forEach(r -> required.add((String) r[0]));
        List<QuestDefinition> extra = pool.all().stream().filter(d -> !required.contains(d.id())).toList();
        assertTrue(extra.size() >= 12, "only " + extra.size() + " extra quests");
        for (QuestDefinition d : extra) {
            assertTrue(d.reward() >= 8 && d.reward() <= 25, d.id() + " reward " + d.reward());
        }
        Set<QuestType> extraTypes = new HashSet<>();
        extra.forEach(d -> extraTypes.add(d.type()));
        assertTrue(extraTypes.containsAll(Set.of(QuestType.FISH_CATCH, QuestType.CRAFT_ITEM, QuestType.SMELT_EXTRACT,
                QuestType.SHEAR_ENTITY, QuestType.COLLECT_LAID_EGGS, QuestType.ENTITY_KILL)));
    }

    @Test
    void weightedPickIsAlwaysThreeDistinctQuests() throws Exception {
        QuestPool pool = TestSupport.defaults(dir).quests();
        Random random = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            List<QuestDefinition> picked = pool.pick(random);
            assertEquals(3, picked.size());
            assertEquals(3, new HashSet<>(picked).size());
            picked.forEach(d -> assertTrue(d.enabled()));
        }
    }

    @Test
    void bundledShopMatchesRequiredExamples() throws Exception {
        ShopCatalog shop = TestSupport.defaults(dir).shop();
        Map<String, Integer> prices = Map.of("torches", 10, "oak_logs", 25, "iron_ingots", 60, "diamonds", 120,
                "efficiency_book", 300, "quest_pickaxe", 1000);
        prices.forEach((id, price) -> assertEquals(price, shop.get(id).orElseThrow().price(), id));
        assertEquals(16, shop.get("torches").orElseThrow().item().amount());
        assertEquals(32, shop.get("oak_logs").orElseThrow().item().amount());
        assertEquals(Map.of("efficiency", 4), shop.get("efficiency_book").orElseThrow().item().storedEnchantments());
        assertEquals(Map.of("efficiency", 5, "unbreaking", 3),
                shop.get("quest_pickaxe").orElseThrow().item().enchantments());
        assertFalse(shop.get("island_fly_hour").orElseThrow().enabled());
    }

    private ShopCatalog parseShop(String yaml, ConfigErrors errors) {
        return ShopLoader.load(YamlFiles.parse("shop.yml", yaml, errors), PlatformValidator.PERMISSIVE);
    }

    @Test
    void invalidPricesAreRejected() {
        for (String price : List.of("9", "1001", "0", "-50", "10.5", "\"100\"", "abc", "99999999999999999999")) {
            ConfigErrors errors = new ConfigErrors();
            ShopCatalog shop = parseShop("items:\n  x:\n    price: " + price + "\n    item: {material: STONE}\n", errors);
            assertNull(shop, "price " + price + " should be rejected");
            assertTrue(errors.hasProblems());
        }
        ConfigErrors ok = new ConfigErrors();
        assertNotNull(parseShop("items:\n  x:\n    price: 10\n    item: {material: STONE}\n"
                + "  y:\n    price: 1000\n    item: {material: STONE}\n", ok), ok.problems().toString());
    }

    @Test
    void priceParsingForCommands() {
        assertEquals(10, PriceRules.parse("10").orElseThrow());
        assertEquals(1000, PriceRules.parse("1000").orElseThrow());
        for (String bad : List.of("9", "1001", "-10", "+10", "10.0", "1e3", "1,000", "", " 10", "abc")) {
            assertTrue(PriceRules.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void duplicateIdsAreRejected() {
        ConfigErrors errors = new ConfigErrors();
        parseShop("items:\n  x:\n    price: 10\n    item: {material: STONE}\n  x:\n    price: 20\n    item: {material: DIRT}\n",
                errors);
        assertTrue(errors.hasProblems());
        assertTrue(errors.problems().get(0).contains("duplicate"), errors.problems().toString());
    }

    @Test
    void invalidMaterialsAreRejected() {
        PlatformValidator strict = new PlatformValidator() {
            @Override
            public boolean isItemMaterial(String name) {
                return Set.of("STONE", "DIAMOND").contains(name);
            }
        };
        ConfigErrors errors = new ConfigErrors();
        ShopLoader.load(YamlFiles.parse("shop.yml", "items:\n  x:\n    price: 10\n    item: {material: NOT_A_THING}\n",
                errors), strict);
        assertTrue(errors.problems().stream().anyMatch(p -> p.contains("NOT_A_THING")), errors.problems().toString());
    }

    @Test
    void commandRewardsOnlyAllowAllowlistedPlaceholders() {
        assertNull(CommandTemplate.validate("give {player} diamond 1"));
        assertNull(CommandTemplate.validate("/lp user {uuid} parent add vip"));
        assertNotNull(CommandTemplate.validate("say {message}"));
        assertNotNull(CommandTemplate.validate("say hi\nop {player}"));
        assertNotNull(CommandTemplate.validate("say {player"));
        assertNotNull(CommandTemplate.validate("  "));
        assertEquals("give Steve diamond 1", CommandTemplate.render("/give {player} diamond 1", Map.of("player", "Steve")));
        // Values are inserted verbatim and never re-expanded.
        assertEquals("say {uuid}", CommandTemplate.render("say {player}", Map.of("player", "{uuid}")));

        ConfigErrors errors = new ConfigErrors();
        parseShop("items:\n  c:\n    price: 50\n    type: command\n    icon: PAPER\n    name: x\n"
                + "    commands: [\"say {secret}\"]\n", errors);
        assertTrue(errors.hasProblems());
    }

    @Test
    void invalidQuestFieldsAreReported() throws Exception {
        TestSupport.copyDefaults(dir);
        String quests = Files.readString(dir.resolve("quests.yml"));
        Files.writeString(dir.resolve("quests.yml"), quests
                .replace("target: 1000\n    reward: 10", "target: -5\n    reward: 10")
                .replace("type: CROP_HARVEST", "type: TELEPORT"));
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(dir, PlatformValidator.PERMISSIVE));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("target")), ex.problems().toString());
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("TELEPORT")), ex.problems().toString());
    }

    @Test
    void malformedYamlFailsWithoutPartialResult() throws Exception {
        TestSupport.copyDefaults(dir);
        Files.writeString(dir.resolve("config.yml"), "reset: [unclosed");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(dir, PlatformValidator.PERMISSIVE));
    }

    @Test
    void overlappingMenuSlotsAreRejected() throws Exception {
        TestSupport.copyDefaults(dir);
        String menus = Files.readString(dir.resolve("menus.yml"));
        Files.writeString(dir.resolve("menus.yml"), menus.replace("quest-slots: [11, 13, 15]", "quest-slots: [11, 13, 22]"));
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(dir, PlatformValidator.PERMISSIVE));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("slot 22")), ex.problems().toString());
    }

    @Test
    void shopRoundTripsThroughYamlWriter() throws Exception {
        ShopCatalog shop = TestSupport.defaults(dir).shop();
        Path out = dir.resolve("shop-out.yml");
        YamlFiles.writeAtomically(out, ShopLoader.HEADER, ShopLoader.toYaml(shop));
        ShopCatalog reread = ConfigLoader.loadShop(out, PlatformValidator.PERMISSIVE);
        assertEquals(shop.all().size(), reread.all().size());
        for (ShopEntry e : shop.all()) {
            ShopEntry r = reread.get(e.id()).orElseThrow();
            assertEquals(e, r);
        }
    }
}
