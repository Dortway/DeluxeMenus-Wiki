package com.exoblacksmith.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exoblacksmith.ExoBlackSmithPlugin;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.gui.MainMenu;
import com.exoblacksmith.gui.Menu;
import com.exoblacksmith.gui.RecipeMenu;
import com.exoblacksmith.item.ItemService;
import java.lang.reflect.Field;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Event-level behaviour: damage pipeline, menus, abilities and vanilla guards. */
class GameplayTest {
    ServerMock server;
    ExoBlackSmithPlugin plugin;
    PlayerMock player;
    ItemService items;
    WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(ExoBlackSmithPlugin.class);
        player = server.addPlayer("Smith");
        player.teleport(new Location(world, 0.5, 70, 0.5));
        items = plugin.services().items();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    ItemStack armorWith(String id, String rune, int tier) {
        ItemStack piece = items.create(plugin.registry().armor(id), 1, 1);
        if (rune == null) {
            return piece;
        }
        player.getInventory().addItem(piece, items.create(plugin.registry().rune(rune), tier, 1));
        String uid = items.read(piece).uid();
        assertEquals(RuneService.Result.OK, plugin.services().runes().apply(player, uid, rune, tier));
        RuneService.ArmorLocation loc = plugin.services().runes().findArmor(player, uid);
        player.getInventory().setItem(loc.index(), null);
        return loc.stack();
    }

    double fire(EntityDamageEvent event) {
        server.getPluginManager().callEvent(event);
        return event.getDamage();
    }

    // ------------------------------------------------------------------ damage

