package com.exoblacksmith.effect;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.PotionSpec;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.item.ItemService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps passive equipment effects in sync with what each player is wearing.
 * All attribute changes use ExoBlackSmith-owned keys and transient modifiers, so they never touch other
 * plugins' modifiers, are never saved into player data, and cannot stack by re-equipping.
 */
public final class EquipmentService implements Listener {
    private final Plugin plugin;
    private final ItemService items;
    private final Supplier<Registry> registry;
    private final NamespacedKey healthKey;
    private final NamespacedKey speedKey;
    private final NamespacedKey efficiencyKey;
    private final NamespacedKey knockbackKey;
    private final NamespacedKey pendingEffectsKey;
    private final Map<UUID, PotionOwnership> potions = new HashMap<>();
    private final Map<UUID, Loadout> loadouts = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private BukkitTask scanTask;
    private BukkitTask dirtyTask;

    public EquipmentService(Plugin plugin, ItemService items, Supplier<Registry> registry) {
        this.plugin = plugin;
        this.items = items;
        this.registry = registry;
        this.healthKey = new NamespacedKey(plugin, "mask_health");
        this.speedKey = new NamespacedKey(plugin, "speed");
        this.efficiencyKey = new NamespacedKey(plugin, "void_stride_efficiency");
        this.knockbackKey = new NamespacedKey(plugin, "anchor_guard");
        this.pendingEffectsKey = new NamespacedKey(plugin, "pending_effect_cleanup");
    }

    public void start() {
        stopTasks();
        int interval = registry.get().settings.equipmentScanTicks;
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player);
            }
        }, interval, interval);
        dirtyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 1, 1);
    }

    private void stopTasks() {
        if (scanTask != null) {
            scanTask.cancel();
        }
        if (dirtyTask != null) {
            dirtyTask.cancel();
        }
    }

    private void flushDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        for (UUID id : Set.copyOf(dirty)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                refresh(player);
            }
        }
        dirty.clear();
    }

    public void markDirty(Player player) {
        dirty.add(player.getUniqueId());
    }

    /** Last applied loadout (may be up to one scan interval old). Use {@link #current} for combat checks. */
    public Loadout loadout(Player player) {
        return loadouts.getOrDefault(player.getUniqueId(), Loadout.EMPTY);
    }

    /** Freshly resolved loadout from the player's current equipment. */
    public Loadout current(Player player) {
        return Loadout.resolve(player, items, registry.get());
    }

    public void refresh(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (player.isDead()) {
            return;
        }
        Registry reg = registry.get();
        Loadout loadout = current(player);
        loadouts.put(player.getUniqueId(), loadout);

        double hearts = loadout.maskLevel() == null ? 0 : loadout.maskLevel().bonusHearts();
        setModifier(player, Attribute.MAX_HEALTH, healthKey, hearts * 2.0, AttributeModifier.Operation.ADD_NUMBER);

        double speed = loadout.rune(RuneMechanic.VOID_STRIDE);
        for (ArmorPieceDef piece : loadout.armor().values()) {
            speed += piece.speedBonus();
        }
        if (loadout.activeSet() != null) {
            speed += loadout.activeSet().bonusSpeed();
        }
        boolean airborne = player.isFlying() || player.isGliding();
        if (reg.settings.speedDisabledWhileFlying && airborne) {
            speed = 0;
        }
        setModifier(player, Attribute.MOVEMENT_SPEED, speedKey, speed, AttributeModifier.Operation.ADD_SCALAR);
        setModifier(player, Attribute.MOVEMENT_EFFICIENCY, efficiencyKey,
                loadout.hasRune(RuneMechanic.VOID_STRIDE) ? 1.0 : 0.0, AttributeModifier.Operation.ADD_NUMBER);
        setModifier(player, Attribute.KNOCKBACK_RESISTANCE, knockbackKey,
                loadout.rune(RuneMechanic.ANCHOR_GUARD), AttributeModifier.Operation.ADD_NUMBER);

        List<PotionSpec> effects = loadout.maskLevel() == null ? List.of() : loadout.maskLevel().effects();
        potions.computeIfAbsent(player.getUniqueId(), id -> new PotionOwnership()).sync(player, effects);
    }

    private static void setModifier(Player player, Attribute attribute, NamespacedKey key, double amount,
                                    AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(key);
        if (amount == 0) {
            if (existing != null) {
                instance.removeModifier(key);
            }
        } else if (existing == null || existing.getAmount() != amount || existing.getOperation() != operation) {
            if (existing != null) {
                instance.removeModifier(key);
            }
            instance.addTransientModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        }
        if (attribute == Attribute.MAX_HEALTH && player.getHealth() > instance.getValue()) {
            player.setHealth(Math.max(0.5, instance.getValue()));
        }
    }

    /** Removes every ExoBlackSmith-owned modifier and effect from a player. */
    public void clear(Player player) {
        PotionOwnership owned = potions.remove(player.getUniqueId());
        if (owned != null) {
            owned.clear(player);
            String pending = owned.encodePending();
            if (pending.isEmpty()) {
                player.getPersistentDataContainer().remove(pendingEffectsKey);
            } else {
                player.getPersistentDataContainer().set(pendingEffectsKey, PersistentDataType.STRING, pending);
            }
        }
        setModifier(player, Attribute.MAX_HEALTH, healthKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        setModifier(player, Attribute.MOVEMENT_SPEED, speedKey, 0, AttributeModifier.Operation.ADD_SCALAR);
        setModifier(player, Attribute.MOVEMENT_EFFICIENCY, efficiencyKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        setModifier(player, Attribute.KNOCKBACK_RESISTANCE, knockbackKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        loadouts.remove(player.getUniqueId());
    }

    public void shutdown() {
        stopTasks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
    }

    // ------------------------------------------------------------------ lifecycle events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PotionOwnership owned = new PotionOwnership();
        owned.decodePending(player.getPersistentDataContainer().get(pendingEffectsKey, PersistentDataType.STRING));
        player.getPersistentDataContainer().remove(pendingEffectsKey);
        potions.put(player.getUniqueId(), owned);
        markDirty(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
        dirty.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PotionOwnership owned = potions.get(event.getEntity().getUniqueId());
        if (owned != null) {
            owned.reset(); // vanilla clears all effects on death, even with keepInventory
        }
        loadouts.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PotionOwnership owned = potions.get(event.getPlayer().getUniqueId());
        if (owned != null) {
            owned.reset();
        }
        markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            markDirty(player);
        }
    }
}
