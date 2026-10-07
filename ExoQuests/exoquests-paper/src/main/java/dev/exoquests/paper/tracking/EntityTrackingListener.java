package dev.exoquests.paper.tracking;

import dev.exoquests.core.config.Settings;
import dev.exoquests.core.quest.QuestType;
import dev.exoquests.paper.ExoQuestsPlugin;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/** Breeding, kills, shearing, fishing and laid-egg collection. */
public final class EntityTrackingListener implements Listener {

    private final TrackingContext ctx;

    public EntityTrackingListener(ExoQuestsPlugin plugin) {
        this.ctx = plugin.tracking();
    }

    /** Fires once per baby; only counted when a player fed the parents. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player && ctx.eligible(player)) {
            String type = event.getEntity().getType().name();
            if (ctx.wants(player, QuestType.ENTITY_BREED, type)) {
                ctx.record(player, QuestType.ENTITY_BREED, type, 1, true, ctx.period());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || !ctx.eligible(killer)) {
            return;
        }
        String type = dead.getType().name();
        if (!ctx.wants(killer, QuestType.ENTITY_KILL, type)) {
            return;
        }
        Settings.KillPolicy policy = ctx.settings().kills();
        if (policy.excludedSpawnReasons().contains(dead.getEntitySpawnReason().name())) {
            return;
        }
        if (policy.requireDirectDamage() && !killedDirectly(dead, killer, policy)) {
            return;
        }
        ctx.record(killer, QuestType.ENTITY_KILL, type, 1, true, ctx.period());
    }

    /** The final damage must come from the player, their projectile, or (if allowed) their pet. */
    private static boolean killedDirectly(LivingEntity dead, Player killer, Settings.KillPolicy policy) {
        EntityDamageEvent last = dead.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        if (damager.getUniqueId().equals(killer.getUniqueId())) {
            return true;
        }
        if (policy.allowProjectiles() && damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter
                && shooter.getUniqueId().equals(killer.getUniqueId())) {
            return true;
        }
        return policy.allowTamedPets() && damager instanceof Tameable pet && pet.isTamed()
                && killer.getUniqueId().equals(pet.getOwnerUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        Player player = event.getPlayer();
        String type = event.getEntity().getType().name();
        if (ctx.eligible(player) && ctx.wants(player, QuestType.SHEAR_ENTITY, type)) {
            ctx.record(player, QuestType.SHEAR_ENTITY, type, 1, true, ctx.period());
        }
    }

    /** One catch = one action, regardless of the caught stack size. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        Player player = event.getPlayer();
        String type = item.getItemStack().getType().name();
        if (ctx.eligible(player) && ctx.wants(player, QuestType.FISH_CATCH, type)) {
            ctx.record(player, QuestType.FISH_CATCH, type, 1, true, ctx.period());
        }
    }

    // ------------------------------------------------------------------ laid eggs

    /** Tags egg item entities laid by chickens. The tag lives on the item entity and survives restarts. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLay(EntityDropItemEvent event) {
        if (event.getEntity() instanceof Chicken && event.getItemDrop().getItemStack().getType().name().endsWith("EGG")) {
            event.getItemDrop().getPersistentDataContainer().set(ctx.laidEggKey(), PersistentDataType.BYTE, (byte) 1);
        }
    }

    private boolean laid(Item item) {
        return item.getPersistentDataContainer().has(ctx.laidEggKey(), PersistentDataType.BYTE);
    }

    /** Prevents player-dropped eggs from merging into a laid-egg stack (or the reverse). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (laid(event.getEntity()) != laid(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !laid(event.getItem())) {
            return;
        }
        String type = event.getItem().getItemStack().getType().name();
        int picked = event.getItem().getItemStack().getAmount() - event.getRemaining();
        if (picked > 0 && ctx.eligible(player) && ctx.wants(player, QuestType.COLLECT_LAID_EGGS, type)) {
            ctx.record(player, QuestType.COLLECT_LAID_EGGS, type, picked, true, ctx.period());
        }
    }
}
