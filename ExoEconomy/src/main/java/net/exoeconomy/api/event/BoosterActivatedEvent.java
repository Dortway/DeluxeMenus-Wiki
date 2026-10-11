package net.exoeconomy.api.event;

import net.exoeconomy.api.BoosterInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread when a booster starts running (purchase, grant, or leaving the queue) or is extended. */
public final class BoosterActivatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final BoosterInfo booster;
    private final boolean extension;

    public BoosterActivatedEvent(BoosterInfo booster, boolean extension) {
        this.booster = booster; this.extension = extension;
    }

    public BoosterInfo getBooster() { return booster; }
    /** True when an existing booster was extended instead of a new one starting. */
    public boolean isExtension() { return extension; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
