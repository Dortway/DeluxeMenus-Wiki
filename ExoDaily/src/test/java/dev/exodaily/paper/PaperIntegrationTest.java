package dev.exodaily.paper;

import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.paper.menu.ExoMenu;
import dev.exodaily.paper.menu.MenuLookup;
import dev.exodaily.paper.menu.MenuType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Boots the real plugin on MockBukkit's Paper 1.21.11 server and drives it like a player would. */
class PaperIntegrationTest {

    private ServerMock server;
    private ExoDailyPlugin plugin;

    /** MockBukkit does not implement Paper's getHolder(boolean); production uses that overload. */
    public static class TestPlugin extends ExoDailyPlugin {
        @Override
        protected MenuLookup menuLookup() {
            return inventory -> inventory != null && inventory.getHolder() instanceof ExoMenu menu ? menu : null;
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.loadWith(TestPlugin.class, PaperIntegrationTest.class.getResourceAsStream("/plugin.yml"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    private void settle() {
        // Let in-flight storage work finish and its server-thread callbacks run.
        for (int i = 0; i < 40; i++) {
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Waits out the configured click cooldown so the next click is not ignored as spam. */
    private void humanPause() {
        long until = System.currentTimeMillis() + 400;
        while (System.currentTimeMillis() < until) {
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> messages(PlayerMock player) {
        List<String> messages = new java.util.ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            messages.add(plain(message));
        }
        return messages;
    }

    private static ExoMenu menu(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof ExoMenu menu ? menu : null;
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String name(ItemStack item) {
        return plain(item.getItemMeta().displayName());
    }

    private static String lore(ItemStack item) {
        List<Component> lore = item.getItemMeta().lore();
        return lore == null ? "" : String.join("\n", lore.stream().map(PaperIntegrationTest::plain).toList());
    }

    private static int itemCount(PlayerMock player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(item -> item != null && !item.isEmpty())
                .mapToInt(ItemStack::getAmount).sum();
    }

    private PlayerMock newPlayer() {
        PlayerMock player = server.addPlayer();
        // MockBukkit's loadWith() does not apply plugin.yml permission defaults; Paper does.
        player.addAttachment(plugin, Permissions.USE, true);
        return player;
    }

    private PlayerMock openMain() {
        PlayerMock player = newPlayer();
        player.performCommand("daily");
        await("main menu", () -> menu(player) != null && menu(player).type() == MenuType.MAIN);
        return player;
    }

    private InventoryClickEvent click(PlayerMock player, ClickType type, int rawSlot) {
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void enablesWithBundledConfigurationAndStorage() {
        assertTrue(plugin.storageReady());
        assertNotNull(plugin.presentation());
        for (String file : ConfigLoader.FILES) {
            assertTrue(Files.exists(plugin.getDataFolder().toPath().resolve(file)), file + " was not created");
        }
    }

    @Test
    void mainMenuShowsTodayInTheCentre() {
        PlayerMock player = openMain();
        Inventory top = player.getOpenInventory().getTopInventory();
        assertEquals(27, top.getSize());
        ItemStack today = top.getItem(13);
        assertNotNull(today);
        assertEquals(Material.CHEST, today.getType());
        assertEquals("✦ ᴅᴀʏ 1", name(today));
        String lore = lore(today);
        assertTrue(lore.contains("your rewards for today"), lore);
        assertTrue(lore.contains("◆ standard:"), lore);
        assertTrue(lore.contains("premium:") && lore.contains("(locked)"), lore);
        assertTrue(lore.contains("claimed: 0/1"), lore);
        assertTrue(lore.contains("next reset:"), lore);
        assertTrue(lore(top.getItem(22)).contains("miss a day and its rewards are skipped."));
        assertTrue(lore(top.getItem(22)).contains("reset timezone: Europe/London"));
        for (int slot : new int[]{4, 11, 15, 18, 26}) {
            assertNotNull(top.getItem(slot), "slot " + slot);
        }
        // Decorative border fills the rest.
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, top.getItem(0).getType());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, top.getItem(2).getType());
    }

    @Test
    void inventoryManipulationIsBlocked() {
        PlayerMock player = openMain();
        player.getInventory().setItem(0, new ItemStack(Material.DIRT, 5));
        int topSize = player.getOpenInventory().getTopInventory().getSize();
        for (ClickType type : ClickType.values()) {
            if (type == ClickType.UNKNOWN) {
                continue;
            }
            for (int rawSlot : new int[]{0, 13, topSize, topSize + 27}) {
                InventoryClickEvent event = click(player, type, rawSlot);
                assertTrue(event.isCancelled(), type + " on raw slot " + rawSlot + " was not cancelled");
            }
        }
        InventoryDragEvent drag = new InventoryDragEvent(player.getOpenInventory(), null, new ItemStack(Material.DIRT),
                false, Map.of(13, new ItemStack(Material.DIRT)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
        assertEquals(Material.CHEST, player.getOpenInventory().getTopInventory().getItem(13).getType());
        assertEquals(5, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void claimingDeliversOnceAndRespectsPremium() {
        PlayerMock player = openMain();
        click(player, ClickType.LEFT, 13);
        await("details menu", () -> menu(player) != null && menu(player).type() == MenuType.DETAILS);
        Inventory details = player.getOpenInventory().getTopInventory();
        assertTrue(lore(details.getItem(11)).contains("position 1"), lore(details.getItem(11)));
        assertEquals(Material.LIME_STAINED_GLASS_PANE, details.getItem(20).getType());
        assertEquals(Material.RED_STAINED_GLASS_PANE, details.getItem(22).getType());

        humanPause();
        click(player, ClickType.LEFT, 20);
        await("standard reward", () -> itemCount(player) > 0);
        settle();
        int afterFirst = itemCount(player);
        await("claimed button", () -> player.getOpenInventory().getTopInventory().getItem(20).getType()
                == Material.GREEN_STAINED_GLASS_PANE);

        // Repeated clicks and the locked premium position give nothing more.
        messages(player);
        for (int i = 0; i < 10; i++) {
            click(player, ClickType.LEFT, 20); // rapid spam: ignored or "already claimed"
        }
        for (int slot : new int[]{20, 11, 22, 13, 24}) {
            humanPause();
            click(player, ClickType.LEFT, slot);
        }
        settle();
        assertEquals(afterFirst, itemCount(player));
        List<String> afterSpam = messages(player);
        assertTrue(afterSpam.stream().anyMatch(m -> m.contains("for premium players")), afterSpam.toString());

        // Upgrading mid-day unlocks positions 2 and 3 for today.
        player.addAttachment(plugin, Permissions.PREMIUM, true);
        await("premium re-render", () -> player.getOpenInventory().getTopInventory().getItem(22).getType()
                == Material.LIME_STAINED_GLASS_PANE);
        humanPause();
        click(player, ClickType.LEFT, 22);
        await("premium reward", () -> itemCount(player) > afterFirst);
        settle();
        int afterSecond = itemCount(player);
        humanPause();
        click(player, ClickType.LEFT, 24);
        await("second premium reward", () -> itemCount(player) > afterSecond);
        settle();
        int total = itemCount(player);
        for (int i = 0; i < 3; i++) {
            humanPause();
            click(player, ClickType.LEFT, 20);
            click(player, ClickType.LEFT, 22);
            click(player, ClickType.LEFT, 24);
            settle();
        }
        assertEquals(total, itemCount(player));
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("already claimed")));
        assertTrue(Arrays.stream(player.getInventory().getContents())
                .noneMatch(item -> item != null && item.hasItemMeta()
                        && item.getItemMeta().getPersistentDataContainer().getKeys().stream()
                        .anyMatch(key -> key.getNamespace().equals("exodaily"))),
                "menu icons must never reach the player's inventory");
    }

    @Test
    void fullInventoryKeepsTheRewardAndDropsNothing() {
        PlayerMock player = openMain();
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        click(player, ClickType.LEFT, 13);
        await("details menu", () -> menu(player) != null && menu(player).type() == MenuType.DETAILS);
        humanPause();
        messages(player);
        click(player, ClickType.LEFT, 20);
        settle();
        assertEquals(36 * 64, itemCount(player));
        List<String> full = messages(player);
        assertTrue(full.stream().anyMatch(m -> m.contains("inventory is too full")), full.toString());
        assertTrue(player.getWorld().getEntitiesByClass(Item.class).isEmpty(), "nothing may be dropped on the ground");
        assertEquals(Material.LIME_STAINED_GLASS_PANE, player.getOpenInventory().getTopInventory().getItem(20).getType());

        player.getInventory().setItem(35, null);
        humanPause();
        click(player, ClickType.LEFT, 20);
        await("reward after freeing space", () -> itemCount(player) > 35 * 64);
    }

    @Test
    void leakedMenuIconsAreRemoved() {
        PlayerMock player = openMain();
        ItemStack icon = player.getOpenInventory().getTopInventory().getItem(13).clone();
        player.getInventory().setItem(5, icon);
        player.closeInventory();
        settle();
        ItemStack slot = player.getInventory().getItem(5);
        assertTrue(slot == null || slot.isEmpty());
    }

    @Test
    void adminSetDayRefreshesOpenMenus() {
        PlayerMock player = openMain();
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        admin.performCommand("exodaily setday " + player.getName() + " 5");
        await("refreshed day", () -> menu(player) != null
                && name(player.getOpenInventory().getTopInventory().getItem(13)).equals("✦ ᴅᴀʏ 5"));
        assertTrue(Files.exists(plugin.getDataFolder().toPath().resolve("audit.log")));
    }

    @Test
    void nonAdminsCannotUseAdminCommands() {
        PlayerMock player = newPlayer();
        player.performCommand("exodaily setday " + player.getName() + " 5");
        settle();
        assertTrue(plain(player.nextComponentMessage()).contains("permission"));
    }

    @Test
    void failedReloadKeepsTheActiveConfiguration() throws Exception {
        Presentation before = plugin.presentation();
        Path rewards = plugin.getDataFolder().toPath().resolve(ConfigLoader.REWARDS);
        String original = Files.readString(rewards, StandardCharsets.UTF_8);
        Files.writeString(rewards, original.replace("material: COAL,", "material: COALZ,"), StandardCharsets.UTF_8);
        server.dispatchCommand(server.getConsoleSender(), "exodaily reload");
        settle();
        assertSame(before, plugin.presentation());

        Files.writeString(rewards, original, StandardCharsets.UTF_8);
        server.dispatchCommand(server.getConsoleSender(), "exodaily reload");
        await("successful reload", () -> plugin.presentation() != before);
        assertNotSame(before, plugin.presentation());
    }

    @Test
    void overviewPreviewsFutureDaysWithoutAssigningThem() {
        PlayerMock player = openMain();
        humanPause();
        click(player, ClickType.LEFT, 4);
        await("overview", () -> menu(player) != null && menu(player).type() == MenuType.OVERVIEW);
        Inventory overview = player.getOpenInventory().getTopInventory();
        assertEquals(54, overview.getSize());
        ItemStack dayOne = overview.getItem(11);
        assertEquals(Material.PURPLE_DYE, dayOne.getType());
        ItemStack daySeven = overview.getItem(20);
        assertEquals(Material.YELLOW_DYE, daySeven.getType());
        assertEquals(7, daySeven.getAmount());
        String preview = lore(daySeven);
        assertTrue(preview.contains("possible rewards") && preview.contains("weekly milestone")
                && preview.contains("not guaranteed"), preview);
        humanPause();
        click(player, ClickType.LEFT, 45);
        await("back to main", () -> menu(player) != null && menu(player).type() == MenuType.MAIN);
    }

    @Test
    void adminCanSaveTheHeldItemAsAReward() throws Exception {
        PlayerMock admin = newPlayer();
        admin.setOp(true);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(meta -> meta.displayName(Component.text("Event Blade")));
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 7);
        admin.getInventory().setItemInMainHand(sword);
        Presentation before = plugin.presentation();
        admin.performCommand("exodaily reward save event_blade");
        await("reward saved", () -> plugin.presentation() != before
                && plugin.presentation().config().rewards().rewards().containsKey("event_blade"));
        var reward = plugin.presentation().config().rewards().rewards().get("event_blade");
        assertTrue(reward.isSerialized());
        assertEquals("1 event blade", reward.summary());
        String file = Files.readString(plugin.getDataFolder().toPath().resolve(ConfigLoader.REWARDS), StandardCharsets.UTF_8);
        assertTrue(file.contains("event_blade:") && file.contains("serialized-item:"));
        ItemStack restored = ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(reward.serializedItem()));
        assertEquals(7, restored.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS));
        assertEquals("Event Blade", plain(restored.getItemMeta().displayName()));
        List<String> sent = messages(admin);
        assertTrue(sent.stream().anyMatch(m -> m.contains("saved reward event_blade")), sent.toString());
        assertTrue(sent.stream().anyMatch(m -> m.contains("add event_blade to a pool")), sent.toString());
    }
}
