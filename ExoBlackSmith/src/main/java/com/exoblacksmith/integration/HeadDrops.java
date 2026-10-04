package com.exoblacksmith.integration;

import com.exoblacksmith.config.ConfigLoader;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.Settings;
import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.item.ItemService;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Value-head drops.
 *
 * <p>Three entry points, all funnelled through {@link #grant} with a per-entity de-duplication window
 * so overlapping handlers can never pay twice for the same death:
 * <ol>
 *   <li>Vanilla {@link EntityDeathEvent}: one roll per entity that actually died. Entities carrying a
 *       configured stacked-mob marker are skipped here when a custom stack event is configured.</li>
 *   <li>Optional custom stack-death event (e.g. from ExoSpawners), bound by reflection from config:
 *       event class plus accessor methods for killer, entity and the number of mobs that actually died.
 *       ExoSpawners' API could not be inspected while building this plugin, so these names must be
 *       verified against your ExoSpawners version before enabling.</li>
 *   <li>The {@code /exoblacksmith drop} command and {@link #grant} for scripts and other plugins.</li>
 * </ol>
 * The displayed stack size is never used; only the reported number of real deaths.
 */
public final class HeadDrops implements Listener {
    private static final long DEDUPE_MS = 10_000;
    private static final int MAX_DEATHS_PER_EVENT = 10_000;

    private final Plugin plugin;
    private final ItemService items;
    private final Supplier<Registry> registry;
    private final Logger logger;
    private final Map<UUID, Long> handled = new LinkedHashMap<>();
    private Listener customListener;

    public HeadDrops(Plugin plugin, ItemService items, Supplier<Registry> registry) {
        this.plugin = plugin;
        this.items = items;
        this.registry = registry;
        this.logger = plugin.getLogger();
    }

    /** (Re)binds the configured custom stack event. Safe to call on reload. */
    public void bindCustomEvent() {
        if (customListener != null) {
            HandlerList.unregisterAll(customListener);
            customListener = null;
        }
        Settings s = registry.get().settings;
        if (!s.exoEnabled) {
            return;
        }
        try {
            Plugin owner = Bukkit.getPluginManager().getPlugin("ExoSpawners");
            ClassLoader loader = owner != null ? owner.getClass().getClassLoader() : getClass().getClassLoader();
            Class<?> raw = Class.forName(s.exoEventClass, true, loader);
            if (!Event.class.isAssignableFrom(raw)) {
                logger.severe("integrations.exospawners.custom-event.class " + s.exoEventClass + " is not a Bukkit event.");
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            Method killer = raw.getMethod(s.exoKillerMethod);
            Method entity = raw.getMethod(s.exoEntityMethod);
            Method amount = s.exoAmountMethod.isBlank() ? null : raw.getMethod(s.exoAmountMethod);
            customListener = new Listener() {
            };
            Bukkit.getPluginManager().registerEvent(eventClass, customListener, EventPriority.MONITOR, (l, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                if (event instanceof org.bukkit.event.Cancellable c && c.isCancelled()) {
                    return;
                }
                try {
                    Object k = killer.invoke(event);
                    Object e = entity.invoke(event);
                    int deaths = 1;
                    if (amount != null) {
                        Object a = amount.invoke(event);
                        deaths = a instanceof Number n ? n.intValue() : 0;
                    }
                    if (e instanceof Entity dead) {
                        handleDeath(dead, k instanceof Player p ? p : null, deaths);
                    }
                } catch (ReflectiveOperationException ex) {
                    logger.warning("Custom stack event accessor failed: " + ex.getMessage());
                }
            }, plugin, true);
            logger.info("Bound custom stacked-mob death event " + s.exoEventClass + ".");
        } catch (ReflectiveOperationException | LinkageError ex) {
            logger.severe("Could not bind custom stacked-mob event (" + s.exoEventClass + "): " + ex
                    + ". Vanilla death drops remain active.");
        }
    }

    public void unbind() {
        if (customListener != null) {
            HandlerList.unregisterAll(customListener);
            customListener = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            return;
        }
        if (customListener != null && hasStackMarker(dead)) {
            return; // the custom stack event is responsible for this entity
        }
        handleDeath(dead, dead.getKiller(), 1);
    }

    private boolean hasStackMarker(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        for (String marker : registry.get().settings.exoStackedMarkers) {
            if (entity.hasMetadata(marker)) {
                return true;
            }
            NamespacedKey key = NamespacedKey.fromString(marker.toLowerCase(java.util.Locale.ROOT));
            if (key != null && pdc.has(key)) {
                return true;
            }
        }
        return false;
    }

    private void handleDeath(Entity dead, Player killer, int deaths) {
        if (!claim(dead.getUniqueId())) {
            return;
        }
        boolean spawner = dead.fromMobSpawner()
                || dead.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER
                || dead.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER;
        grant(killer, dead.getType(), deaths, spawner, dead.getLocation());
    }

    private synchronized boolean claim(UUID entity) {
        long now = System.currentTimeMillis();
        handled.values().removeIf(t -> now - t > DEDUPE_MS);
        return handled.putIfAbsent(entity, now) == null;
    }

    /**
     * Rolls drops for {@code deaths} real deaths of {@code type}. Returns the number of heads granted.
     *
     * @param killer   credited player killer (may be null)
     * @param spawner  whether the mob came from a spawner
     * @param location where drops land if not given directly (may be null when a killer is present)
     */
    public int grant(Player killer, EntityType type, int deaths, boolean spawner, Location location) {
        Registry reg = registry.get();
        Settings s = reg.settings;
        HeadDef head = ConfigLoader.headsByMob(reg).get(type);
        if (head == null || head.dropChance() <= 0 || deaths <= 0) {
            return 0;
        }
        deaths = Math.min(deaths, MAX_DEATHS_PER_EVENT);
        if (s.dropRequirePlayerKiller && killer == null) {
            return 0;
        }
        if (killer != null && !killer.hasPermission("exoblacksmith.drops")) {
            return 0;
        }
        if (spawner ? !s.dropAllowSpawner : !s.dropAllowNatural) {
            return 0;
        }
        Location where = location != null ? location : killer != null ? killer.getLocation() : null;
        if (where == null || where.getWorld() == null) {
            return 0;
        }
        if (!s.dropWorlds.isEmpty() && !s.dropWorlds.contains(where.getWorld().getName())) {
            return 0;
        }
        double chance = head.dropChance();
        if (killer != null) {
            ItemStack weapon = killer.getInventory().getItemInMainHand();
            int looting = weapon.isEmpty() ? 0 : weapon.getEnchantmentLevel(Enchantment.LOOTING);
            chance += looting * s.dropLootingBonus;
        }
        chance = Math.min(1.0, chance);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int total = 0;
        for (int i = 0; i < deaths; i++) {
            if (random.nextDouble() < chance) {
                total += head.dropMin() + (head.dropMax() > head.dropMin() ? random.nextInt(head.dropMax() - head.dropMin() + 1) : 0);
            }
        }
        if (total <= 0) {
            return 0;
        }
        deliver(killer, where, head, total, s.dropToInventory);
        return total;
    }

    private void deliver(Player killer, Location where, HeadDef head, int total, boolean toInventory) {
        int remaining = total;
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            ItemStack stack = items.create(head, 1, amount);
            if (toInventory && killer != null && killer.isOnline()) {
                for (ItemStack overflow : killer.getInventory().addItem(stack).values()) {
                    killer.getWorld().dropItemNaturally(killer.getLocation(), overflow);
                }
            } else {
                where.getWorld().dropItemNaturally(where, stack);
            }
        }
    }
}
