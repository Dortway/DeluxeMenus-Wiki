package com.exoblacksmith.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.config.model.Ingredient;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.MaskDef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.config.model.RuneDef;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class DefaultConfigTest {
    static final Path RES = Path.of("src/main/resources");

    @BeforeAll
    static void server() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stop() {
        MockBukkit.unmock();
    }

    static ConfigLoader.Result load(Map<String, String> overrides) {
        ConfigLoader.Source source = file -> {
            if (overrides.containsKey(file)) {
                String content = overrides.get(file);
                return content == null ? null : new StringReader(content);
            }
            return Files.newBufferedReader(RES.resolve(file), StandardCharsets.UTF_8);
        };
        return ConfigLoader.load(source, file -> Files.newBufferedReader(RES.resolve(file), StandardCharsets.UTF_8));
    }

    static Registry defaults() {
        ConfigLoader.Result result = load(Map.of());
        assertTrue(result.ok(), () -> "default config must load: " + result.errors());
        return result.registry();
    }

    @Test
    void defaultsLoadWithoutErrors() {
        ConfigLoader.Result result = load(Map.of());
        assertEquals(List.of(), result.errors());
        assertEquals(12, result.registry().heads().size());
        assertEquals(12, result.registry().masks().size());
        assertEquals(9, result.registry().runes().size());
        assertEquals(12, result.registry().armorPieces().size());
        assertEquals(3, result.registry().sets().size());
    }

    @Test
    void everyRequiredItemHasAReachableRecipe() {
        Registry reg = defaults();
        Set<ItemRef> reachable = RecipeValidator.reachable(reg);
        for (ItemRef ref : RecipeValidator.requiredOutputs(reg)) {
            assertTrue(!reg.recipesProducing(ref).isEmpty(), "no recipe for " + ref.describe());
            assertTrue(reachable.contains(ref), "unreachable " + ref.describe());
        }
        // armor 12, masks 12, runes 9, upgrade materials 2, rune upgrades 18, totems 10, mask level-ups 15
        assertEquals(12 + 12 + 9 + 2 + 18 + 10 + 15, reg.recipes().size());
        for (Category c : Category.values()) {
            assertTrue(!reg.recipes(c).isEmpty(), c + " category is empty");
        }
    }

    @Test
    void runeRecipesFollowTheSpecification() {
        Registry reg = defaults();
        Set<Set<String>> combos = new HashSet<>();
        int[] outer = {0, 1, 2, 3, 5, 6, 7, 8};
        for (RuneDef rune : reg.runes()) {
            RecipeDef base = reg.recipesProducing(ItemRef.exo(rune.id(), 1)).getFirst();
            assertEquals(null, base.cell(4), "tier I center stays empty");
            Set<String> heads = new HashSet<>();
            for (int i : outer) {
                Ingredient ing = base.cell(i);
                assertEquals(15, ing.amount(), rune.id() + " slot " + i);
                assertNotNull(reg.head(ing.ref().exoId()), "outer slots are value heads");
                heads.add(ing.ref().exoId());
            }
            assertEquals(120, base.totals().values().stream().mapToInt(Integer::intValue).sum());
            assertTrue(combos.add(heads), "head combination for " + rune.id() + " must be distinct");

            for (int tier = 2; tier <= 3; tier++) {
                RecipeDef up = reg.recipesProducing(ItemRef.exo(rune.id(), tier)).getFirst();
                assertEquals(ItemRef.exo(rune.id(), tier - 1), up.cell(4).ref(), "previous rune in the center");
                assertEquals(1, up.cell(4).amount());
                String material = tier == 2 ? "legendary_rune_upgrade" : "fabled_rune_upgrade";
                assertEquals(120, up.totals().get(ItemRef.exo(material, 1)));
                for (int i : outer) {
                    assertEquals(15, up.cell(i).amount());
                }
            }
        }
    }

    @Test
    void runeValuesMatchTheSpecification() {
        Registry reg = defaults();
        Map<String, double[]> expected = Map.of(
                "blast_rune", new double[]{0.15, 0.20, 0.25}, "totem_surge", new double[]{2, 3, 4},
                "hardened_shell", new double[]{0.12, 0.16, 0.20}, "kinetic_reducer", new double[]{0.20, 0.30, 0.40},
                "phoenix_aura", new double[]{0.30, 0.40, 0.50}, "void_stride", new double[]{0.10, 0.15, 0.20},
                "feather_ward", new double[]{0.10, 0.20, 0.30}, "anchor_guard", new double[]{0.10, 0.15, 0.20},
                "tidal_breath", new double[]{10, 20, 30});
        expected.forEach((id, values) -> {
            RuneDef rune = reg.rune(id);
            for (int t = 1; t <= 3; t++) {
                assertEquals(values[t - 1], rune.tier(t).value(), 1e-9, id + " tier " + t);
            }
        });
        assertEquals("epic", reg.rune("blast_rune").tier(1).rarity().id());
        assertEquals("legendary", reg.rune("blast_rune").tier(2).rarity().id());
        assertEquals("fabled", reg.rune("blast_rune").tier(3).rarity().id());
    }

    @Test
    void masksMatchTheSpecification() {
        Registry reg = defaults();
        Map<String, Integer> maxLevels = Map.ofEntries(Map.entry("sedge", 2), Map.entry("wisdom", 2), Map.entry("canapy", 3),
                Map.entry("trident", 3), Map.entry("skelly", 1), Map.entry("creepy", 2), Map.entry("syder", 2),
                Map.entry("wolfski", 1), Map.entry("guard", 2), Map.entry("golom", 3), Map.entry("stray", 3),
                Map.entry("drownie", 3));
        maxLevels.forEach((id, max) -> assertEquals(max, reg.mask(id).maxLevel(), id));
        assertEquals(4, reg.mask("canapy").level(1).heal().hearts());
        assertTrue(reg.mask("canapy").level(3).heal().full());
        assertEquals(40, reg.mask("canapy").level(2).heal().cooldownSeconds());
        assertEquals(3, reg.mask("golom").level(1).bonusHearts());
        assertEquals(8, reg.mask("drownie").level(1).bonusHearts());
        assertEquals(1, reg.mask("drownie").level(1).tridentBonus());
        assertEquals(30, reg.mask("syder").level(1).cobweb().cooldownSeconds());
        assertEquals(20, reg.mask("syder").level(2).cobweb().cooldownSeconds());
        assertEquals(40, reg.mask("skelly").level(1).arrowTeleport().cooldownSeconds());
        assertEquals(2, reg.mask("guard").level(2).summon().count());
        // every upgradable mask has exactly one totem and level recipes using it
        for (MaskDef mask : reg.masks()) {
            if (mask.maxLevel() < 2) {
                continue;
            }
            assertNotNull(reg.material(mask.id() + "_mask_totem"));
            for (int level = 2; level <= mask.maxLevel(); level++) {
                RecipeDef up = reg.recipesProducing(ItemRef.exo(mask.id(), level)).getFirst();
                assertEquals(ItemRef.exo(mask.id(), level - 1), up.cell(4).ref());
                assertEquals(1, up.totals().get(ItemRef.exo(mask.id() + "_mask_totem", 1)));
            }
        }
    }

    @Test
    void texturesArePreservedExactly() throws IOException {
        Registry reg = defaults();
        JsonArray registry = JsonParser.parseString(Files.readString(Path.of("tools/texture-registry.json"))).getAsJsonArray();
        String prompt = Files.readString(Path.of("tools/development-prompt.md"));
        for (JsonElement el : registry) {
            JsonObject e = el.getAsJsonObject();
            String maskId = e.get("mask").getAsString().toLowerCase();
            HeadDef head = reg.head(e.get("head").getAsString());
            assertEquals(e.get("head_texture").getAsString(), head.appearance().texture());
            assertEquals(e.get("mask_texture").getAsString(), reg.mask(maskId).appearance().texture());
            assertTrue(prompt.contains(head.appearance().texture()));
            assertTrue(prompt.contains(reg.mask(maskId).appearance().texture()));
            assertEquals(head.id(), reg.mask(maskId).ingredientHead());
            assertTrue(!head.appearance().texture().equals(reg.mask(maskId).appearance().texture()),
                    "ingredient and mask textures differ");
        }
    }

    @Test
    void invalidConfigIsRejectedWithFileAndKey() throws IOException {
        String runes = Files.readString(RES.resolve("runes.yml")).replace("value: 0.15", "value: 7");
        String recipes = Files.readString(RES.resolve("recipes.yml")).replaceFirst("item: creeper_value_head", "item: creeper_valu_head");
        ConfigLoader.Result result = load(Map.of("runes.yml", runes, "recipes.yml", recipes));
        assertTrue(!result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("runes.yml: runes.blast_rune.tiers.1.value")), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("recipes.yml:") && e.contains("creeper_valu_head")), result.errors().toString());
    }

    @Test
    void unreachableOrMissingRecipesAreRejected() throws IOException {
        String recipes = Files.readString(RES.resolve("recipes.yml"));
        int start = recipes.indexOf("  fabled_rune_upgrade:\n");
        int end = recipes.indexOf("  blast_rune_tier_2:\n");
        ConfigLoader.Result result = load(Map.of("recipes.yml", recipes.substring(0, start) + recipes.substring(end)));
        assertTrue(!result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("no recipe produces fabled_rune_upgrade")), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("blast_rune@3") && e.contains("never be obtained")), result.errors().toString());
    }

    @Test
    void wrongTotemIsRejected() throws IOException {
        String recipes = Files.readString(RES.resolve("recipes.yml")).replace(
                "T: {item: sedge_mask_totem, amount: 1}\n      M: {item: sedge", "T: {item: wisdom_mask_totem, amount: 1}\n      M: {item: sedge");
        ConfigLoader.Result result = load(Map.of("recipes.yml", recipes));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("totem 'wisdom_mask_totem' belongs to mask 'wisdom'")),
                result.errors().toString());
    }

    @Test
    void syntaxErrorsAreReported() {
        ConfigLoader.Result result = load(Map.of("armor.yml", "sets: [unclosed"));
        assertTrue(!result.ok());
        assertTrue(result.errors().getFirst().startsWith("armor.yml: (syntax)"), result.errors().toString());
    }
}
