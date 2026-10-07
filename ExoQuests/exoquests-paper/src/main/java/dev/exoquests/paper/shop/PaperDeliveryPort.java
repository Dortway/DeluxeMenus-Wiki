package dev.exoquests.paper.shop;

import dev.exoquests.core.shop.CommandTemplate;
import dev.exoquests.core.shop.DeliveryPort;
import dev.exoquests.core.shop.RewardType;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Paper implementation of purchase checks and delivery. Every method except mainThread runs on the main thread. */
public final class PaperDeliveryPort implements DeliveryPort {

    private static final String SEPARATOR = "\n";

    private final ExoQuestsPlugin plugin;

    public PaperDeliveryPort(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Executor mainThread() {
        return plugin.mainExecutor();
    }

    @Override
    public Check precheck(UUID uuid, ShopEntry entry) {
        if (!safeToTouchServer()) {
            return Check.OFFLINE;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return Check.OFFLINE;
        }
        if (!player.hasPermission("exoquests.shop")
                || (entry.permission() != null && !player.hasPermission(entry.permission()))) {
            return Check.NO_PERMISSION;
        }
        return precheckSnapshot(uuid, entry.type(), snapshot(entry));
    }

    @Override
    public Check precheckSnapshot(UUID uuid, RewardType type, String snapshot) {
        if (!safeToTouchServer()) {
            // During shutdown the purchase stays PENDING and is delivered on the player's next join.
            return Check.OFFLINE;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return Check.OFFLINE;
        }
        if (type == RewardType.ITEM) {
            List<ItemStack> stacks;
            try {
                stacks = ItemRewards.decode(snapshot);
            } catch (RuntimeException e) {
                return Check.UNAVAILABLE;
            }
            return InventorySpace.fits(player, stacks) ? Check.OK : Check.NO_SPACE;
        }
        if (!plugin.configs().current().settings().shop().commandPlayerName().matcher(player.getName()).matches()) {
            return Check.INVALID_NAME;
        }
        return Check.OK;
    }

    private boolean safeToTouchServer() {
        return !plugin.isShuttingDown() && Bukkit.isPrimaryThread();
    }

    @Override
    public String snapshot(ShopEntry entry) {
        if (entry.type() == RewardType.ITEM) {
            return ItemRewards.encode(ItemRewards.build(entry.item(), plugin.text()));
        }
        return String.join(SEPARATOR, entry.commands());
    }

    @Override
    public void deliver(UUID uuid, String purchaseId, String itemId, int price, RewardType type, String snapshot)
            throws Exception {
        if (!safeToTouchServer()) {
            throw new IllegalStateException("delivery attempted off the main thread or during shutdown");
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            throw new IllegalStateException("player went offline during delivery");
        }
        if (type == RewardType.ITEM) {
            ItemStack[] stacks = ItemRewards.decode(snapshot).toArray(ItemStack[]::new);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stacks);
            if (!leftover.isEmpty()) {
                // Space was verified on this same tick, so this only happens if another plugin intervened.
                // Dropping at the player's feet avoids losing a paid reward.
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
                plugin.getLogger().warning("Purchase " + purchaseId + ": " + leftover.size()
                        + " stack(s) did not fit and were dropped at " + player.getName() + "'s location.");
            }
            return;
        }
        Map<String, String> values = Map.of(
                "player", player.getName(),
                "uuid", uuid.toString(),
                "item_id", itemId,
                "purchase_id", purchaseId,
                "price", Integer.toString(price));
        for (String template : snapshot.split(SEPARATOR)) {
            String command = CommandTemplate.render(template, values);
            plugin.getLogger().info("Purchase " + purchaseId + " running console command: " + command);
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                throw new IllegalStateException("console command failed: " + command);
            }
        }
    }
}
