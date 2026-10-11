package net.exoeconomy.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Prices custom items that the vanilla catalogue would reject. Called on the server thread while
 * building a sell preview. Return empty for items you do not own. The returned price is per single
 * item; ExoEconomy multiplies by the stack amount and applies selling multipliers only if enabled.
 */
@FunctionalInterface
public interface SellPriceProvider {
    Optional<BigDecimal> price(Player seller, ItemStack item);
}
