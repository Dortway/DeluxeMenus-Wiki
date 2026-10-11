package dev.exo.woodcube.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on the server thread after a player successfully chopped a log inside a wood cube: the drop is already in
 * the cube's storage (or counted as auto-sold) and the log is gone. Not cancellable - it reports a committed
 * harvest, so listeners (rewards, tool XP, quests, statistics) can reward exactly the logs that were really mined.
 * It is NOT fired for refused breaks (no access, wrong tool, full storage, frame/control blocks, creative mode)
 * or for Auto-Mine. Mirrors ExoCubes' {@code dev.exo.cubes.api.CubeHarvestEvent}.
 */
public final class WoodCubeHarvestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID cubeId;
    private final UUID cubeOwner;
    private final String tier;
    private final Location location;
    private final Material block;
    private final Material drop;
    private final int amount;
    private final boolean autosold;
    private final double value;

    public WoodCubeHarvestEvent(Player player, UUID cubeId, UUID cubeOwner, String tier, Location location,
                                Material block, Material drop, int amount, boolean autosold, double value) {
        this.player = player;
        this.cubeId = cubeId;
        this.cubeOwner = cubeOwner;
        this.tier = tier;
        this.location = location;
        this.block = block;
        this.drop = drop;
        this.amount = amount;
        this.autosold = autosold;
        this.value = value;
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getCubeId() {
        return cubeId;
    }

    public UUID getCubeOwner() {
        return cubeOwner;
    }

    /** Tier id from tiers.yml (starter / premium / fabled ...). */
    public String getTier() {
        return tier;
    }

    /** Location of the chopped log (a copy). */
    public Location getLocation() {
        return location.clone();
    }

    /** The log block type that was chopped. */
    public Material getBlock() {
        return block;
    }

    /** The item that went into storage (or was auto-sold); null if the configured item is not a material. */
    public Material getDrop() {
        return drop;
    }

    public int getAmount() {
        return amount;
    }

    public boolean isAutosold() {
        return autosold;
    }

    /** Sell value of this harvest in money (0 when unknown / not sellable). */
    public double getValue() {
        return value;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
