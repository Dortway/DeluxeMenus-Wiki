package net.exoeconomy.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.math.BigDecimal;
import java.util.Map;

/** Fired on the server thread when a player confirms a sale, before items are taken or Shards paid. */
public final class SellRequestEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Map<String, Integer> items;
    private final BigDecimal payout;
    private boolean cancelled;

    public SellRequestEvent(Player player, Map<String, Integer> items, BigDecimal payout) {
        super(player);
        this.items = Map.copyOf(items); this.payout = payout;
    }

    /** Sold item counts keyed by material name (or provider key for custom items). */
    public Map<String, Integer> getItems() { return items; }
    public BigDecimal getPayout() { return payout; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
