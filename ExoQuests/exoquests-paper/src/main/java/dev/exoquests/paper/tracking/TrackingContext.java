package dev.exoquests.paper.tracking;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.Settings;
import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.quest.QuestType;
import dev.exoquests.core.quest.WoodType;
import dev.exoquests.core.util.BlockPos;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Shared tracking rules: who is eligible, which placements are recorded, and how actions are recorded. */
public final class TrackingContext {

    static final Set<Material> STACKED_PLANTS = EnumSet.of(Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO);

    private final ExoQuestsPlugin plugin;
    private final NamespacedKey laidEggKey;
    private volatile Set<Material> trackedPlacements = EnumSet.noneOf(Material.class);

    public TrackingContext(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
        this.laidEggKey = new NamespacedKey(plugin, "laid_egg");
        refresh(plugin.configs().current());
    }

    /** Recomputes which block placements must be recorded for the current quest pool. */
    public void refresh(ConfigBundle bundle) {
        Set<Material> tracked = EnumSet.noneOf(Material.class);
        QuestPool pool = bundle.quests();
        for (String name : pool.naturalOnlyMaterials()) {
            Material m = Material.getMaterial(name);
            if (m != null) {
                tracked.add(m);
            }
        }
        if (tracked.contains(Material.BAMBOO)) {
            tracked.add(Material.BAMBOO_SAPLING);
        }
        if (pool.usesType(QuestType.TREE_GROW)) {
            for (WoodType wood : WoodType.values()) {
                Material sapling = Material.getMaterial(wood.saplingMaterial());
                if (sapling != null) {
                    tracked.add(sapling);
                }
            }
        }
        trackedPlacements = tracked;
    }

    public boolean tracksPlacement(Material material) {
        return trackedPlacements.contains(material);
    }

    NamespacedKey laidEggKey() {
        return laidEggKey;
    }

    Settings settings() {
        return plugin.configs().current().settings();
    }

    public boolean worldAllowed(World world) {
        return settings().worlds().allows(world.getName());
    }

    public boolean gameModeAllowed(GameMode mode) {
        return settings().allowedGameModes().contains(mode.name());
    }

    /** True if this player's actions may earn progress right now. */
    public boolean eligible(Player player) {
        return gameModeAllowed(player.getGameMode()) && worldAllowed(player.getWorld())
                && player.hasPermission("exoquests.progress");
    }

    /** Period key at the moment of the event; recorded actions from an older period are discarded. */
    public String period() {
        return plugin.progress().currentPeriod();
    }

    public boolean wants(Player player, QuestType type, String key) {
        return plugin.progress().wants(player.getUniqueId(), type, key);
    }

    public void record(Player player, QuestType type, String key, int amount, boolean natural, String period) {
        if (amount > 0) {
            plugin.progress().record(player.getUniqueId(), new QuestAction(type, key, amount, natural), period, false);
        }
    }

    /** Logs a failed asynchronous tracking step; used with {@code CompletableFuture#exceptionally}. */
    <T> T logFailure(Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "placement tracking failed", error);
        return null;
    }

    public static BlockPos pos(Block block) {
        return new BlockPos(block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ());
    }
}
