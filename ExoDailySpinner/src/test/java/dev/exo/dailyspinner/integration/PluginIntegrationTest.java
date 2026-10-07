package dev.exo.dailyspinner.integration;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.menu.AdminMenu;
import dev.exo.dailyspinner.menu.GuiItems;
import dev.exo.dailyspinner.menu.MainMenu;
import dev.exo.dailyspinner.menu.PreviewMenu;
import dev.exo.dailyspinner.menu.SpinnerMenu;
import dev.exo.dailyspinner.reward.RewardDefinition;
import dev.exo.dailyspinner.storage.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests against MockBukkit (an in-memory Bukkit implementation). These exercise the real
 * plugin: config loading, menus, click protection, spins, persistence, claims and reloads.
 * MockBukkit is not a real Paper server; see TESTING.md for the manual server checklist.
 */
class PluginIntegrationTest {

    private static final String DIAMOND_ONLY = """
            rarities:
              rare: { name: "<aqua>Rare", pane: LIGHT_BLUE_STAINED_GLASS_PANE, order: 3, announce: true }
            rewards:
              diamonds:
                weight: 10
                rarity: rare
                type: item
                item: { material: DIAMOND, amount: 5 }
            """;

    private ServerMock server;
    private ExoDailySpinner plugin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ExoDailySpinner.class);
        // Faster animation for tests.
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String text = Files.readString(config)
                .replace("steps: 46", "steps: 12")
                .replace("max-delay-ticks: 9", "max-delay-ticks: 3")
                .replace("click-cooldown-ms: 150", "click-cooldown-ms: 0")
                .replace("command-cooldown-ms: 600", "command-cooldown-ms: 0")
                .replace("resume-delay-ticks: 40", "resume-delay-ticks: 5");
        Files.writeString(config, text);
        useRewards(DIAMOND_ONLY);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------ helpers

    private void drain() {
        try {
            plugin.database().submit(repo -> null).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drain();
            server.getScheduler().performOneTick();
        }
        drain();
        server.getScheduler().performOneTick();
    }

    private void waitFor(BooleanSupplier condition, int maxTicks, String what) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            tick(1);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    private void useRewards(String yaml) throws Exception {
        Files.writeString(plugin.getDataFolder().toPath().resolve("rewards.yml"), yaml, StandardCharsets.UTF_8);
        plugin.reload(server.getConsoleSender());
        waitFor(() -> !plugin.isReloading(), 2000, "reload");
    }

    private Object top(PlayerMock player) {
        return dev.exo.dailyspinner.menu.Holders.of(player.getOpenInventory().getTopInventory());
    }

    private InventoryClickEvent click(PlayerMock player, int slot, ClickType type) {
        InventoryClickEvent event = player.simulateInventoryClick(player.getOpenInventory(), type, slot);
        tick(1);
        return event;
    }

    private int count(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material && !GuiItems.isGuiItem(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private PlayerData data(UUID id) {
        try {
            return plugin.database().submit(repo -> repo.loadPlayer(id)).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private SpinnerMenu openSpinner(PlayerMock player) {
        player.performCommand("ds");
        waitFor(() -> top(player) instanceof MainMenu, 50, "main menu");
        assertEquals(9, player.getOpenInventory().getTopInventory().getSize());
        click(player, 4, ClickType.LEFT);
        waitFor(() -> top(player) instanceof SpinnerMenu, 50, "spinner menu");
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
        return (SpinnerMenu) top(player);
    }

    private void drainMessages(PlayerMock player) {
        while (player.nextComponentMessage() != null) {
            // discard
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void defaultConfigurationLoads() throws Exception {
        String defaults;
        try (var in = plugin.getResource("rewards.yml")) {
            defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        useRewards(defaults);
        assertEquals(10, plugin.bundle().rewards().rewards().size());
        double sum = plugin.bundle().rewards().rewards().stream()
                .mapToDouble(r -> plugin.bundle().rewards().probability(r.id())).sum();
        assertEquals(1.0, sum, 1e-9);
        assertEquals(10.0 / 143.0, plugin.bundle().rewards().probability("diamonds"), 1e-12);
    }

    @Test
    void fullSpinDeliversExactlyOnceAndStartsCooldown() {
        PlayerMock player = server.addPlayer();
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        waitFor(() -> menu.state() == SpinnerMenu.State.SPINNING, 20, "spinning");
        // Spam the spin button while it spins: must be ignored.
        for (int i = 0; i < 5; i++) {
            click(player, 22, ClickType.LEFT);
        }
        waitFor(() -> count(player, Material.DIAMOND) > 0, 300, "delivery");
        tick(100);
        assertEquals(5, count(player, Material.DIAMOND));
        PlayerData data = data(player.getUniqueId());
        assertNotNull(data.lastDailySpin());
        assertFalse(data.activeSpin());
        // Winner landed under the pointer.
        ItemStack centre = player.getOpenInventory().getTopInventory().getItem(13);
        assertNotNull(centre);
        assertEquals(Material.DIAMOND, centre.getType());

        // Second attempt: on cooldown, nothing more given.
        drainMessages(player);
        waitFor(() -> menu.state() == SpinnerMenu.State.IDLE, 100, "idle");
        click(player, 22, ClickType.LEFT);
        tick(20);
        assertEquals(5, count(player, Material.DIAMOND));
    }

    @Test
    void menuItemsCannotBeTakenByAnyClickType() {
        PlayerMock player = server.addPlayer();
        player.performCommand("ds");
        waitFor(() -> top(player) instanceof MainMenu, 50, "main menu");
        InventoryView view = player.getOpenInventory();
        for (ClickType type : ClickType.values()) {
            for (int slot : new int[]{0, 2, 4, 6, 8, 20}) {
                if (slot >= view.countSlots()) {
                    continue;
                }
                InventoryClickEvent event = new InventoryClickEvent(view, slot < 9 ? InventoryType.SlotType.CONTAINER
                        : InventoryType.SlotType.CONTAINER, slot, type, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
                server.getPluginManager().callEvent(event);
                assertTrue(event.isCancelled(), "click " + type + " on slot " + slot + " must be cancelled");
            }
        }
        InventoryDragEvent drag = new InventoryDragEvent(view, new ItemStack(Material.STONE), new ItemStack(Material.STONE),
                false, Map.of(3, new ItemStack(Material.STONE)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player, new ItemStack(Material.STONE), new ItemStack(Material.DIRT));
        server.getPluginManager().callEvent(swap);
        assertTrue(swap.isCancelled());
        tick(5);
        for (ItemStack item : player.getInventory().getContents()) {
            assertFalse(GuiItems.isGuiItem(item), "no menu item may end up in the player inventory");
        }
    }

    @Test
    void closingMidSpinDeliversImmediatelyWithoutReroll() {
        PlayerMock player = server.addPlayer();
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        waitFor(() -> menu.state() == SpinnerMenu.State.SPINNING, 20, "spinning");
        tick(2);
        player.closeInventory();
        waitFor(() -> count(player, Material.DIAMOND) == 5, 40, "instant delivery on close");
        // Re-opening must not offer another spin or deliver again.
        openSpinner(player);
        tick(200);
        assertEquals(5, count(player, Material.DIAMOND));
        assertFalse(data(player.getUniqueId()).activeSpin());
    }

    @Test
    void disconnectMidSpinResumesSameRewardOnJoin() {
        PlayerMock player = server.addPlayer();
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        waitFor(() -> menu.state() == SpinnerMenu.State.SPINNING, 20, "spinning");
        player.disconnect();
        tick(50);
        assertTrue(data(player.getUniqueId()).activeSpin(), "spin stays reserved while offline");
        assertEquals(0, count(player, Material.DIAMOND));
        player.reconnect();
        waitFor(() -> count(player, Material.DIAMOND) == 5, 100, "resume on join");
        tick(100);
        assertEquals(5, count(player, Material.DIAMOND), "delivered exactly once");
        assertFalse(data(player.getUniqueId()).activeSpin());
    }

    @Test
    void fullInventoryStoresOverflowAndClaimWorks() {
        PlayerMock player = server.addPlayer();
        // Fill every slot (MockBukkit's addItem also uses armour/offhand slots, unlike Paper).
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        }
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        waitFor(() -> data(player.getUniqueId()).pendingItems() == 1 && !plugin.spins().isBusy(player.getUniqueId()),
                300, "overflow stored");
        assertEquals(0, count(player, Material.DIAMOND));
        assertTrue(player.getWorld().getEntities().stream().noneMatch(e -> e instanceof org.bukkit.entity.Item),
                "overflow must never be dropped on the ground");
        player.closeInventory();

        // Claiming with a still-full inventory keeps the reward stored.
        player.performCommand("ds claim");
        tick(10);
        assertEquals(1, data(player.getUniqueId()).pendingItems());

        player.getInventory().setItem(0, null);
        // Rapid repeated claims: only one may run, and items are given once.
        player.performCommand("ds claim");
        player.performCommand("ds claim");
        player.performCommand("ds claim");
        tick(20);
        assertEquals(5, count(player, Material.DIAMOND));
        assertEquals(0, data(player.getUniqueId()).pendingItems());
        player.performCommand("ds claim");
        tick(10);
        assertEquals(5, count(player, Material.DIAMOND));
    }

    @Test
    void bonusSpinsAndResetWork() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.performCommand("ds give " + player.getName() + " 2");
        tick(5);
        assertEquals(2, data(player.getUniqueId()).bonusSpins());
        player.performCommand("ds give " + player.getName() + " 0");
        player.performCommand("ds give " + player.getName() + " -5");
        player.performCommand("ds give " + player.getName() + " abc");
        tick(5);
        assertEquals(2, data(player.getUniqueId()).bonusSpins());

        for (int spin = 0; spin < 3; spin++) {
            SpinnerMenu menu = openSpinner(player);
            click(player, 22, ClickType.LEFT);
            int expected = (spin + 1) * 5;
            waitFor(() -> count(player, Material.DIAMOND) == expected, 300, "spin " + spin);
            player.closeInventory();
            tick(2);
        }
        PlayerData after = data(player.getUniqueId());
        assertEquals(0, after.bonusSpins(), "daily first, then two bonus spins");
        // Nothing left now.
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        tick(60);
        assertEquals(15, count(player, Material.DIAMOND));
        player.closeInventory();

        player.performCommand("ds reset " + player.getName());
        tick(5);
        assertNull(data(player.getUniqueId()).lastDailySpin());
    }

    @Test
    void invalidReloadKeepsWorkingConfiguration() throws Exception {
        var before = plugin.bundle();
        useRewards("""
                rewards:
                  bad:
                    weight: -3
                    item: { material: DIAMOND }
                  bad2:
                    weight: .nan
                    item: { material: NOT_A_MATERIAL }
                """);
        assertSame(before, plugin.bundle(), "invalid reload must not replace the active configuration");
        useRewards("""
                rewards:
                  Upper:
                    weight: 1
                    item: { material: DIAMOND }
                """);
        assertSame(before, plugin.bundle());
        useRewards("rewards: [broken");
        assertSame(before, plugin.bundle());
    }

    @Test
    void noRewardsMeansNoSpinConsumed() throws Exception {
        useRewards("rewards: {}\n");
        assertTrue(plugin.bundle().rewards().isEmpty());
        PlayerMock player = server.addPlayer();
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        tick(20);
        assertNull(data(player.getUniqueId()).lastDailySpin(), "no entitlement consumed without rewards");
        assertEquals(SpinnerMenu.State.IDLE, menu.state());
    }

    @Test
    void addHandCopiesItemWithoutTouchingIt() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("Blade of Testing"));
        meta.lore(List.of(Component.text("line one")));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.getPersistentDataContainer().set(new NamespacedKey("test", "tag"), PersistentDataType.STRING, "kept");
        sword.setItemMeta(meta);
        player.getInventory().setItemInMainHand(sword.clone());

        player.performCommand("ds reward addhand blade 2.5");
        tick(5);
        RewardDefinition reward = plugin.bundle().rewards().get("blade");
        assertNotNull(reward);
        assertEquals(2.5, reward.weight());
        assertEquals(sword, player.getInventory().getItemInMainHand(), "held item must be unchanged");
        ItemStack stored = reward.itemTemplate();
        assertEquals(Material.DIAMOND_SWORD, stored.getType());
        assertEquals(5, stored.getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals("kept", stored.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey("test", "tag"), PersistentDataType.STRING));

        // Duplicate id and invalid weights are rejected.
        player.performCommand("ds reward addhand blade 1");
        player.performCommand("ds reward addhand other NaN");
        player.performCommand("ds reward addhand other2 Infinity");
        player.performCommand("ds reward addhand other3 0");
        tick(5);
        assertEquals(2, plugin.bundle().rewards().rewards().size());

        player.performCommand("ds reward additem gold gold_ingot 32 4");
        player.performCommand("ds reward additem badmat not_a_thing 1 4");
        player.performCommand("ds reward additem toomany diamond 999999 4");
        tick(5);
        assertNotNull(plugin.bundle().rewards().get("gold"));
        assertNull(plugin.bundle().rewards().get("badmat"));
        assertNull(plugin.bundle().rewards().get("toomany"));

        player.performCommand("ds reward setweight gold 8");
        player.performCommand("ds reward remove blade");
        tick(5);
        assertEquals(8, plugin.bundle().rewards().get("gold").weight());
        assertNull(plugin.bundle().rewards().get("blade"));
    }

    @Test
    void commandRewardsNeedExplicitPermission() {
        PlayerMock player = server.addPlayer();
        player.setOp(true); // ops do NOT get exodailyspinner.admin.rewards.command by default
        player.performCommand("ds reward addcommand cash 5 say hello {player}");
        tick(5);
        assertNull(plugin.bundle().rewards().get("cash"));
        server.dispatchCommand(server.getConsoleSender(), "ds reward addcommand cash 5 say hello {player}");
        tick(5);
        assertNotNull(plugin.bundle().rewards().get("cash"));
        assertEquals(List.of("say hello {player}"), plugin.bundle().rewards().get("cash").commands());
    }

    @Test
    void commandRewardRunsOnceFromSnapshot() throws Exception {
        useRewards("""
                rewards:
                  bonus:
                    weight: 1
                    type: command
                    commands: ["dailyspinner give {player} 3"]
                """);
        PlayerMock player = server.addPlayer();
        SpinnerMenu menu = openSpinner(player);
        click(player, 22, ClickType.LEFT);
        waitFor(() -> data(player.getUniqueId()).bonusSpins() == 3 && !plugin.spins().isBusy(player.getUniqueId()),
                300, "command reward");
        tick(100);
        assertEquals(3, data(player.getUniqueId()).bonusSpins());
    }

    @Test
    void previewAndAdminMenusOpenWithPermissions() {
        PlayerMock player = server.addPlayer();
        player.performCommand("ds preview");
        waitFor(() -> top(player) instanceof PreviewMenu, 20, "preview");
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
        ItemStack first = player.getOpenInventory().getTopInventory().getItem(10);
        assertNotNull(first);
        assertEquals(Material.DIAMOND, first.getType());
        player.closeInventory();
        player.performCommand("ds admin");
        tick(5);
        assertFalse(top(player) instanceof AdminMenu, "non-admins cannot open the admin menu");
        player.setOp(true);
        player.performCommand("ds admin");
        waitFor(() -> top(player) instanceof AdminMenu, 20, "admin menu");
    }

    @Test
    void staleMenuClicksAreIgnored() {
        PlayerMock player = server.addPlayer();
        player.performCommand("ds");
        waitFor(() -> top(player) instanceof MainMenu, 50, "main menu");
        InventoryView oldView = player.getOpenInventory();
        player.performCommand("ds preview");
        waitFor(() -> top(player) instanceof PreviewMenu, 20, "preview");
        // A click arriving for the old (no longer registered) main menu does nothing.
        InventoryClickEvent stale = new InventoryClickEvent(oldView, InventoryType.SlotType.CONTAINER, 4, ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(stale);
        assertTrue(stale.isCancelled());
        tick(10);
        assertTrue(top(player) instanceof PreviewMenu, "stale click must not open the spinner");
    }
}
