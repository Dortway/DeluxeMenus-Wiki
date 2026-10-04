package com.exoblacksmith.effect;

import com.exoblacksmith.config.model.Reduction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived damage reductions granted by armor set abilities (wards, post-dash fall protection). */
public final class TempBuffs {
    private record Buff(List<Reduction> reductions, long expiresAt, boolean extinguish) {
    }

    private final Map<UUID, List<Buff>> buffs = new ConcurrentHashMap<>();

    public void add(UUID player, List<Reduction> reductions, double seconds, boolean extinguish) {
        long until = System.currentTimeMillis() + (long) (seconds * 1000);
        buffs.computeIfAbsent(player, id -> new ArrayList<>()).add(new Buff(List.copyOf(reductions), until, extinguish));
    }

    public List<Reduction> active(UUID player) {
        List<Buff> list = buffs.get(player);
        if (list == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        list.removeIf(b -> b.expiresAt() <= now);
        List<Reduction> out = new ArrayList<>();
        for (Buff b : list) {
            out.addAll(b.reductions());
        }
        return out;
    }

    public boolean extinguishing(UUID player) {
        List<Buff> list = buffs.get(player);
        if (list == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Buff b : list) {
            if (b.extinguish() && b.expiresAt() > now) {
                return true;
            }
        }
        return false;
    }

    public void clear(UUID player) {
        buffs.remove(player);
    }
}
