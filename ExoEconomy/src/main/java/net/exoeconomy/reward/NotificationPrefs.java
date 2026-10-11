package net.exoeconomy.reward;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player reward notification switches (stored as a bit set of muted channels; default: all on). */
public final class NotificationPrefs {
    public static final int CHAT = 1, ACTION_BAR = 2, SOUND = 4, ALL = CHAT | ACTION_BAR | SOUND;

    private final Map<UUID, Integer> muted = new HashMap<>();

    public NotificationPrefs(Map<UUID, Integer> loaded) { muted.putAll(loaded); }

    public boolean enabled(UUID player, int channel) { return (muted.getOrDefault(player, 0) & channel) == 0; }

    /**
     * Turns channels on or off; {@code on == null} flips them (all on → off, otherwise on).
     * Returns the new muted bit set for persistence.
     */
    public int set(UUID player, int channels, Boolean on) {
        int current = muted.getOrDefault(player, 0);
        boolean enable = on != null ? on : (current & channels) != 0;
        int next = enable ? current & ~channels : current | channels;
        if (next == 0) muted.remove(player); else muted.put(player, next);
        return next;
    }
}