    @Test
    void featherWardReducesOnlyFallDamage() {
        player.getInventory().setBoots(armorWith("stormstride_boots", "feather_ward", 3));
        double fall = fire(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 10));
        // rune 30% and stormstride per-piece 8% fall, multiplicative: 10 * 0.7 * 0.92
        assertEquals(10 * 0.7 * 0.92, fall, 1e-9);
        double drown = fire(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.DROWNING,
                DamageSource.builder(DamageType.DROWN).build(), 10));
        assertEquals(10, drown, 1e-9, "unrelated damage untouched");
    }

    @Test
    void phoenixAuraReducesFireAndLavaButNeverGrantsImmunity() {
        player.getInventory().setChestplate(armorWith("emberforged_chestplate", "phoenix_aura", 3));
        player.getInventory().setLeggings(items.create(plugin.registry().armor("emberforged_leggings"), 1, 1));
        player.getInventory().setBoots(items.create(plugin.registry().armor("emberforged_boots"), 1, 1));
        double lava = fire(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.LAVA,
                DamageSource.builder(DamageType.LAVA).build(), 10));
        // phoenix 50%, 3 pieces x 6%, set bonus 10% -> multiplicative 0.5*0.94^3*0.9 = 0.3738, under the 80% cap
        assertEquals(10 * 0.5 * Math.pow(0.94, 3) * 0.9, lava, 1e-9);

        // set ability (sneak + swap hands) adds a temporary 40% fire/lava ward
        player.setSneaking(true);
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR), new ItemStack(Material.AIR));
        server.getPluginManager().callEvent(swap);
        assertTrue(swap.isCancelled(), "set ability consumes the swap key while sneaking with a full set");
        double warded = fire(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.LAVA,
                DamageSource.builder(DamageType.LAVA).build(), 10));
        // + ward 40% lava: 0.5 * 0.94^3 * 0.9 * 0.6 = 22.4% of the damage remains (77.6% reduction, under the 80% cap)
        assertEquals(10 * 0.5 * Math.pow(0.94, 3) * 0.9 * 0.6, warded, 1e-9);
        assertTrue(warded > 0, "never immunity");
        assertTrue(plugin.cooldowns().remaining(player, "set.emberforged") > 0);
    }

    @Test
    void blastRuneDoesNotAffectOrdinaryExplosions() {
        player.getInventory().setChestplate(armorWith("emberforged_chestplate", "blast_rune", 1));
        double creeper = fire(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                DamageSource.builder(DamageType.EXPLOSION).build(), 10));
        assertEquals(10, creeper, 1e-9, "a generic explosion is not a crystal/anchor explosion");
    }

    @Test
    void setBonusNeedsChestLegsBootsAndIgnoresHelmet() {
        player.getInventory().setHelmet(items.create(plugin.registry().mask("sedge"), 1, 1));
        player.getInventory().setChestplate(items.create(plugin.registry().armor("riftguard_chestplate"), 1, 1));
        player.getInventory().setLeggings(items.create(plugin.registry().armor("riftguard_leggings"), 1, 1));
        assertNull(plugin.equipment().current(player).activeSet());
        player.getInventory().setBoots(items.create(plugin.registry().armor("riftguard_boots"), 1, 1));
        assertNotNull(plugin.equipment().current(player).activeSet(), "mask in the helmet slot still completes the set");
        assertEquals("sedge", plugin.equipment().current(player).mask().id());
    }

    @Test
    void runeStackingDefaultsToHighestTier() {
        player.getInventory().setChestplate(armorWith("riftguard_chestplate", "hardened_shell", 1));
        player.getInventory().setLeggings(armorWith("riftguard_leggings", "hardened_shell", 3));
        assertEquals(0.20, plugin.equipment().current(player).rune(com.exoblacksmith.config.model.RuneMechanic.HARDENED_SHELL), 1e-9);
    }

    // ------------------------------------------------------------------ menus

    @Test
    void menusAreLockedAgainstEveryClickType() {
        assertTrue(player.performCommand("blacksmith"));
        InventoryView view = player.getOpenInventory();
        assertInstanceOf(MainMenu.class, com.exoblacksmith.gui.Holders.menu(view.getTopInventory()));
        for (ClickType click : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.MIDDLE)) {
            InventoryClickEvent top = player.simulateInventoryClick(view, click, 10);
            assertTrue(top.isCancelled(), "top " + click);
            InventoryClickEvent bottom = player.simulateInventoryClick(view, click, 60);
            assertTrue(bottom.isCancelled(), "bottom " + click);
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            assertTrue(stack == null || !items.isMenuIcon(stack), "no icon leaked into the player inventory");
        }
    }

    @Test
    void rapidConfirmClicksCraftExactlyOnce() throws Exception {
        RecipeDef recipe = plugin.registry().recipe("legendary_rune_upgrade");
        player.getInventory().addItem(items.create(plugin.registry().head("zombie_value_head"), 1, 16),
                ItemStack.of(Material.GOLD_INGOT, 32), ItemStack.of(Material.DIAMOND, 2));
        RecipeMenu menu = new RecipeMenu(plugin.services(), player, recipe, 0);
        menu.open();
        InventoryView view = player.getOpenInventory();
        for (int i = 0; i < 5; i++) {
            player.simulateInventoryClick(view, ClickType.LEFT, 43);
        }
        server.getScheduler().performTicks(2);
        assertEquals(8, plugin.services().crafting().count(player, ItemRef.exo("legendary_rune_upgrade", 1)),
                "five clicks in one tick produce one craft");
        Thread.sleep(plugin.registry().settings.clickCooldownMs + 20);
        player.simulateInventoryClick(view, ClickType.LEFT, 43);
        server.getScheduler().performTicks(2);
        assertEquals(16, plugin.services().crafting().count(player, ItemRef.exo("legendary_rune_upgrade", 1)),
                "a later deliberate click crafts again with the remaining ingredients");
        player.simulateInventoryClick(view, ClickType.LEFT, 43);
        server.getScheduler().performTicks(2);
        Thread.sleep(plugin.registry().settings.clickCooldownMs + 20);
        player.simulateInventoryClick(view, ClickType.LEFT, 43);
        server.getScheduler().performTicks(2);
        assertEquals(16, plugin.services().crafting().count(player, ItemRef.exo("legendary_rune_upgrade", 1)),
                "no ingredients left: no output");
    }

    @Test
    void closedMenuNeverExecutesAQueuedClick() {
        RecipeDef recipe = plugin.registry().recipe("legendary_rune_upgrade");
        player.getInventory().addItem(items.create(plugin.registry().head("zombie_value_head"), 1, 8),
                ItemStack.of(Material.GOLD_INGOT, 16), ItemStack.of(Material.DIAMOND, 1));
        new RecipeMenu(plugin.services(), player, recipe, 0).open();
        player.simulateInventoryClick(player.getOpenInventory(), ClickType.LEFT, 43);
        player.closeInventory();
        server.getScheduler().performTicks(2);
        assertEquals(0, plugin.services().crafting().count(player, ItemRef.exo("legendary_rune_upgrade", 1)));
        assertEquals(8, plugin.services().crafting().count(player, ItemRef.exo("zombie_value_head", 1)));
    }

    // ------------------------------------------------------------------ guards and abilities

    @Test
    void headsAndMasksCannotBePlaced() {
        Block block = world.getBlockAt(5, 70, 5);
        ItemStack head = items.create(plugin.registry().head("pig_value_head"), 1, 1);
        BlockPlaceEvent place = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN), head, player,
                true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled());
    }

    @Test
    void canapyHealsRespectingMaxHealthAndCooldown() {
        player.getInventory().setHelmet(items.create(plugin.registry().mask("canapy"), 1, 1));
        player.setHealth(4);
        player.setSneaking(true);
        PlayerInteractEvent click = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(click);
        assertEquals(12, player.getHealth(), 1e-9, "4 hearts = 8 health points");
        assertTrue(plugin.cooldowns().remaining(player, "mask.heal") > 39_000);
        server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND));
        assertEquals(12, player.getHealth(), 1e-9, "second activation is on cooldown");
        // offhand events never trigger
        plugin.cooldowns().clear(player, "mask.heal");
        server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.OFF_HAND));
        assertEquals(12, player.getHealth(), 1e-9);
    }

    @Test
    void canapyLevelThreeRestoresFullHealthIncludingBonusHearts() {
        player.getInventory().setHelmet(items.create(plugin.registry().mask("canapy"), 3, 1));
        player.setHealth(2);
        player.setSneaking(true);
        server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND));
        assertEquals(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), player.getHealth(), 1e-9);
    }

    @Test
    void temporaryCobwebsExpireWithoutOverwritingLaterChanges() throws Exception {
        Field f = ExoBlackSmithPlugin.class.getDeclaredField("tempBlocks");
        f.setAccessible(true);
        com.exoblacksmith.ability.TempBlockService temp = (com.exoblacksmith.ability.TempBlockService) f.get(plugin);
        Block a = world.getBlockAt(10, 71, 10);
        Block b = world.getBlockAt(11, 71, 10);
        world.getChunkAt(a).load();
        a.setType(Material.AIR);
        b.setType(Material.AIR);
        assertTrue(temp.place(a, Material.COBWEB, 1));
        assertTrue(temp.place(b, Material.COBWEB, 1));
        assertFalse(temp.place(a, Material.COBWEB, 1), "never stacks on an existing block");
        // a player replaces b with something else through a real placement event
        b.setType(Material.STONE);
        BlockPlaceEvent real = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN), ItemStack.of(Material.STONE),
                player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(real);
        Thread.sleep(1100);
        server.getScheduler().performTicks(20);
        assertEquals(Material.AIR, a.getType(), "expired cobweb removed");
        assertEquals(Material.STONE, b.getType(), "later unrelated change kept");
    }

    // ------------------------------------------------------------------ attacker bonuses

    EntityDamageByEntityEvent hit(org.bukkit.entity.Entity attacker, org.bukkit.entity.Entity victim, double damage) {
        return new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withDirectEntity(attacker).withCausingEntity(attacker).build(), damage);
    }

    @Test
    void tridentMaskAddsDamageOnlyToTridentHits() {
        PlayerMock victim = server.addPlayer("Victim");
        player.getInventory().setHelmet(items.create(plugin.registry().mask("trident"), 3, 1));
        player.getInventory().setItemInMainHand(ItemStack.of(Material.TRIDENT));
        assertEquals(11, fire(hit(player, victim, 8)), 1e-9, "+3 damage points at level 3");
        player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND_SWORD));
        assertEquals(8, fire(hit(player, victim, 8)), 1e-9, "no bonus without a trident");
    }

    @Test
    void creepyProcRespectsCooldownAndCancelledPvp() {
        PlayerMock victim = server.addPlayer("Victim");
        player.getInventory().setHelmet(items.create(plugin.registry().mask("creepy"), 2, 1));
        // a PvP-protected (cancelled) hit never spends the cooldown
        EntityDamageByEntityEvent blocked = hit(player, victim, 5);
        blocked.setCancelled(true);
        server.getPluginManager().callEvent(blocked);
        assertTrue(plugin.cooldowns().ready(player, "mask.explosive_hit"));
        assertEquals(9, fire(hit(player, victim, 5)), 1e-9, "+4 explosive damage at level 2");
        assertFalse(plugin.cooldowns().ready(player, "mask.explosive_hit"));
        assertEquals(5, fire(hit(player, victim, 5)), 1e-9, "8s proc cooldown");
    }

    @Test
    void summonsOnlyChaseTheirTargetAndLeaveNoDrops() {
        PlayerMock victim = server.addPlayer("Victim");
        victim.teleport(new Location(world, 4.5, 70, 0.5));
        world.getChunkAt(0, 0).load();
        world.getChunkAt(-1, -1).load();
        world.getChunkAt(-1, 0).load();
        world.getChunkAt(0, -1).load();
        for (int x = -3; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, 70, z).setType(Material.AIR);
                world.getBlockAt(x, 71, z).setType(Material.AIR);
                world.getBlockAt(x, 69, z).setType(Material.STONE);
            }
        }
        com.exoblacksmith.config.model.MaskLevel.SummonAbility wolves = plugin.registry().mask("wolfski").level(1).summon();
        com.exoblacksmith.ability.SummonService summons;
        try {
            Field f = ExoBlackSmithPlugin.class.getDeclaredField("summons");
            f.setAccessible(true);
            summons = (com.exoblacksmith.ability.SummonService) f.get(plugin);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertEquals(2, summons.summon(player, victim, wolves, 4));
        assertEquals(2, summons.active(player.getUniqueId()));
        org.bukkit.entity.Wolf wolf = world.getEntitiesByClass(org.bukkit.entity.Wolf.class).iterator().next();
        org.bukkit.event.entity.EntityTargetEvent retarget = new org.bukkit.event.entity.EntityTargetEvent(wolf, player,
                org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY);
        server.getPluginManager().callEvent(retarget);
        assertTrue(retarget.isCancelled(), "a summon can never target its owner");
        EntityDamageByEntityEvent bite = hit(wolf, player, 4);
        server.getPluginManager().callEvent(bite);
        assertTrue(bite.isCancelled(), "a summon cannot damage anyone but its target");
        assertFalse(wolf.isPersistent(), "summons are never saved to disk");
        // owner logs out: summons are removed
        player.disconnect();
        assertEquals(0, summons.active(player.getUniqueId()));
    }
}
