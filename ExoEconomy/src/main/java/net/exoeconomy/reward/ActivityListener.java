package net.exoeconomy.reward;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.config.RewardSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.*;

/**
 * Detects mining, farming and slaying. Handlers run at MONITOR with ignoreCancelled, so anything a
 * protection plugin (or another plugin's anti-exploit rule) cancels pays nothing, and they do
 * cheap map lookups only. Rewards for one block or entity are paid at most once:
 * a per-tick position guard catches the same block reported by two events, and killed entities
 * carry a persistent "rewarded" tag.
 */
public final class ActivityListener implements Listener {
    /** Plants that grow upward as separate blocks; breaking one breaks those above it. */
    private static final Set<Material> STEMS = Set.of(Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.KELP, Material.KELP_PLANT);

    private final ExoEconomyPlugin plugin;
    private final RewardService rewards;
    private final Provenance provenance;
    private final NamespacedKey spawnReason;
    private final NamespacedKey rewarded;
    private final NamespacedKey exoSpawnersManaged = NamespacedKey.fromString("exospawners:managed_mob");
    private final Map<Long, Integer> seenThisTick = new HashMap<>();
    private int seenTick;
    private boolean fromSpawnerSupported = true;

    public ActivityListener(ExoEconomyPlugin plugin, RewardService rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
        this.provenance = rewards.provenance();
        this.spawnReason = new NamespacedKey(plugin, "spawn_reason");
        this.rewarded = new NamespacedKey(plugin, "rewarded");
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        Block block = e.getBlock();
        Player player = e.getPlayer();
        Material type = block.getType();
        byte kind = provenance.get(block);
        boolean loaded = provenance.loaded();
        if (STEMS.contains(type)) {
            stems(player, block, type, kind, loaded);
            return;
        }
        provenance.set(block, Provenance.NATURAL);
        if (alreadyHandled(block)) return;
        RewardSettings settings = plugin.settings().rewards();
        String key = type.name();
        BigDecimal farm = rewards.configured(RewardSettings.FARMING, key);
        if (farm.signum() > 0 && rewards.eligible(player, RewardSettings.FARMING)) {
            if (block.getBlockData() instanceof Ageable ageable) {
                if (ageable.getAge() < ageable.getMaximumAge()) return;
                if (kind == Provenance.FERTILIZED && !settings.farming().allowFertilized()) return;
            } else if (!loaded || kind == Provenance.PLACED) {
                return; // melons, pumpkins and similar pay only when grown, not when placed
            }
            rewards.award(player, RewardSettings.FARMING, key, farm);
            return;
        }
        BigDecimal wood = rewards.configured(RewardSettings.WOODCUTTING, key);
        if (wood.signum() > 0) {
            // Logs from trees (natural or grown from saplings) pay; placed logs never do.
            if (!loaded || kind == Provenance.PLACED || !rewards.eligible(player, RewardSettings.WOODCUTTING)) return;
            rewards.award(player, RewardSettings.WOODCUTTING, key, wood);
            return;
        }
        BigDecimal mine = rewards.configured(RewardSettings.MINING, key);
        if (mine.signum() <= 0 || !loaded || kind == Provenance.PLACED || kind == Provenance.FERTILIZED) return;
        if (kind == Provenance.GENERATED && !settings.mining().rewardGeneratedBlocks()) return;
        if (!rewards.eligible(player, RewardSettings.MINING)) return;
        rewards.award(player, RewardSettings.MINING, key, mine);
    }

