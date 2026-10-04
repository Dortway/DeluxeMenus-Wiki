package com.exoblacksmith.ability;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.effect.CooldownService;
import com.exoblacksmith.effect.EquipmentService;
import com.exoblacksmith.item.ItemKeys;
import com.exoblacksmith.util.Durations;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

/**
 * Activated mask abilities.
 * <ul>
 *   <li>Sneak + right-click (main hand only, empty hand or a melee weapon/trident) triggers the worn
 *       mask's heal, cobweb or summon ability. Offhand interaction events are ignored and a short
 *       per-player debounce absorbs the duplicate air/block events the client can send.</li>
 *   <li>Skelly: shooting a bow while sneaking marks that one arrow. The full cooldown is reserved at
 *       the shot, so only one teleport can be pending. A valid impact teleports the wearer to the nearest
 *       safe spot; a miss, unsafe spot or a cancelled teleport shortens the cooldown to the configured
 *       miss cooldown.</li>
 * </ul>
 * Inventory clicks never trigger abilities (they are not interact events).
 */
public final class MaskAbilities implements Listener {
    private static final String HEAL = "mask.heal";
    private static final String COBWEB = "mask.cobweb";
    private static final String SUMMON = "mask.summon";
    private static final String TELEPORT = "mask.arrow_teleport";

    private record PendingArrow(UUID arrow, long deadline, long missCooldownMs) {
    }

    private final Plugin plugin;
    private final Supplier<Registry> registry;
    private final EquipmentService equipment;
    private final CooldownService cooldowns;
    private final TempBlockService tempBlocks;
    private final SummonService summons;
    private final ItemKeys keys;
    private final Map<UUID, Long> lastActivation = new HashMap<>();
    private final Map<UUID, PendingArrow> pendingArrows = new HashMap<>();
    private BukkitTask task;

