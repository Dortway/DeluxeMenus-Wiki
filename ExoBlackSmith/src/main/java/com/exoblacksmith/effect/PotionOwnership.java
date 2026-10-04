package com.exoblacksmith.effect;

import com.exoblacksmith.config.model.PotionSpec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Applies mask potion effects without clobbering effects from other sources.
 * <ul>
 *   <li>Owned effects use a signature: infinite duration, ambient, no particles, expected amplifier.</li>
 *   <li>If an equal or stronger effect from another source is active, ours is not applied (it is
 *       re-checked every scan and applied once the other one ends).</li>
 *   <li>If a weaker effect from another source is active, it is snapshotted and restored (with its
 *       remaining duration) when ours is removed.</li>
 *   <li>If another source later puts a stronger effect on top of ours, ours is removed as soon as it
 *       resurfaces after the mask is gone.</li>
 * </ul>
 * Limitation: a third-party effect with exactly our signature is indistinguishable from ours.
 */
final class PotionOwnership {
    private record Snapshot(PotionEffect effect, long takenAt) {
    }

    private final Map<PotionEffectType, Integer> applied = new HashMap<>();
    private final Map<PotionEffectType, Snapshot> snapshots = new HashMap<>();
    private final Map<PotionEffectType, Integer> pendingCleanup = new HashMap<>();

    static boolean isOurs(PotionEffect effect, int amplifier) {
        return effect != null && effect.isInfinite() && effect.isAmbient() && !effect.hasParticles()
                && effect.getAmplifier() == amplifier;
    }

    void sync(Player player, List<PotionSpec> desired) {
        Map<PotionEffectType, Integer> want = new HashMap<>();
        for (PotionSpec spec : desired) {
            want.merge(spec.type(), spec.amplifier(), Math::max);
        }
        // remove effects we no longer want (or whose amplifier changed)
        for (Iterator<Map.Entry<PotionEffectType, Integer>> it = applied.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<PotionEffectType, Integer> e = it.next();
            Integer wanted = want.get(e.getKey());
            if (wanted != null && wanted.equals(e.getValue())) {
                continue;
            }
            removeOwned(player, e.getKey(), e.getValue());
            it.remove();
        }
        // pending cleanups: ours was buried under another effect; remove it once it resurfaces
        for (Iterator<Map.Entry<PotionEffectType, Integer>> it = pendingCleanup.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<PotionEffectType, Integer> e = it.next();
            if (applied.containsKey(e.getKey())) {
                it.remove();
                continue;
            }
            PotionEffect current = player.getPotionEffect(e.getKey());
            if (current == null) {
                it.remove();
            } else if (isOurs(current, e.getValue())) {
                player.removePotionEffect(e.getKey());
                it.remove();
            }
        }
        // apply what we want
        for (Map.Entry<PotionEffectType, Integer> e : want.entrySet()) {
            PotionEffectType type = e.getKey();
            int amp = e.getValue();
            PotionEffect current = player.getPotionEffect(type);
            if (isOurs(current, amp)) {
                applied.put(type, amp);
                continue;
            }
            if (current != null && current.getAmplifier() >= amp) {
                applied.remove(type); // a stronger or equal external effect wins; try again next scan
                continue;
            }
            if (current != null && !snapshots.containsKey(type)) {
                snapshots.put(type, new Snapshot(current, System.currentTimeMillis()));
            }
            player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, true, false, true));
            applied.put(type, amp);
        }
    }

    private void removeOwned(Player player, PotionEffectType type, int amp) {
        PotionEffect current = player.getPotionEffect(type);
        if (isOurs(current, amp)) {
            player.removePotionEffect(type);
            restore(player, type);
        } else if (current != null) {
            pendingCleanup.put(type, amp);
            snapshots.remove(type);
        } else {
            snapshots.remove(type);
        }
    }

    private void restore(Player player, PotionEffectType type) {
        Snapshot snap = snapshots.remove(type);
        if (snap == null) {
            return;
        }
        PotionEffect old = snap.effect();
        int duration;
        if (old.isInfinite()) {
            duration = PotionEffect.INFINITE_DURATION;
        } else {
            long elapsedTicks = (System.currentTimeMillis() - snap.takenAt()) / 50L;
            duration = (int) Math.max(0, old.getDuration() - elapsedTicks);
            if (duration <= 0) {
                return;
            }
        }
        player.addPotionEffect(new PotionEffect(type, duration, old.getAmplifier(), old.isAmbient(), old.hasParticles(), old.hasIcon()));
    }

    /** Removes every owned effect (quit, plugin disable). */
    void clear(Player player) {
        for (Map.Entry<PotionEffectType, Integer> e : applied.entrySet()) {
            removeOwned(player, e.getKey(), e.getValue());
        }
        applied.clear();
        pendingCleanup.entrySet().removeIf(e -> {
            PotionEffect current = player.getPotionEffect(e.getKey());
            if (isOurs(current, e.getValue())) {
                player.removePotionEffect(e.getKey());
                return true;
            }
            return current == null;
        });
        snapshots.clear();
    }

    /** Encodes buried owned effects so they can still be cleaned up after a relog. */
    String encodePending() {
        StringBuilder sb = new StringBuilder();
        pendingCleanup.forEach((type, amp) -> {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(type.getKey()).append('=').append(amp);
        });
        return sb.toString();
    }

    void decodePending(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String entry : encoded.split(";")) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(entry.substring(0, eq));
            PotionEffectType type = key == null ? null : org.bukkit.Registry.EFFECT.get(key);
            try {
                if (type != null) {
                    pendingCleanup.put(type, Integer.parseInt(entry.substring(eq + 1)));
                }
            } catch (NumberFormatException ignored) {
                // skip corrupt entry
            }
        }
    }

    /** After death vanilla has already cleared effects. */
    void reset() {
        applied.clear();
        snapshots.clear();
        pendingCleanup.clear();
    }
}
