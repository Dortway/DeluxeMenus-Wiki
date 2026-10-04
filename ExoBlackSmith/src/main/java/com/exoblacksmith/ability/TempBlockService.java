package com.exoblacksmith.ability;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Temporary ability blocks (cobwebs).
 * <ul>
 *   <li>Only air is ever replaced, and only after protection checks pass.</li>
 *   <li>On expiry a block is removed only if it is still the exact type we placed and still tracked;
 *       breaking it, or anything else changing it through a tracked event, untracks it, so later
 *       unrelated changes are never overwritten.</li>
 *   <li>Breaking a temporary block drops nothing; pistons and explosions cannot move or break them.</li>
 *   <li>Entries are saved to {@code data/temp-blocks.yml} on every change and cleaned on startup, so a
 *       crash cannot leave permanent cobwebs (chunks that are not loaded are cleaned when they load).</li>
 * </ul>
 */
public final class TempBlockService implements Listener {
    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private record Entry(Material type, long expiresAt) {
    }

    private final Plugin plugin;
    private final File file;
    private final Logger logger;
    private final Map<Key, Entry> entries = new HashMap<>();
    private BukkitTask task;

    public TempBlockService(Plugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
        this.logger = plugin.getLogger();
    }

    public void start() {
        load();
        sweep(true);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> sweep(false), 10, 10);
    }

    public boolean isTracked(Block block) {
        return entries.containsKey(Key.of(block));
    }

    /** Places {@code type} at {@code block} if it is air; caller has already run protection checks. */
    public boolean place(Block block, Material type, int seconds) {
        if (!block.getType().isAir() || isTracked(block)) {
            return false;
        }
        block.setType(type, true);
        entries.put(Key.of(block), new Entry(type, System.currentTimeMillis() + seconds * 1000L));
        save();
        return true;
    }

    private void sweep(boolean all) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Entry> e = it.next();
            if (!all && e.getValue().expiresAt() > now) {
                continue;
            }
            World world = Bukkit.getWorld(e.getKey().world());
            if (world == null) {
                continue; // world not loaded yet; keep for later
            }
            if (!world.isChunkLoaded(e.getKey().x() >> 4, e.getKey().z() >> 4)) {
                continue; // cleaned by ChunkLoadEvent
            }
            revert(world, e.getKey(), e.getValue());
            it.remove();
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private static void revert(World world, Key key, Entry entry) {
        Block block = world.getBlockAt(key.x(), key.y(), key.z());
        if (block.getType() == entry.type()) {
            block.setType(Material.AIR, true);
        }
    }

    /** Reverts everything currently loaded (plugin disable). */
    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        sweep(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Entry> e = it.next();
            Key k = e.getKey();
            if (k.world().equals(event.getWorld().getUID()) && k.x() >> 4 == event.getChunk().getX()
                    && k.z() >> 4 == event.getChunk().getZ() && e.getValue().expiresAt() <= now) {
                revert(event.getWorld(), k, e.getValue());
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (entries.remove(Key.of(event.getBlock())) != null) {
            event.setDropItems(false);
            event.setExpToDrop(0);
            save();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // Something real replaced a tracked position (e.g. a later placement): stop tracking it.
        if (entries.remove(Key.of(event.getBlockPlaced())) != null) {
            save();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (entries.remove(Key.of(event.getBlock())) != null) {
            save();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isTracked(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (isTracked(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isTracked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isTracked);
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        entries.forEach((k, e) -> lines.add(k.world() + "," + k.x() + "," + k.y() + "," + k.z() + "," + e.type().name()
                + "," + e.expiresAt()));
        yaml.set("blocks", lines);
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            logger.warning("Could not save temporary blocks: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("blocks")) {
            String[] p = line.split(",");
            if (p.length != 6) {
                continue;
            }
            try {
                Material type = Material.valueOf(p[4]);
                entries.put(new Key(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3])), new Entry(type, Long.parseLong(p[5])));
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring corrupt temp-block entry: " + line);
            }
        }
    }
}
