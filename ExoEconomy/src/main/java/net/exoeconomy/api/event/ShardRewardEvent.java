package net.exoeconomy.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.math.BigDecimal;

/**
 * Fired on the server thread for every eligible activity reward, before boosters, caps and
 * batching. Cancel it to award nothing (e.g. to stop another plugin's double rewards), or change the
 * base amount. The amount must stay between 0 and 1,000,000.
 */
public final class ShardRewardEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private static final BigDecimal MAX = new BigDecimal("1000000");
    private final String activity;
    private final String key;
    private BigDecimal baseAmount;
    private boolean cancelled;

    public ShardRewardEvent(Player player, String activity, String key, BigDecimal baseAmount) {
        super(player);
        this.activity = activity; this.key = key; this.baseAmount = baseAmount;
    }

    /** "mining", "farming", "slaying" or a custom activity id. */
    public String getActivity() { return activity; }
    /** Material, entity type or custom key that produced the reward. */
    public String getKey() { return key; }
    public BigDecimal getBaseAmount() { return baseAmount; }

    public void setBaseAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX) > 0) throw new IllegalArgumentException("Reward must be 0..1000000");
        this.baseAmount = amount;
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
