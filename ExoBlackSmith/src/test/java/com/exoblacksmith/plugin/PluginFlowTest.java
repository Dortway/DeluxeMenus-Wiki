package com.exoblacksmith.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exoblacksmith.ExoBlackSmithPlugin;
import com.exoblacksmith.Services;
import com.exoblacksmith.config.ConfigLoader;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.craft.CraftPlan;
import com.exoblacksmith.craft.CraftingService;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.effect.CooldownService;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemKeys;
import com.exoblacksmith.item.ItemKind;
import com.exoblacksmith.item.ItemService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** End-to-end flows on a MockBukkit 1.21.11 server with the real plugin and default configs. */
class PluginFlowTest {
    ServerMock server;
    ExoBlackSmithPlugin plugin;
    PlayerMock player;
    Services services;
    ItemService items;
    CraftingService crafting;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ExoBlackSmithPlugin.class);
        player = server.addPlayer("Smith");
        services = plugin.services();
        items = services.items();
        crafting = services.crafting();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    Registry reg() {
        return plugin.registry();
    }

    int count(String id, int level) {
        return crafting.count(player, ItemRef.exo(id, level));
    }

    void console(String command) {
        assertTrue(server.dispatchCommand(server.getConsoleSender(), command), command);
    }

    @Test
    void consoleGrantsAuthenticHeadsAndItems() {
        console("exoblacksmith givehead Smith creeper_value_head 120");
        console("exoblacksmith give Smith blast_rune 1 3");
        console("exoblacksmith give Smith sedge 1 2"); // vanilla selectors (@p) are not implemented by MockBukkit
        assertEquals(120, count("creeper_value_head", 1));
        assertEquals(1, count("blast_rune", 3));
        assertEquals(1, count("sedge", 2));
        // invalid input is rejected without giving anything
        console("exoblacksmith give Smith blast_rune 1 4");
        console("exoblacksmith give Smith not_an_item 1");
        console("exoblacksmith givehead Smith blast_rune 1");
        assertEquals(0, count("blast_rune", 1));
    }

    @Test
    void craftsTierOneRuneConsumingExactly120Heads() {
        console("exoblacksmith givehead Smith creeper_value_head 64");
        console("exoblacksmith givehead Smith creeper_value_head 10");
        console("exoblacksmith givehead Smith skeleton_value_head 61");
        CraftingService.Outcome outcome = crafting.craft(player, reg().recipe("blast_rune"));
        assertTrue(outcome.ok(), () -> "missing " + outcome.missing());
        assertEquals(1, count("blast_rune", 1));
        assertEquals(74 - 60, count("creeper_value_head", 1));
        assertEquals(1, count("skeleton_value_head", 1));
        // second click: nothing left to pay with
        assertEquals(CraftPlan.Status.MISSING, crafting.craft(player, reg().recipe("blast_rune")).status());
        assertEquals(1, count("blast_rune", 1));
    }

    @Test
    void forgedLookalikesAreRejectedAndNotConsumed() {
        ItemStack real = items.create(reg().head("creeper_value_head"), 1, 64);
        ItemStack forged = real.clone();
        ItemMeta meta = forged.getItemMeta();
        ItemKeys keys = items.keys();
        meta.getPersistentDataContainer().set(keys.signature, PersistentDataType.STRING, "00000000000000000000000000000000");
        forged.setItemMeta(meta);
        ItemStack tampered = items.create(reg().rune("blast_rune"), 1, 1);
        ItemMeta tm = tampered.getItemMeta();
        tm.getPersistentDataContainer().set(keys.level, PersistentDataType.INTEGER, 3);
        tampered.setItemMeta(tm);
        ItemStack plainHead = ItemStack.of(Material.PLAYER_HEAD, 64);

        assertNotNull(items.identify(real));
        assertNull(items.identify(forged), "bad signature");
        assertNull(items.identify(tampered), "edited tier");
        assertNull(items.identify(plainHead), "vanilla head");

        player.getInventory().addItem(forged, forged.clone(), plainHead);
        console("exoblacksmith givehead Smith skeleton_value_head 60");
        assertEquals(0, count("creeper_value_head", 1));
        CraftingService.Outcome outcome = crafting.craft(player, reg().recipe("blast_rune"));
        assertEquals(CraftPlan.Status.MISSING, outcome.status());
        assertEquals(60, outcome.missing().get(ItemRef.exo("creeper_value_head", 1)));
        assertEquals(60, count("skeleton_value_head", 1), "nothing consumed on failure");
        int forgedLeft = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.PLAYER_HEAD && items.identify(s) == null) {
                forgedLeft += s.getAmount();
            }
        }
        assertEquals(64 * 3, forgedLeft, "forged and plain heads were not touched");
    }

    @Test
    void rejectsBeforeConsumptionWhenInventoryIsFull() {
        console("exoblacksmith givehead Smith sheep_value_head 64");
        console("exoblacksmith givehead Smith sheep_value_head 1");
        player.getInventory().addItem(ItemStack.of(Material.DIAMOND_BLOCK, 1));
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i) == null) {
                player.getInventory().setItem(i, ItemStack.of(Material.DIRT, 64));
            }
        }
        // sedge_mask needs 64 sheep heads + 1 diamond block: the 64-stack frees a slot, so it fits
        assertTrue(crafting.craft(player, reg().recipe("sedge_mask")).ok());
        assertEquals(1, count("sedge", 1));
        // fill again: the next craft cannot free a slot (1 head remaining stack stays)
        console("exoblacksmith givehead Smith sheep_value_head 63");
        int before = count("sheep_value_head", 1);
        assertEquals(CraftPlan.Status.MISSING, crafting.craft(player, reg().recipe("sedge_mask")).status());
        assertEquals(before, count("sheep_value_head", 1));
    }

    @Test
    void noSpaceLeavesInventoryUntouched() {
        // Every ingredient stack keeps a remainder, so consumption frees no slot and the 8 outputs cannot fit.
        player.getInventory().setItem(0, items.create(reg().head("zombie_value_head"), 1, 9));
        player.getInventory().setItem(1, ItemStack.of(Material.GOLD_INGOT, 17));
        player.getInventory().setItem(2, ItemStack.of(Material.DIAMOND, 2));
        for (int i = 3; i < 36; i++) {
            player.getInventory().setItem(i, ItemStack.of(Material.STONE, 64));
        }
        CraftingService.Outcome outcome = crafting.craft(player, reg().recipe("legendary_rune_upgrade"));
        assertEquals(CraftPlan.Status.NO_SPACE, outcome.status());
        assertEquals(9, count("zombie_value_head", 1), "no ingredients consumed when output cannot fit");
        assertEquals(17, player.getInventory().getItem(1).getAmount());
        assertEquals(2, player.getInventory().getItem(2).getAmount());
        assertEquals(0, count("legendary_rune_upgrade", 1));

        // free one slot: now it fits, and exactly the recipe quantities are charged
        player.getInventory().setItem(35, null);
        assertTrue(crafting.craft(player, reg().recipe("legendary_rune_upgrade")).ok());
        assertEquals(1, count("zombie_value_head", 1));
        assertEquals(1, player.getInventory().getItem(1).getAmount());
        assertEquals(1, player.getInventory().getItem(2).getAmount());
        assertEquals(8, count("legendary_rune_upgrade", 1));
    }

    @Test
    void runeApplicationEnforcesSlotsDuplicatesAndCapacity() {
        RuneService runes = services.runes();
        console("exoblacksmith give Smith riftguard_chestplate 1");
        console("exoblacksmith give Smith riftguard_boots 1");
        console("exoblacksmith give Smith blast_rune 2 1");
        console("exoblacksmith give Smith phoenix_aura 1 1");
        console("exoblacksmith give Smith hardened_shell 1 2");
        console("exoblacksmith give Smith kinetic_reducer 1 1");
        console("exoblacksmith give Smith void_stride 1 1");
        String chest = null;
        String boots = null;
        for (RuneService.ArmorLocation loc : runes.allArmor(player)) {
            if (loc.def().id().equals("riftguard_chestplate")) {
                chest = loc.data().uid();
            } else {
                boots = loc.data().uid();
            }
        }
        assertEquals(RuneService.Result.INCOMPATIBLE, runes.apply(player, boots, "phoenix_aura", 1));
        assertEquals(RuneService.Result.INCOMPATIBLE, runes.apply(player, chest, "void_stride", 1));
        assertEquals(1, count("phoenix_aura", 1), "rejected rune not consumed");
        assertEquals(RuneService.Result.OK, runes.apply(player, chest, "blast_rune", 1));
        assertEquals(1, count("blast_rune", 1), "exactly one rune consumed");
        assertEquals(RuneService.Result.DUPLICATE, runes.apply(player, chest, "blast_rune", 1));
        assertEquals(RuneService.Result.OK, runes.apply(player, chest, "phoenix_aura", 1));
        assertEquals(RuneService.Result.OK, runes.apply(player, chest, "hardened_shell", 2));
        assertEquals(RuneService.Result.NO_SLOTS, runes.apply(player, chest, "kinetic_reducer", 1));
        assertEquals(1, count("kinetic_reducer", 1));
        RuneService.ArmorLocation loc = runes.findArmor(player, chest);
        assertEquals(3, loc.data().runes().size());
        assertNotNull(items.identify(loc.stack()), "re-signed armor still authenticates");
        // ordinary armor never accepts runes
        assertEquals(RuneService.Result.ARMOR_MISSING, runes.apply(player, "not-a-uid", "kinetic_reducer", 1));
    }

    @Test
    void maskUpgradeConsumesOldMaskOnceAndRetiresItsId() {
        console("exoblacksmith give Smith sedge 1 1");
        console("exoblacksmith give Smith sedge_mask_totem 1");
        console("exoblacksmith give Smith wisdom_mask_totem 1");
        console("exoblacksmith givehead Smith sheep_value_head 105");
        ItemStack oldMask = null;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            ItemData d = items.read(s);
            if (d != null && d.kind() == ItemKind.MASK) {
                oldMask = s.clone();
            }
        }
        assertNotNull(oldMask);
        CraftingService.Outcome outcome = crafting.craft(player, reg().recipe("sedge_mask_level_2"));
        assertTrue(outcome.ok(), () -> "missing " + outcome.missing());
        assertEquals(1, count("sedge", 2));
        assertEquals(0, count("sedge", 1));
        assertEquals(0, count("sedge_mask_totem", 1));
        assertEquals(1, count("wisdom_mask_totem", 1), "the other mask's totem is untouched");
        assertEquals(0, count("sheep_value_head", 1));
        assertNull(items.identify(oldMask), "a duplicated copy of the consumed mask is dead");
        assertTrue(items.retired().isRetired(items.read(oldMask).uid()));
        // max level reached for sedge: no level 3 recipe exists
        assertTrue(reg().recipesProducing(ItemRef.exo("sedge", 3)).isEmpty());
    }

    @Test
    void maskEffectsAndHealthApplyOnceAndCleanUp() {
        ItemStack golom = items.create(reg().mask("golom"), 1, 1);
        player.getInventory().setHelmet(golom);
        plugin.equipment().refresh(player);
        plugin.equipment().refresh(player);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        long ours = maxHealth.getModifiers().stream().filter(m -> m.getKey().getNamespace().equals("exoblacksmith")).count();
        assertEquals(1, ours, "re-applying never duplicates the health modifier");
        assertEquals(26.0, maxHealth.getValue(), 1e-9, "+3 hearts");
        assertTrue(player.hasPotionEffect(PotionEffectType.REGENERATION));
        assertTrue(player.hasPotionEffect(PotionEffectType.STRENGTH));

        player.getInventory().setHelmet(null);
        plugin.equipment().refresh(player);
        assertEquals(20.0, maxHealth.getValue(), 1e-9);
        assertFalse(player.hasPotionEffect(PotionEffectType.REGENERATION));
        assertFalse(player.hasPotionEffect(PotionEffectType.STRENGTH));
        for (AttributeModifier m : maxHealth.getModifiers()) {
            assertFalse(m.getKey().getNamespace().equals("exoblacksmith"));
        }
    }

    @Test
    void strongerExternalEffectsAreNeverErasedAndWeakerOnesAreRestored() {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 6000, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 6000, 0));
        // stray level 3: speed II (amp 1), strength I (amp 0)
        player.getInventory().setHelmet(items.create(reg().mask("stray"), 3, 1));
        plugin.equipment().refresh(player);
        assertEquals(1, player.getPotionEffect(PotionEffectType.STRENGTH).getAmplifier(), "stronger external strength kept");
        assertEquals(1, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "mask speed II applied");

        player.getInventory().setHelmet(null);
        plugin.equipment().refresh(player);
        PotionEffect strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        assertNotNull(strength);
        assertEquals(1, strength.getAmplifier(), "external strength II still there");
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed, "weaker external speed I restored");
        assertEquals(0, speed.getAmplifier());
        assertFalse(speed.isInfinite());
    }

    @Test
    void retiredOrUnsignedMasksGrantNothing() {
        ItemStack mask = items.create(reg().mask("golom"), 1, 1);
        items.retired().retire(items.read(mask).uid());
        player.getInventory().setHelmet(mask);
        plugin.equipment().refresh(player);
        assertEquals(20.0, player.getAttribute(Attribute.MAX_HEALTH).getValue(), 1e-9);
    }

    @Test
    void cooldownsSurviveRestartAndAreKeyedByAbility() {
        CooldownService cooldowns = plugin.cooldowns();
        cooldowns.start(player, "mask.heal", 40_000);
        assertFalse(cooldowns.ready(player, "mask.heal"));
        // a fresh service instance (plugin reload / restart) reads the player's persistent data
        CooldownService fresh = new CooldownService(services.items().keys());
        assertFalse(fresh.ready(player, "mask.heal"));
        assertTrue(fresh.remaining(player, "mask.heal") > 39_000);
        assertTrue(fresh.ready(player, "mask.cobweb"));
    }

    @Test
    void brokenReloadKeepsThePreviousConfiguration() throws Exception {
        Registry before = reg();
        File runes = new File(plugin.getDataFolder(), "runes.yml");
        String original = Files.readString(runes.toPath(), StandardCharsets.UTF_8);
        Files.writeString(runes.toPath(), original.replace("mechanic: BLAST", "mechanic: EXPLODE_EVERYTHING"));
        ConfigLoader.Result result = plugin.reload();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("runes.yml: runes.blast_rune.mechanic")), result.errors().toString());
        assertSame(before, reg(), "live configuration unchanged");

        Files.writeString(runes.toPath(), original.replace("value: 0.15", "value: 0.18"));
        assertTrue(plugin.reload().ok());
        assertEquals(0.18, reg().rune("blast_rune").tier(1).value(), 1e-9);
    }

    @Test
    void dropApiGrantsAuthenticHeadsForRealDeathsOnly() {
        int granted = plugin.drops().grant(player, org.bukkit.entity.EntityType.CREEPER, 2000, false, player.getLocation());
        assertTrue(granted > 40 && granted < 200, "~5% of 2000 deaths, got " + granted);
        assertEquals(0, plugin.drops().grant(null, org.bukkit.entity.EntityType.CREEPER, 100, false, player.getLocation()),
                "player killer required by default");
        assertEquals(0, plugin.drops().grant(player, org.bukkit.entity.EntityType.CREEPER, 0, false, player.getLocation()));
        assertEquals(0, plugin.drops().grant(player, org.bukkit.entity.EntityType.BLAZE, 100, false, player.getLocation()),
                "mobs without a configured head drop nothing");
    }
}
