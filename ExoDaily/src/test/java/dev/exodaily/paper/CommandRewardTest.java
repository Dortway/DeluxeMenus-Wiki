package dev.exodaily.paper;

import dev.exodaily.core.claim.ClaimParticipant;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.reward.RewardType;
import dev.exodaily.core.storage.ClaimKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command rewards against MockBukkit's real command map and inventories. */
class CommandRewardTest {

    private ServerMock server;
    private ExoDailyPlugin plugin;
    private final List<String> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.loadWith(PaperIntegrationTest.TestPlugin.class,
                CommandRewardTest.class.getResourceAsStream("/plugin.yml"));
        server.getCommandMap().register("exotest", new Command("exotest") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                executed.add(String.join(" ", args));
                return true;
            }
        });
        server.getCommandMap().register("exotest", new Command("exofail") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                executed.add("fail");
                throw new IllegalStateException("integration is down");
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PaperClaimParticipant participant(PlayerMock player) {
        return new PaperClaimParticipant(player, new ItemFactory(new NamespacedKey(plugin, "menu_item")),
                () -> plugin.presentation().styler(), Permissions.PREMIUM, plugin.getLogger());
    }

    private static RewardDefinition reward(RewardType type, String... commands) {
        return new RewardDefinition("money", "GOLD_NUGGET", 1, null, List.of(), Map.of(), List.of(), null, "$500", 10,
                null, type, List.of(commands));
    }

    private static int items(PlayerMock player) {
        return Arrays.stream(player.getInventory().getStorageContents())
                .filter(item -> item != null && !item.isEmpty()).mapToInt(ItemStack::getAmount).sum();
    }

    @Test
    void commandRewardRunsCommandsWithPlaceholdersAndGivesNoItem() {
        PlayerMock player = server.addPlayer();
        ClaimKey key = new ClaimKey(player.getUniqueId(), 2, 7, RewardPosition.PREMIUM_ONE);
        RewardDefinition reward = reward(RewardType.COMMAND,
                "exotest pay {player} 500", "exotest {claim_id} {uuid} {cycle} {day} {position} {reward}");
        PaperClaimParticipant participant = participant(player);
        assertEquals(ClaimParticipant.DeliveryCheck.OK, participant.check(reward));
        assertEquals(ClaimParticipant.DeliveryResult.DELIVERED, participant.deliver(reward, key));
        assertEquals(List.of("pay " + player.getName() + " 500",
                key.asString() + " " + player.getUniqueId() + " 2 7 2 money"), executed);
        assertEquals(0, items(player));
    }

    @Test
    void commandRewardNeedsNoInventorySpace() {
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        RewardDefinition reward = reward(RewardType.COMMAND, "exotest hi");
        assertEquals(ClaimParticipant.DeliveryCheck.OK, participant(player).check(reward));
    }

    @Test
    void bothGivesItemsFirstAndRunsNothingWhenItemsDoNotFit() {
        PlayerMock player = server.addPlayer();
        ClaimKey key = new ClaimKey(player.getUniqueId(), 1, 1, RewardPosition.STANDARD);
        RewardDefinition both = reward(RewardType.BOTH, "exotest bonus {player}");
        assertEquals(ClaimParticipant.DeliveryResult.DELIVERED, participant(player).deliver(both, key));
        assertEquals(1, items(player));
        assertEquals(List.of("bonus " + player.getName()), executed);

        executed.clear();
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        assertEquals(ClaimParticipant.DeliveryCheck.NO_SPACE, participant(player).check(both));
        assertEquals(ClaimParticipant.DeliveryResult.NOT_DELIVERED_NO_SPACE, participant(player).deliver(both, key));
        assertTrue(executed.isEmpty(), "commands must not run when the items could not be given");
    }

    @Test
    void failingOrUnknownCommandsMakeTheClaimUncertain() {
        PlayerMock player = server.addPlayer();
        ClaimKey key = new ClaimKey(player.getUniqueId(), 1, 1, RewardPosition.STANDARD);
        RewardDefinition throwing = reward(RewardType.COMMAND, "exotest first", "exofail", "exotest never");
        assertEquals(ClaimParticipant.DeliveryResult.UNCERTAIN, participant(player).deliver(throwing, key));
        assertEquals(List.of("first", "fail"), executed, "commands stop at the first failure");

        executed.clear();
        RewardDefinition unknown = reward(RewardType.COMMAND, "no_such_command_here {player}");
        assertEquals(ClaimParticipant.DeliveryResult.UNCERTAIN, participant(player).deliver(unknown, key));
    }
}
