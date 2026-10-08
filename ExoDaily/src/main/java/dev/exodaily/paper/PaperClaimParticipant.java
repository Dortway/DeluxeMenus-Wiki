package dev.exodaily.paper;

import dev.exodaily.core.claim.ClaimParticipant;
import dev.exodaily.core.delivery.InventoryFit;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.storage.ClaimKey;
import dev.exodaily.core.text.TextStyler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Live player checks, all-or-nothing item delivery into the main inventory, then reward commands. Server thread only. */
public final class PaperClaimParticipant implements ClaimParticipant {

    private final Player player;
    private final ItemFactory items;
    private final Supplier<TextStyler> styler;
    private final String premiumPermission;
    private final Logger logger;

    public PaperClaimParticipant(Player player, ItemFactory items, Supplier<TextStyler> styler,
                                 String premiumPermission, Logger logger) {
        this.player = player;
        this.items = items;
        this.styler = styler;
        this.premiumPermission = premiumPermission;
        this.logger = logger;
    }

    @Override
    public boolean isOnline() {
        return player.isOnline();
    }

    @Override
    public boolean hasPremium() {
        return player.hasPermission(premiumPermission);
    }

    @Override
    public DeliveryCheck check(RewardDefinition reward) {
        Optional<List<ItemStack>> stacks = items.rewardStacks(reward, styler.get());
        if (stacks.isEmpty()) {
            return DeliveryCheck.INVALID_ITEM;
        }
        return fits(player.getInventory(), stacks.get()) ? DeliveryCheck.OK : DeliveryCheck.NO_SPACE;
    }

    @Override
    public DeliveryResult deliver(RewardDefinition reward, ClaimKey key) {
        DeliveryResult itemResult = deliverItems(reward);
        if (itemResult != DeliveryResult.DELIVERED || !reward.type().runsCommands()) {
            return itemResult;
        }
        return runCommands(reward, key);
    }

    /**
     * Runs the reward's console commands in order. Commands cannot be rolled back, so any failure
     * (an exception, or the command reporting failure) after delivery started makes the claim
     * UNCERTAIN: it stays blocked and is never re-run automatically.
     */
    private DeliveryResult runCommands(RewardDefinition reward, ClaimKey key) {
        CommandSender console = player.getServer().getConsoleSender();
        List<String> commands = reward.commands();
        for (int i = 0; i < commands.size(); i++) {
            String command = expand(commands.get(i), reward, key);
            boolean ok;
            try {
                ok = player.getServer().dispatchCommand(console, command);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Reward command threw for claim " + key + ": /" + command, e);
                ok = false;
            }
            if (!ok) {
                logger.severe("Reward command failed for claim " + key + " (reward '" + reward.id() + "', command "
                        + (i + 1) + " of " + commands.size() + "): /" + command + ". Earlier commands and items cannot be"
                        + " undone; the claim is flagged for review (/exodaily pending).");
                return DeliveryResult.UNCERTAIN;
            }
        }
        logger.info("Ran " + commands.size() + " reward command(s) for " + player.getName() + " (claim " + key
                + ", reward '" + reward.id() + "')");
        return DeliveryResult.DELIVERED;
    }

    /** Replaces command placeholders. Player names contain only letters, digits and underscores. */
    static String expand(String template, Player player, RewardDefinition reward, ClaimKey key) {
        return template
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{claim_id}", key.asString())
                .replace("{cycle}", Integer.toString(key.cycle()))
                .replace("{day}", Integer.toString(key.day()))
                .replace("{position}", Integer.toString(key.position().number()))
                .replace("{reward}", reward.id());
    }

    private String expand(String template, RewardDefinition reward, ClaimKey key) {
        return expand(template, player, reward, key);
    }

    private DeliveryResult deliverItems(RewardDefinition reward) {
        Optional<List<ItemStack>> stacks = items.rewardStacks(reward, styler.get());
        if (stacks.isEmpty()) {
            return DeliveryResult.NOT_DELIVERED_INVALID;
        }
        if (stacks.get().isEmpty()) {
            return DeliveryResult.DELIVERED;
        }
        PlayerInventory inventory = player.getInventory();
        if (!fits(inventory, stacks.get())) {
            return DeliveryResult.NOT_DELIVERED_NO_SPACE;
        }
        ItemStack[] before = deepCopy(inventory.getStorageContents());
        try {
            ItemStack[] toAdd = stacks.get().stream().map(ItemStack::clone).toArray(ItemStack[]::new);
            HashMap<Integer, ItemStack> leftovers = inventory.addItem(toAdd);
            if (!leftovers.isEmpty()) {
                // Never drop items: undo the partial add so the inventory is exactly as before.
                inventory.setStorageContents(before);
                logger.warning("Reward '" + reward.id() + "' did not fully fit for " + player.getName()
                        + " despite the space check; the inventory was restored and the reward stays available.");
                return DeliveryResult.NOT_DELIVERED_NO_SPACE;
            }
            return DeliveryResult.DELIVERED;
        } catch (RuntimeException e) {
            try {
                inventory.setStorageContents(before);
                logger.log(Level.WARNING, "Error while delivering reward '" + reward.id() + "' to " + player.getName()
                        + "; the inventory was restored", e);
                return DeliveryResult.NOT_DELIVERED_INVALID;
            } catch (RuntimeException restoreFailure) {
                e.addSuppressed(restoreFailure);
                logger.log(Level.SEVERE, "Error while delivering reward '" + reward.id() + "' to " + player.getName()
                        + " and the inventory could not be restored; delivery is uncertain", e);
                return DeliveryResult.UNCERTAIN;
            }
        }
    }

    static boolean fits(PlayerInventory inventory, List<ItemStack> stacks) {
        int inventoryMax = inventory.getMaxStackSize();
        InventoryFit.StackOps<ItemStack> ops = new InventoryFit.StackOps<>() {
            @Override
            public boolean isEmpty(ItemStack stack) {
                return stack.isEmpty();
            }

            @Override
            public int amount(ItemStack stack) {
                return stack.getAmount();
            }

            @Override
            public int maxStackSize(ItemStack stack) {
                return Math.min(stack.getMaxStackSize(), inventoryMax);
            }

            @Override
            public boolean similar(ItemStack a, ItemStack b) {
                return a.isSimilar(b);
            }
        };
        return InventoryFit.fits(Arrays.asList(inventory.getStorageContents()), stacks, ops);
    }

    private static ItemStack[] deepCopy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }
}
