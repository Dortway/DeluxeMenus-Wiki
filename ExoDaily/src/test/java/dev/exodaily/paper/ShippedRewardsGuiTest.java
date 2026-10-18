package dev.exodaily.paper;

import dev.exodaily.paper.menu.ExoMenu;
import dev.exodaily.paper.menu.MenuType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Claims a shipped command reward through the GUI, with stand-ins for the external plugins' commands. */
class ShippedRewardsGuiTest {

    private ServerMock server;
    private ExoDailyPlugin plugin;
    private final List<String> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        for (String label : List.of("xp", "eco", "shards", "crate", "ebox")) {
            server.getCommandMap().register("stub", new Command(label) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String used, @NotNull String[] args) {
                    executed.add(label + " " + String.join(" ", args));
                    return true;
                }
            });
        }
        plugin = MockBukkit.loadWith(PaperIntegrationTest.TestPlugin.class,
                ShippedRewardsGuiTest.class.getResourceAsStream("/plugin.yml"));
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
            }
        }
    }

    private void pause() {
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

    private static ExoMenu menu(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof ExoMenu menu ? menu : null;
    }

    private void click(PlayerMock player, int slot) {
        server.getPluginManager().callEvent(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    @Test
    void claimingRunsTheRewardCommandOnceAndGivesNoItems() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, Permissions.USE, true);
        player.addAttachment(plugin, Permissions.PREMIUM, true);
        player.performCommand("daily");
        await("main menu", () -> menu(player) != null);
        pause();
        click(player, 13);
        await("details", () -> menu(player) != null && menu(player).type() == MenuType.DETAILS);

        for (int i = 0; i < 3; i++) {
            pause();
            click(player, 20 + i * 2);
            int expected = i + 1;
            await("command " + expected, () -> executed.size() >= expected);
        }
        for (int i = 0; i < 6; i++) {
            pause();
            click(player, 20 + (i % 3) * 2); // already claimed: must not run again
        }
        pause();
        assertEquals(3, executed.size(), executed.toString());
        assertTrue(executed.stream().allMatch(command -> command.contains(player.getName())), executed.toString());
        assertTrue(Arrays.stream(player.getInventory().getStorageContents())
                .allMatch(item -> item == null || item.isEmpty()), "command rewards give no items");
    }
}
