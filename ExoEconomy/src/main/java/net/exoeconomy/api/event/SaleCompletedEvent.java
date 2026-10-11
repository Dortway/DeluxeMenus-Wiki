package net.exoeconomy.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.math.BigDecimal;
import java.util.Map;

/** Fired on the server thread after ExcellentEconomy confirmed a sale payout. */
public final class SaleCompletedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String transactionId;
    private final Map<String, Integer> items;
    private final BigDecimal payout;

    public SaleCompletedEvent(Player player, String transactionId, Map<String, Integer> items, BigDecimal payout) {
        super(player);
        this.transactionId = transactionId; this.items = Map.copyOf(items); this.payout = payout;
    }

    public String getTransactionId() { return transactionId; }
    public Map<String, Integer> getItems() { return items; }
    public BigDecimal getPayout() { return payout; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
