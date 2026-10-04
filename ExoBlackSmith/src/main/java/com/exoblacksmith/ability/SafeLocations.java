package com.exoblacksmith.ability;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Finds a standing spot that will not suffocate, burn or drop the player out of the world. */
public final class SafeLocations {
    private SafeLocations() {
    }

    public static Location find(Location target, float yaw, float pitch) {
        World world = target.getWorld();
        if (world == null || !world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
            return null;
        }
        if (!world.getWorldBorder().isInside(target)) {
            return null;
        }
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int startY = target.getBlockY();
        for (int dy = 0; dy <= 4; dy++) {
            Location candidate = check(world, x, startY - dy, z);
            if (candidate != null) {
                candidate.setYaw(yaw);
                candidate.setPitch(pitch);
                return candidate;
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            Location candidate = check(world, x, startY + dy, z);
            if (candidate != null) {
                candidate.setYaw(yaw);
                candidate.setPitch(pitch);
                return candidate;
            }
        }
        return null;
    }

    private static Location check(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return null;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        if (!clear(feet) || !clear(head) || !ground.getType().isSolid() || hazardous(ground.getType())) {
            return null;
        }
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        return world.getWorldBorder().isInside(loc) ? loc : null;
    }

    private static boolean clear(Block block) {
        Material type = block.getType();
        if (block.isLiquid() || hazardous(type) || type == Material.COBWEB || type == Material.POWDER_SNOW
                || type == Material.SCAFFOLDING) {
            return false;
        }
        // Non-solid blocks (air, plants, signs...) never collide; solid ones (e.g. open doors or fence
        // gates) need the precise collision-shape check.
        return !type.isSolid() || block.isPassable();
    }

    private static boolean hazardous(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH,
                 WITHER_ROSE, POINTED_DRIPSTONE, END_PORTAL, NETHER_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }
}
