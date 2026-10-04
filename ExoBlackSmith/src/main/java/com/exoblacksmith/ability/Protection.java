package com.exoblacksmith.ability;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;

/**
 * Protection checks that defer to other plugins through standard Bukkit events rather than any
 * plugin-specific API.
 */
public final class Protection {
    private Protection() {
    }

    /**
     * Asks protection plugins whether {@code player} may place {@code type} at {@code block} by firing a
     * synthetic {@link BlockPlaceEvent} (the same technique most plugins use). Also honours vanilla spawn
     * protection and the world border, which a synthetic event does not cover.
     */
    public static boolean canPlace(Player player, Block block, Material type, boolean spawnProtection) {
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        if (!block.getWorld().getWorldBorder().isInside(loc)) {
            return false;
        }
        if (block.getY() < block.getWorld().getMinHeight() || block.getY() >= block.getWorld().getMaxHeight()) {
            return false;
        }
        if (spawnProtection && !player.isOp() && block.getWorld().equals(Bukkit.getWorlds().getFirst())) {
            int radius = Bukkit.getSpawnRadius();
            Location spawn = block.getWorld().getSpawnLocation();
            if (radius > 0 && Math.max(Math.abs(block.getX() - spawn.getBlockX()), Math.abs(block.getZ() - spawn.getBlockZ())) <= radius) {
                return false;
            }
        }
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN),
                ItemStack.of(type), player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled() && event.canBuild();
    }

    /**
     * Pre-checks before targeting another player. Region PvP rules are enforced later because all summon
     * damage is re-dealt as the owner's own attack, which protection plugins evaluate normally.
     */
    public static boolean canTarget(Player owner, Player target, boolean respectTeams) {
        if (owner.equals(target) || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR || target.isInvulnerable()) {
            return false;
        }
        if (!owner.getWorld().equals(target.getWorld()) || !owner.getWorld().getPVP() || !owner.canSee(target)) {
            return false;
        }
        if (respectTeams) {
            Team team = owner.getScoreboard().getEntryTeam(owner.getName());
            if (team != null && team.hasEntry(target.getName())) {
                return false;
            }
        }
        return true;
    }
}
