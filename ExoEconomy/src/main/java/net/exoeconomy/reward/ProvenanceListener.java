package net.exoeconomy.reward;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps {@link Provenance} up to date. All handlers run at MONITOR and ignore cancelled events, so
 * protection plugins that cancel an action also cancel the provenance change.
 *
 * <p>Events fired before the world changes record the type the block is about to have.
 *
 * <p>Limits (documented): blocks changed by plugins without firing Bukkit events, WorldEdit edits,
 * the second half of doors/beds/tall plants when broken indirectly, and blocks moved by
 * mechanisms other than pistons and gravity are not tracked.
 */
public final class ProvenanceListener implements Listener {
    private final Provenance provenance;
    private final NamespacedKey fallingKey;

    public ProvenanceListener(Provenance provenance, NamespacedKey fallingKey) {
        this.provenance = provenance;
        this.fallingKey = fallingKey;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState s : multi.getReplacedBlockStates()) provenance.set(s.getBlock(), Provenance.PLACED);
        } else {
            provenance.set(e.getBlockPlaced(), Provenance.PLACED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent e) {
        provenance.set(e.getBlock(), e instanceof EntityBlockFormEvent ? Provenance.PLACED : Provenance.GENERATED, e.getNewState().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(BlockGrowEvent e) {
        // A new block (sugar cane segment, melon, pumpkin) appearing by growth is natural.
        if (e.getBlock().getType() != e.getNewState().getType()) provenance.set(e.getBlock(), Provenance.NATURAL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void structure(StructureGrowEvent e) {
        for (BlockState s : e.getBlocks()) provenance.set(s.getBlock(), Provenance.NATURAL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) {
        provenance.set(e.getBlock(), Provenance.NATURAL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent e) {
        for (BlockState s : e.getBlocks()) if (s.getBlockData() instanceof Ageable) provenance.set(s.getBlock(), Provenance.FERTILIZED, s.getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) { move(e.getBlocks(), e.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) { move(e.getBlocks(), e.getDirection()); }

    private void move(List<Block> blocks, BlockFace direction) {
        Map<Block, Byte> kinds = new HashMap<>();
        Map<Block, String> types = new HashMap<>();
        for (Block b : blocks) { kinds.put(b, provenance.get(b)); types.put(b, b.getType().name()); }
        for (Block b : blocks) provenance.set(b, Provenance.NATURAL);
        // Moved blocks keep their origin; generator blocks pushed out of a generator stay GENERATED.
        for (Block b : blocks) {
            byte kind = kinds.get(b);
            if (kind != Provenance.NATURAL) provenance.set(b.getRelative(direction), kind, types.get(b));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changeBlock(EntityChangeBlockEvent e) {
        Block block = e.getBlock();
        if (e.getEntity() instanceof FallingBlock falling) {
            if (e.getTo() == Material.AIR) {
                byte kind = provenance.get(block);
                provenance.set(block, Provenance.NATURAL);
                if (kind != Provenance.NATURAL) falling.getPersistentDataContainer().set(fallingKey, PersistentDataType.BYTE, kind);
            } else {
                Byte kind = falling.getPersistentDataContainer().get(fallingKey, PersistentDataType.BYTE);
                provenance.set(block, kind == null ? Provenance.NATURAL : kind, e.getTo().name());
            }
        } else {
            // Endermen, ravagers, silverfish, sheep eating grass and similar: not player-made.
            provenance.set(block, Provenance.NATURAL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) { for (Block b : e.blockList()) provenance.set(b, Provenance.NATURAL); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(BlockExplodeEvent e) { for (Block b : e.blockList()) provenance.set(b, Provenance.NATURAL); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) { provenance.set(e.getBlock(), Provenance.NATURAL); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fade(BlockFadeEvent e) { provenance.set(e.getBlock(), Provenance.NATURAL); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void decay(LeavesDecayEvent e) { provenance.set(e.getBlock(), Provenance.NATURAL); }
}
