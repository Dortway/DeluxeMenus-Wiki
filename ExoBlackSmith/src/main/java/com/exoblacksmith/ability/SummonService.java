package com.exoblacksmith.ability;

import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.effect.DamageGuard;
import com.exoblacksmith.item.ItemKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Temporary wolves and iron golems that attack exactly one designated enemy player.
 * <ul>
 *   <li>Summons are non-persistent (never saved to disk) and tagged with owner and target.</li>
 *   <li>They can only target the designated player; any other retarget is cancelled.</li>
 *   <li>Their own hits are cancelled and re-dealt as damage from the owner, so PvP flags, region
 *       protection and combat plugins see an ordinary owner-vs-target attack. Damage to anyone else is
 *       cancelled outright.</li>
 *   <li>They drop nothing, cannot be tamed, leashed, interacted with or sent through portals.</li>
 *   <li>Removed on expiry, owner quit/death/world change, target loss, chunk unload and plugin shutdown;
 *       stray tagged entities are removed when chunks load.</li>
 * </ul>
 */
public final class SummonService implements Listener {
    private record Summon(UUID entity, UUID owner, UUID target, long expiresAt, double damage) {
    }

    private final Plugin plugin;
    private final ItemKeys keys;
    private final Map<UUID, Summon> summons = new HashMap<>();
    private BukkitTask task;

    public SummonService(Plugin plugin, ItemKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    public void start() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                removeIfStray(entity);
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    }

    public int active(UUID owner) {
        int count = 0;
        for (Summon s : summons.values()) {
            if (s.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /** Spawns the configured summons; returns how many were spawned. */
    public int summon(Player owner, Player target, MaskLevel.SummonAbility ability, int maxActive) {
        int allowed = Math.min(ability.count(), maxActive - active(owner.getUniqueId()));
        int spawned = 0;
        for (int i = 0; i < allowed; i++) {
            double angle = (Math.PI * 2 / Math.max(1, ability.count())) * i;
            Location base = owner.getLocation().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            Location spot = SafeLocations.find(base, owner.getLocation().getYaw(), 0);
            if (spot == null) {
                spot = SafeLocations.find(owner.getLocation(), owner.getLocation().getYaw(), 0);
            }
            if (spot == null) {
                continue;
            }
            Class<? extends Mob> type = ability.entity() == org.bukkit.entity.EntityType.IRON_GOLEM ? IronGolem.class : Wolf.class;
            Mob mob = owner.getWorld().spawn(spot, type, m -> {
                m.setPersistent(false);
                m.getPersistentDataContainer().set(keys.summonOwner, PersistentDataType.STRING, owner.getUniqueId().toString());
                m.getPersistentDataContainer().set(keys.summonTarget, PersistentDataType.STRING, target.getUniqueId().toString());
                if (m instanceof Wolf wolf) {
                    wolf.setAngry(true);
                    wolf.setCollarColor(DyeColor.RED);
                }
                if (m instanceof IronGolem golem) {
                    golem.setPlayerCreated(true);
                }
                m.setTarget(target);
            });
            summons.put(mob.getUniqueId(), new Summon(mob.getUniqueId(), owner.getUniqueId(), target.getUniqueId(),
                    System.currentTimeMillis() + ability.lifetimeSeconds() * 1000L, ability.damage()));
            spawned++;
        }
        return spawned;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Summon> it = summons.values().iterator(); it.hasNext(); ) {
            Summon s = it.next();
            Entity entity = Bukkit.getEntity(s.entity());
            Player owner = Bukkit.getPlayer(s.owner());
            Player target = Bukkit.getPlayer(s.target());
            boolean keep = entity != null && entity.isValid() && now < s.expiresAt()
                    && owner != null && owner.isOnline() && !owner.isDead()
                    && target != null && target.isOnline() && !target.isDead()
                    && entity.getWorld().equals(target.getWorld()) && owner.getWorld().equals(target.getWorld());
            if (!keep) {
                despawn(entity);
                it.remove();
            } else if (entity instanceof Mob mob && !target.equals(mob.getTarget())) {
                mob.setTarget(target);
            }
        }
    }

    private static void despawn(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.getWorld().spawnParticle(Particle.POOF, entity.getLocation().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
            entity.remove();
        }
    }

    public void removeOwnedBy(UUID owner) {
        for (Iterator<Summon> it = summons.values().iterator(); it.hasNext(); ) {
            Summon s = it.next();
            if (s.owner().equals(owner)) {
                despawn(Bukkit.getEntity(s.entity()));
                it.remove();
            }
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Summon s : summons.values()) {
            Entity e = Bukkit.getEntity(s.entity());
            if (e != null) {
                e.remove();
            }
        }
        summons.clear();
    }

    private boolean isSummon(Entity entity) {
        return entity.getPersistentDataContainer().has(keys.summonOwner);
    }

    private void removeIfStray(Entity entity) {
        if (isSummon(entity) && !summons.containsKey(entity.getUniqueId())) {
            entity.remove();
        }
    }

    // ------------------------------------------------------------------ behaviour guards

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent event) {
        Summon s = summons.get(event.getEntity().getUniqueId());
        if (s == null) {
            if (isSummon(event.getEntity())) {
                event.setCancelled(true);
            }
            return;
        }
        Entity newTarget = event.getTarget();
        if (newTarget == null || !newTarget.getUniqueId().equals(s.target())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSummonHit(EntityDamageByEntityEvent event) {
        Summon s = summons.get(event.getDamager().getUniqueId());
        if (s == null) {
            if (isSummon(event.getDamager())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!event.getEntity().getUniqueId().equals(s.target()) || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player owner = Bukkit.getPlayer(s.owner());
        if (owner == null || !owner.isOnline() || !owner.getWorld().equals(victim.getWorld())) {
            return;
        }
        double damage = s.damage();
        DamageGuard.run(() -> victim.damage(damage, owner));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (isSummon(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            summons.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTame(EntityTameEvent event) {
        if (isSummon(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isSummon(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (isSummon(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(EntityPortalEvent event) {
        if (isSummon(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removeOwnedBy(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOwnerDeath(PlayerDeathEvent event) {
        removeOwnedBy(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeOwnedBy(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        List<Entity> toRemove = new ArrayList<>();
        for (Entity entity : event.getChunk().getEntities()) {
            if (isSummon(entity)) {
                toRemove.add(entity);
            }
        }
        for (Entity entity : toRemove) {
            summons.remove(entity.getUniqueId());
            entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            removeIfStray(entity);
        }
    }
}
