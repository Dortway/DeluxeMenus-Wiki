package net.exoeconomy.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Veto for /sell. Return a short reason (shown to the player) to refuse an item, or empty to allow
 * it. Use it for quest items, bound items or anything that must never be sold, even if it looks
 * like a plain vanilla item. Called on the server thread.
 */
@FunctionalInterface
public interface SellItemFilter {
    Optional<String> reject(Player seller, ItemStack item);
}
