package net.exoeconomy.api.event;

import net.exoeconomy.api.BoosterInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread when a booster runs out. */
public final class BoosterExpiredEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final BoosterInfo booster;

    public BoosterExpiredEvent(BoosterInfo booster) { this.booster = booster; }

    public BoosterInfo getBooster() { return booster; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
