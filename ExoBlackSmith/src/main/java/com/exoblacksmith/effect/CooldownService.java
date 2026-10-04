package com.exoblacksmith.effect;

import com.exoblacksmith.item.ItemKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Wall-clock cooldowns keyed by ability (not by item), stored in the player's persistent data container.
 * Swapping equipment, relogging, plugin reloads and server restarts therefore do not reset them.
 * Limitation: the player data file is written by the server's autosave/quit; a hard crash can lose
 * cooldowns started since the last save.
 */
public final class CooldownService {
    private final ItemKeys keys;
    private final Map<UUID, Map<String, Long>> cache = new ConcurrentHashMap<>();

    public CooldownService(ItemKeys keys) {
        this.keys = keys;
    }

    private Map<String, Long> of(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), id -> load(player));
    }

    private Map<String, Long> load(Player player) {
        Map<String, Long> map = new HashMap<>();
        String raw = player.getPersistentDataContainer().get(keys.cooldowns, PersistentDataType.STRING);
        if (raw != null && !raw.isEmpty()) {
            long now = System.currentTimeMillis();
            for (String entry : raw.split(";")) {
                int eq = entry.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    long until = Long.parseLong(entry.substring(eq + 1));
                    if (until > now) {
                        map.put(entry.substring(0, eq), until);
                    }
                } catch (NumberFormatException ignored) {
                    // corrupt entry: drop it
                }
            }
        }
        return map;
    }

    private void save(Player player, Map<String, Long> map) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        map.entrySet().removeIf(e -> e.getValue() <= now);
        map.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(k).append('=').append(v);
        });
        if (sb.isEmpty()) {
            player.getPersistentDataContainer().remove(keys.cooldowns);
        } else {
            player.getPersistentDataContainer().set(keys.cooldowns, PersistentDataType.STRING, sb.toString());
        }
    }

    /** Remaining milliseconds, 0 if ready. */
    public long remaining(Player player, String ability) {
        Long until = of(player).get(ability);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    public boolean ready(Player player, String ability) {
        return remaining(player, ability) == 0;
    }

    public void start(Player player, String ability, long millis) {
        Map<String, Long> map = of(player);
        if (millis <= 0) {
            map.remove(ability);
        } else {
            map.put(ability, System.currentTimeMillis() + millis);
        }
        save(player, map);
    }

    public void clear(Player player, String ability) {
        start(player, ability, 0);
    }

    public void forget(UUID player) {
        cache.remove(player);
    }
}
