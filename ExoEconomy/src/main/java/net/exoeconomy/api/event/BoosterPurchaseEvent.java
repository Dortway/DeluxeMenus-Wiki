package net.exoeconomy.api.event;

import net.exoeconomy.api.BoosterScope;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.math.BigDecimal;

/** Fired on the server thread when a player confirms a booster purchase, before any Shards are charged. */
public final class BoosterPurchaseEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String tierId;
    private final BoosterScope scope;
    private final String activity;
    private final BigDecimal multiplier;
    private final long durationMs;
    private final BigDecimal price;
    private boolean cancelled;

    public BoosterPurchaseEvent(Player player, String tierId, BoosterScope scope, String activity, BigDecimal multiplier, long durationMs, BigDecimal price) {
        super(player);
        this.tierId = tierId; this.scope = scope; this.activity = activity; this.multiplier = multiplier; this.durationMs = durationMs; this.price = price;
    }

    public String getTierId() { return tierId; }
    public BoosterScope getScope() { return scope; }
    public String getActivity() { return activity; }
    public BigDecimal getMultiplier() { return multiplier; }
    public long getDurationMs() { return durationMs; }
    public BigDecimal getPrice() { return price; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
