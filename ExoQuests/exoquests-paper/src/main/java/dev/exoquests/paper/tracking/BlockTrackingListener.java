package dev.exoquests.paper.tracking;

import dev.exoquests.core.config.Settings;
import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestType;
import dev.exoquests.core.quest.WoodType;
import dev.exoquests.core.storage.PlacementMark;
import dev.exoquests.core.storage.PlacementStore;
import dev.exoquests.core.util.BlockPos;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Block-based tracking: mining, logging, crops, stacked plants and tree growth, plus the placement
 * records that stop place-and-break farming.
 *
 * <p>Every handler runs at MONITOR with {@code ignoreCancelled = true}, so actions blocked by protection
 * plugins (WorldGuard, island plugins, ...) never count and never change placement records.</p>
 */
public final class BlockTrackingListener implements Listener {

    private final ExoQuestsPlugin plugin;
    private final TrackingContext ctx;
    private final PlacementStore placements;

    public BlockTrackingListener(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
        this.ctx = plugin.tracking();
        this.placements = plugin.placements();
    }

    // ------------------------------------------------------------------ placement records

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (!ctx.tracksPlacement(type)) {
            return;
        }
        Player player = event.getPlayer();
        if (WoodType.isSapling(type.name())) {
            // Only saplings planted by an eligible player can earn tree credit later.
            UUID owner = ctx.eligible(player) ? player.getUniqueId() : null;
            placements.place(TrackingContext.pos(block), type.name(), PlacementMark.Kind.SAPLING, owner);
        } else {
            placements.place(TrackingContext.pos(block), type.name(), PlacementMark.Kind.PLACED, null);
        }
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        boolean eligible = ctx.eligible(player);
        String period = ctx.period();

        if (handleStackedPlants(player, block, type, eligible, period)) {
            return;
        }

        if (eligible && block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge()
                && ctx.wants(player, QuestType.CROP_HARVEST, type.name())) {
            ctx.record(player, QuestType.CROP_HARVEST, type.name(), 1, true, period);
        }

