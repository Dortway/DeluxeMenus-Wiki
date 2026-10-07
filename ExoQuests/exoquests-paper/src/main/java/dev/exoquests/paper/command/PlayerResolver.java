package dev.exoquests.paper.command;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Resolves command arguments to player UUIDs without blocking web lookups. Accepted: an online player's
 * name, the name of any player who has joined this server before (server user cache), or a UUID.
 */
public final class PlayerResolver {

    public record Target(UUID uuid, String name) {
    }

    private PlayerResolver() {
    }

    public static Optional<Target> resolve(String input) {
        if (input.length() == 36 && input.chars().filter(c -> c == '-').count() == 4) {
            try {
                UUID uuid = UUID.fromString(input);
                OfflinePlayer known = Bukkit.getOfflinePlayer(uuid);
                return Optional.of(new Target(uuid, known.getName() != null ? known.getName() : input));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return Optional.of(new Target(online.getUniqueId(), online.getName()));
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null) {
            return Optional.of(new Target(cached.getUniqueId(), cached.getName() != null ? cached.getName() : input));
        }
        return Optional.empty();
    }
}
