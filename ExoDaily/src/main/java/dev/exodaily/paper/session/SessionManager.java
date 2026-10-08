package dev.exodaily.paper.session;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions exist only for online players who used ExoDaily and are removed on quit, so the map
 * is bounded by the online player count. Tracks which players have an ExoDaily menu open so
 * periodic work only touches those players.
 */
public final class SessionManager {

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> openMenus = ConcurrentHashMap.newKeySet();

    public PlayerSession get(UUID uuid) {
        return sessions.computeIfAbsent(uuid, PlayerSession::new);
    }

    public PlayerSession peek(UUID uuid) {
        return sessions.get(uuid);
    }

    public void remove(UUID uuid) {
        sessions.remove(uuid);
        openMenus.remove(uuid);
    }

    public Collection<PlayerSession> all() {
        return sessions.values();
    }

    public void menuOpened(UUID uuid) {
        openMenus.add(uuid);
    }

    public void menuClosed(UUID uuid) {
        openMenus.remove(uuid);
    }

    public Set<UUID> openMenus() {
        return Set.copyOf(openMenus);
    }

    public void clear() {
        sessions.clear();
        openMenus.clear();
    }
}