    public MaskAbilities(Plugin plugin, Supplier<Registry> registry, EquipmentService equipment, CooldownService cooldowns,
                         TempBlockService tempBlocks, SummonService summons, ItemKeys keys) {
        this.plugin = plugin;
        this.registry = registry;
        this.equipment = equipment;
        this.cooldowns = cooldowns;
        this.tempBlocks = tempBlocks;
        this.summons = summons;
        this.keys = keys;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::expireArrows, 20, 20);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        pendingArrows.clear();
    }

    static boolean activationHand(ItemStack item, boolean requireEmpty) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        if (requireEmpty) {
            return false;
        }
        Material type = item.getType();
        return Tag.ITEMS_SWORDS.isTagged(type) || Tag.ITEMS_AXES.isTagged(type) || type == Material.MACE
                || type == Material.TRIDENT;
    }

    private boolean onCooldown(Player player, String key) {
        long left = cooldowns.remaining(player, key);
        if (left > 0) {
            Text.actionBar(player, registry.get(), "on-cooldown", Map.of("time", Durations.format(left)));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Registry reg = registry.get();
        if (reg.settings.abilityRequireSneak && !player.isSneaking()) {
            return;
        }
        if (!activationHand(event.getItem(), reg.settings.abilityRequireEmptyHand)) {
            return;
        }
        MaskLevel mask = equipment.current(player).maskLevel();
        if (mask == null || !mask.hasActivatedAbility()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastActivation.get(player.getUniqueId());
        if (last != null && now - last < reg.settings.abilityDebounceMs) {
            event.setCancelled(true);
            return;
        }
        lastActivation.put(player.getUniqueId(), now);
        event.setCancelled(true);
        if (!player.hasPermission("exoblacksmith.ability.mask")) {
            Text.send(player, reg, "no-permission");
            return;
        }
        if (mask.heal() != null) {
            heal(player, mask.heal());
        } else if (mask.cobweb() != null) {
            cobweb(player, mask.cobweb());
        } else if (mask.summon() != null) {
            summon(player, mask.summon());
        }
    }

    private void heal(Player player, MaskLevel.HealAbility heal) {
        if (onCooldown(player, HEAL)) {
            return;
        }
        double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (player.getHealth() >= max) {
            Text.actionBar(player, registry.get(), "heal-full-health", Map.of());
            return;
        }
        double amount = heal.full() ? max : heal.hearts() * 2.0;
        double before = player.getHealth();
        EntityRegainHealthEvent regain = new EntityRegainHealthEvent(player, amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
        Bukkit.getPluginManager().callEvent(regain);
        if (regain.isCancelled() || regain.getAmount() <= 0) {
            return; // another plugin blocked the heal: do not consume the cooldown
        }
        player.setHealth(Math.min(max, before + regain.getAmount()));
        cooldowns.start(player, HEAL, heal.cooldownSeconds() * 1000);
        player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 2, 0), 6, 0.4, 0.3, 0.4);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        Text.actionBar(player, registry.get(), "heal-used", Map.of("hearts",
                Durations.number((player.getHealth() - before) / 2.0)));
    }

    private void cobweb(Player player, MaskLevel.CobwebAbility cobweb) {
        if (onCooldown(player, COBWEB)) {
            return;
        }
        RayTraceResult hit = player.rayTraceBlocks(cobweb.range());
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() != BlockFace.UP
                || !hit.getHitBlock().getType().isSolid()) {
            Text.actionBar(player, registry.get(), "cobweb-no-target", Map.of());
            return;
        }
        Block center = hit.getHitBlock().getRelative(BlockFace.UP);
        List<Block> targets = new ArrayList<>();
        targets.add(center);
        if (cobweb.shape() != MaskLevel.CobwebShape.SINGLE) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                targets.add(center.getRelative(face));
            }
        }
        if (cobweb.shape() == MaskLevel.CobwebShape.SQUARE) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH_EAST, BlockFace.NORTH_WEST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST}) {
                targets.add(center.getRelative(face));
            }
        }
        boolean spawnProtection = registry.get().settings.respectSpawnProtection;
        int placed = 0;
        for (Block block : targets) {
            if (!block.getType().isAir() || tempBlocks.isTracked(block) || !block.getRelative(BlockFace.DOWN).getType().isSolid()) {
                continue;
            }
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }
            if (Protection.canPlace(player, block, Material.COBWEB, spawnProtection)
                    && tempBlocks.place(block, Material.COBWEB, cobweb.durationSeconds())) {
                placed++;
            }
        }
        if (placed == 0) {
            Text.actionBar(player, registry.get(), "cobweb-no-target", Map.of());
            return;
        }
        cooldowns.start(player, COBWEB, cobweb.cooldownSeconds() * 1000);
        player.playSound(center.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 1.2f);
        Text.actionBar(player, registry.get(), "cobweb-used", Map.of("count", Integer.toString(placed)));
    }

    private void summon(Player player, MaskLevel.SummonAbility ability) {
        if (onCooldown(player, SUMMON)) {
            return;
        }
        Registry reg = registry.get();
        Entity target = player.getTargetEntity(ability.range());
        if (!(target instanceof Player victim)) {
            Text.actionBar(player, reg, "summon-no-target", Map.of());
            return;
        }
        if (!player.getWorld().getPVP()) {
            Text.actionBar(player, reg, "summon-pvp-disabled", Map.of());
            return;
        }
        if (!Protection.canTarget(player, victim, reg.settings.summonRespectTeams)) {
            Text.actionBar(player, reg, "summon-no-target", Map.of());
            return;
        }
        int spawned = summons.summon(player, victim, ability, reg.settings.summonMaxActive);
        if (spawned == 0) {
            Text.actionBar(player, reg, "summon-no-target", Map.of());
            return;
        }
        cooldowns.start(player, SUMMON, ability.cooldownSeconds() * 1000);
        player.playSound(player.getLocation(), ability.entity() == org.bukkit.entity.EntityType.WOLF
                ? Sound.ENTITY_WOLF_GROWL : Sound.ENTITY_IRON_GOLEM_REPAIR, 1f, 1f);
        Text.actionBar(player, reg, "summon-used", Map.of("count", Integer.toString(spawned), "player", victim.getName()));
    }

    // ------------------------------------------------------------------ Skelly arrow teleport

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getProjectile() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!player.isSneaking() || event.getBow() == null || event.getBow().getType() != Material.BOW) {
            return;
        }
        MaskLevel mask = equipment.current(player).maskLevel();
        if (mask == null || mask.arrowTeleport() == null || !player.hasPermission("exoblacksmith.ability.mask")) {
            return;
        }
        if (pendingArrows.containsKey(player.getUniqueId()) || onCooldown(player, TELEPORT)) {
            return;
        }
        MaskLevel.ArrowTeleportAbility ability = mask.arrowTeleport();
        arrow.getPersistentDataContainer().set(keys.abilityArrow, PersistentDataType.STRING, player.getUniqueId().toString());
        cooldowns.start(player, TELEPORT, ability.cooldownSeconds() * 1000);
        pendingArrows.put(player.getUniqueId(), new PendingArrow(arrow.getUniqueId(),
                System.currentTimeMillis() + ability.maxFlightSeconds() * 1000L, ability.missCooldownSeconds() * 1000));
        Text.actionBar(player, registry.get(), "teleport-armed", Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        String owner = arrow.getPersistentDataContainer().get(keys.abilityArrow, PersistentDataType.STRING);
        if (owner == null) {
            return;
        }
        arrow.getPersistentDataContainer().remove(keys.abilityArrow);
        UUID ownerId;
        try {
            ownerId = UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return;
        }
        PendingArrow pending = pendingArrows.get(ownerId);
        if (pending == null || !pending.arrow().equals(arrow.getUniqueId())) {
            return;
        }
        pendingArrows.remove(ownerId);
        Player player = Bukkit.getPlayer(ownerId);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        MaskLevel mask = equipment.current(player).maskLevel();
        Location destination = null;
        if (!event.isCancelled() && mask != null && mask.arrowTeleport() != null
                && player.getWorld().equals(arrow.getWorld())) {
            Location raw;
            if (event.getHitBlock() != null && event.getHitBlockFace() != null) {
                raw = event.getHitBlock().getRelative(event.getHitBlockFace()).getLocation();
            } else if (event.getHitEntity() != null) {
                raw = event.getHitEntity().getLocation();
            } else {
                raw = arrow.getLocation();
            }
            destination = SafeLocations.find(raw, player.getLocation().getYaw(), player.getLocation().getPitch());
        }
        if (destination == null || !player.teleport(destination, registry.get().settings.teleportCause)) {
            cooldowns.start(player, TELEPORT, pending.missCooldownMs());
            Text.actionBar(player, registry.get(), "teleport-failed", Map.of(
                    "time", Durations.format(pending.missCooldownMs())));
            return;
        }
        player.setFallDistance(0);
        player.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.3f);
        Text.actionBar(player, registry.get(), "teleport-success", Map.of());
    }

    private void expireArrows() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, PendingArrow>> it = pendingArrows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PendingArrow> e = it.next();
            Entity arrow = Bukkit.getEntity(e.getValue().arrow());
            if (now > e.getValue().deadline() || arrow == null || !arrow.isValid()) {
                if (arrow != null) {
                    arrow.getPersistentDataContainer().remove(keys.abilityArrow);
                }
                Player player = Bukkit.getPlayer(e.getKey());
                if (player != null) {
                    cooldowns.start(player, TELEPORT, e.getValue().missCooldownMs());
                    Text.actionBar(player, registry.get(), "teleport-failed", Map.of(
                            "time", Durations.format(e.getValue().missCooldownMs())));
                }
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastActivation.remove(event.getPlayer().getUniqueId());
        PendingArrow pending = pendingArrows.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            Entity arrow = Bukkit.getEntity(pending.arrow());
            if (arrow != null) {
                arrow.getPersistentDataContainer().remove(keys.abilityArrow);
            }
        }
    }
}
