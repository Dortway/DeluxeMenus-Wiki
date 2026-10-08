package dev.exodaily.paper;

import dev.exodaily.core.claim.ClaimParticipant;
import dev.exodaily.core.delivery.InventoryFit;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.text.TextStyler;
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

/** Live player checks and all-or-nothing delivery into the main inventory. Server thread only. */
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
    public DeliveryResult deliver(RewardDefinition reward) {
        Optional<List<ItemStack>> stacks = items.rewardStacks(reward, styler.get());
        if (stacks.isEmpty()) {
            return DeliveryResult.NOT_DELIVERED_INVALID;
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