    /** Sugar cane, cactus, bamboo, kelp: pays for every grown (non-placed) segment that breaks. */
    private void stems(Player player, Block base, Material type, byte baseKind, boolean loaded) {
        int limit = plugin.settings().rewards().farming().stemScanLimit();
        int segments = 0;
        List<Block> column = new ArrayList<>();
        column.add(base);
        Block up = base.getRelative(BlockFace.UP);
        for (int i = 0; i < limit && sameStem(up.getType(), type) && up.getY() < base.getWorld().getMaxHeight(); i++) {
            column.add(up);
            up = up.getRelative(BlockFace.UP);
        }
        for (int i = 0; i < column.size(); i++) {
            Block b = column.get(i);
            byte kind = i == 0 ? baseKind : provenance.get(b);
            provenance.set(b, Provenance.NATURAL);
            if (loaded && kind != Provenance.PLACED && !alreadyHandled(b)) segments++;
        }
        if (segments == 0 || !rewards.eligible(player, RewardSettings.FARMING)) return;
        String key = type == Material.KELP_PLANT ? Material.KELP.name() : type.name();
        BigDecimal each = rewards.configured(RewardSettings.FARMING, key);
        if (each.signum() > 0) rewards.award(player, RewardSettings.FARMING, key, each.multiply(BigDecimal.valueOf(segments)));
    }

    private static boolean sameStem(Material a, Material b) {
        if (a == b) return true;
        return (a == Material.KELP || a == Material.KELP_PLANT) && (b == Material.KELP || b == Material.KELP_PLANT);
    }

    /** Right-click harvesting (sweet berries): pays when the bush was ripe. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(PlayerHarvestBlockEvent e) {
        Block block = e.getHarvestedBlock();
        if (e.getItemsHarvested().isEmpty() || !(block.getBlockData() instanceof Ageable ageable)) return;
        if (ageable.getAge() < ageable.getMaximumAge() - 1) return;
        String key = block.getType().name();
        BigDecimal amount = rewards.configured(RewardSettings.FARMING, key);
        if (amount.signum() <= 0 || alreadyHandled(block) || !rewards.eligible(e.getPlayer(), RewardSettings.FARMING)) return;
        if (provenance.get(block) == Provenance.FERTILIZED && !plugin.settings().rewards().farming().allowFertilized()) return;
        rewards.award(e.getPlayer(), RewardSettings.FARMING, key, amount);
    }

    /** Returns true if this exact block was already handled during the current tick. */
    private boolean alreadyHandled(Block b) {
        int tick = Bukkit.getCurrentTick();
        if (tick != seenTick) {
            seenThisTick.clear();
            seenTick = tick;
        }
        long key = ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF);
        key ^= (long) b.getWorld().getUID().hashCode() << 20;
        return seenThisTick.put(key, tick) != null;
    }

    // ------------------------------------------------------------------ mobs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        e.getEntity().getPersistentDataContainer().set(spawnReason, PersistentDataType.STRING, e.getSpawnReason().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;
        RewardSettings.Slaying slaying = plugin.settings().rewards().slaying();
        if (dead instanceof Player && !slaying.rewardPlayers()) return;
        PersistentDataContainer pdc = dead.getPersistentDataContainer();
        if (pdc.has(rewarded, PersistentDataType.BYTE)) return;
        // ExoSpawners stacked mobs are paid through its StackMobKilledEvent (see ExoSpawnersBridge), like ExoSkills does.
        if (exoSpawnersManaged != null && pdc.has(exoSpawnersManaged, PersistentDataType.BYTE)) return;
        String reason = pdc.get(spawnReason, PersistentDataType.STRING);
        if (!slaying.allows(reason, fromSpawner(dead))) return;
        String key = dead.getType().name();
        BigDecimal amount = rewards.configured(RewardSettings.SLAYING, key);
        if (amount.signum() <= 0 || !rewards.eligible(killer, RewardSettings.SLAYING)) return;
        pdc.set(rewarded, PersistentDataType.BYTE, (byte) 1);
        rewards.award(killer, RewardSettings.SLAYING, key, amount);
    }

    private boolean fromSpawner(LivingEntity entity) {
        if (!fromSpawnerSupported) return false;
        try {
            return entity.fromMobSpawner();
        } catch (LinkageError e) {
            fromSpawnerSupported = false;
            plugin.getLogger().warning("Entity#fromMobSpawner is unavailable on this server; spawner detection relies on spawn reasons only");
            return false;
        }
    }
}