        boolean wantsBreak = eligible && ctx.wants(player, QuestType.BLOCK_BREAK, type.name());
        if (ctx.tracksPlacement(type)) {
            // Always consume the record so the position is clean, even if nobody needs the progress.
            placements.consumeNatural(List.of(new PlacementStore.Target(TrackingContext.pos(block), type.name())))
                    .thenAcceptAsync(natural -> {
                        if (wantsBreak && player.isOnline()) {
                            ctx.record(player, QuestType.BLOCK_BREAK, type.name(), 1, natural == 1, period);
                        }
                    }, plugin.mainExecutor()).exceptionally(ctx::logFailure);
        } else if (wantsBreak) {
            ctx.record(player, QuestType.BLOCK_BREAK, type.name(), 1, true, period);
        }
    }

    /**
     * Sugar cane, cactus and bamboo: breaking a segment also breaks everything above it. Each segment of
     * the affected column counts once; segments recorded as player-placed are reported as non-natural.
     *
     * @return true if the broken block itself was a stacked plant (fully handled)
     */
    private boolean handleStackedPlants(Player player, Block block, Material type, boolean eligible, String period) {
        Settings.StackedPlantPolicy policy = ctx.settings().stackedPlants();
        Block start;
        Material plant;
        if (TrackingContext.STACKED_PLANTS.contains(type)) {
            start = block;
            plant = type;
        } else if (policy.countSupportBreaks()
                && TrackingContext.STACKED_PLANTS.contains(block.getRelative(BlockFace.UP).getType())) {
            start = block.getRelative(BlockFace.UP);
            plant = start.getType();
        } else {
            return false;
        }
        List<PlacementStore.Target> column = new ArrayList<>();
        Block current = start;
        World world = block.getWorld();
        while (current.getType() == plant && column.size() < policy.maxScanHeight()
                && current.getY() < world.getMaxHeight()) {
            column.add(new PlacementStore.Target(TrackingContext.pos(current), plant.name()));
            current = current.getRelative(BlockFace.UP);
        }
        boolean wants = eligible && ctx.wants(player, QuestType.STACKED_PLANT_HARVEST, plant.name());
        int total = column.size();
        if (ctx.tracksPlacement(plant)) {
            placements.consumeNatural(column).thenAcceptAsync(natural -> {
                if (wants && player.isOnline()) {
                    ctx.record(player, QuestType.STACKED_PLANT_HARVEST, plant.name(), natural, true, period);
                    ctx.record(player, QuestType.STACKED_PLANT_HARVEST, plant.name(), total - natural, false, period);
                }
            }, plugin.mainExecutor()).exceptionally(ctx::logFailure);
        } else if (wants) {
            ctx.record(player, QuestType.STACKED_PLANT_HARVEST, plant.name(), total, true, period);
        }
        return TrackingContext.STACKED_PLANTS.contains(type);
    }

    // ------------------------------------------------------------------ tree growth

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Location location = event.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        BlockPos origin = TrackingContext.pos(location.getBlock());
        List<BlockPos> structure = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            structure.add(new BlockPos(world.getUID().toString(), state.getX(), state.getY(), state.getZ()));
        }
        List<BlockPos> candidates = new ArrayList<>(structure.size() + 1);
        candidates.add(origin);
        candidates.addAll(structure);
        Player bonemealer = event.isFromBonemeal() ? event.getPlayer() : null;
        UUID bonemealerId = bonemealer == null ? null : bonemealer.getUniqueId();
        boolean bonemealerEligible = bonemealer != null && ctx.eligible(bonemealer);
        String period = ctx.period();
        // Sapling records of this tree are consumed (one tree per growth event, including 2x2 trees).
        placements.consumeSaplings(candidates).thenAcceptAsync(
                marks -> creditTree(marks, origin, world, bonemealerId, bonemealerEligible, period),
                plugin.mainExecutor()).exceptionally(ctx::logFailure);
        // Blocks generated by the tree are natural: logs from player-planted trees count for logging quests.
        placements.clear(structure);
    }

    private void creditTree(List<PlacementMark> marks, BlockPos origin, World world, UUID bonemealer,
                            boolean bonemealerEligible, String period) {
        if (marks.isEmpty() || !ctx.worldAllowed(world)) {
            return;
        }
        PlacementMark mark = marks.stream().filter(m -> m.pos().equals(origin)).findFirst().orElse(marks.get(0));
        Optional<WoodType> wood = WoodType.fromSapling(mark.material());
        if (wood.isEmpty()) {
            return;
        }
        Settings.TreePolicy policy = ctx.settings().trees();
        UUID credited = mark.owner();
        if (policy.credit() == Settings.TreeCredit.BONEMEALER_IF_PRESENT && bonemealer != null) {
            credited = bonemealerEligible ? bonemealer : null;
        }
        if (credited == null) {
            return;
        }
        Player online = Bukkit.getPlayer(credited);
        if (online != null && !online.hasPermission("exoquests.progress")) {
            return;
        }
        plugin.progress().record(credited, new QuestAction(QuestType.TREE_GROW, wood.get().name(), 1), period,
                policy.creditOfflinePlanter());
    }

    // ------------------------------------------------------------------ keeping records accurate

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        movePistonBlocks(event.getBlock(), event.getBlocks(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        movePistonBlocks(event.getBlock(), event.getBlocks(), false);
    }

    private void movePistonBlocks(Block piston, List<Block> blocks, boolean extending) {
        if (blocks.isEmpty() || !(piston.getBlockData() instanceof Directional directional)) {
            return;
        }
        BlockFace facing = directional.getFacing();
        BlockFace movement = extending ? facing : facing.getOppositeFace();
        List<BlockPos> destroyed = new ArrayList<>();
        List<PlacementStore.Move> moves = new ArrayList<>();
        for (Block b : blocks) {
            if (b.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                destroyed.add(TrackingContext.pos(b));
            } else {
                moves.add(new PlacementStore.Move(TrackingContext.pos(b), TrackingContext.pos(b.getRelative(movement))));
            }
        }
        if (!destroyed.isEmpty()) {
            placements.clear(destroyed);
        }
        if (!moves.isEmpty()) {
            placements.move(moves);
        }
    }

    private void clearTracked(List<Block> blocks) {
        List<BlockPos> positions = new ArrayList<>();
        for (Block b : blocks) {
            if (ctx.tracksPlacement(b.getType())) {
                positions.add(TrackingContext.pos(b));
            }
        }
        if (!positions.isEmpty()) {
            placements.clear(positions);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        clearTracked(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        clearTracked(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        clearTracked(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        clearTracked(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        clearTracked(List.of(event.getToBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (ctx.tracksPlacement(block.getType()) || ctx.tracksPlacement(event.getTo())) {
            placements.clear(List.of(TrackingContext.pos(block)));
        }
    }

    /** A plant grew into this position (new cane/cactus segment, melon or pumpkin from a stem). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (ctx.tracksPlacement(event.getNewState().getType())) {
            placements.clear(List.of(TrackingContext.pos(event.getBlock())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (ctx.tracksPlacement(event.getNewState().getType())) {
            placements.clear(List.of(TrackingContext.pos(event.getBlock())));
        }
    }
}
