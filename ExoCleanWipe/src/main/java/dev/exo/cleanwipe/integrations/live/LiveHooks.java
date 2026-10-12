package dev.exo.cleanwipe.integrations.live;

import dev.exo.cleanwipe.integrations.sql.SqlStartupIntegration;
import dev.exo.cleanwipe.storage.Sqlite;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Main-thread hooks that update a running plugin's memory before its rows are changed. Each uses the plugin's own
 * methods where one exists (the same code its admin commands run); member names are those of the installed
 * versions listed in docs/INTEGRATIONS.md.
 */
public final class LiveHooks {

    private LiveHooks() {
    }

    private static List<Object> copy(Object collection) {
        return new ArrayList<>((Collection<?>) collection);
    }

    private static boolean ownedBy(Object obj, UUID target) throws ReflectiveOperationException {
        return target.equals(Reflect.call(obj, "owner"));
    }

    private static String state(Object obj) throws ReflectiveOperationException {
        return String.valueOf(Reflect.call(obj, "state"));
    }

    /** ExoWoodCube 1.1.1: {@code service().remove(sender, cube)} (its /exowoodcube admin remove), which also clears the structure. */
    public static String woodCube(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        Object registry = Reflect.call(owner, "registry");
        Object service = Reflect.call(owner, "service");
        for (Object cube : copy(Reflect.call(registry, "all"))) {
            if (ownedBy(cube, target) && !state(cube).equals("REMOVED")) {
                Reflect.call(service, "remove", Bukkit.getConsoleSender(), cube);
            }
        }
        return null;
    }

    /** ExoFarmCube 1.1.0: {@code service().adminRemove(sender, cube, false, null)} (its /exofarmcube admin remove). */
    public static String farmCube(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        Object registry = Reflect.call(owner, "registry");
        Object service = Reflect.call(owner, "service");
        for (Object cube : copy(Reflect.call(registry, "all"))) {
            if (ownedBy(cube, target) && !state(cube).equals("REMOVED")) {
                Reflect.call(service, "adminRemove", Bukkit.getConsoleSender(), cube, false, null);
            }
        }
        return null;
    }

    /** ExoTools 1.0.0: {@code store().setState(id, REVOKED, note, actor)} (its /exotools revoke). */
    public static String tools(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        Object store = Reflect.call(owner, "store");
        Object revoked = Reflect.enumConstant(owner.getClass().getClassLoader(), "dev.exo.tools.core.ToolState", "REVOKED");
        for (Object rec : copy(Reflect.call(store, "all"))) {
            String st = state(rec);
            if (ownedBy(rec, target) && (st.equals("ACTIVE") || st.equals("LOCKED"))) {
                Reflect.call(store, "setState", Reflect.call(rec, "id"), revoked, "revoked by exocleanwipe", "exocleanwipe");
            }
        }
        return null;
    }

    /** LightZone 2.0.0: {@code backpacks().invalidate(id, true)} (its admin backpack invalidation), cache and database. */
    public static String lightZone(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        Object manager = Reflect.call(owner, "backpacks");
        List<UUID> ids = new ArrayList<>();
        try (Connection c = Sqlite.open(base.database(), true);
             PreparedStatement ps = c.prepareStatement("SELECT id FROM lz_backpacks WHERE owner = ? AND invalidated = 0")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        for (UUID id : ids) {
            Object f = Reflect.call(manager, "invalidate", id, true);
            if (f instanceof CompletableFuture<?> future) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        return null;
    }

    /** ExoSkills 1.0.3 caches every player's progress ({@code storage.players}); the entry is dropped before the rows. */
    @SuppressWarnings("unchecked")
    public static String skills(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        Object storage = Reflect.field(owner, "storage");
        if (storage == null) {
            return "ExoSkills storage is not running";
        }
        Map<UUID, ?> players = (Map<UUID, ?>) Reflect.field(storage, "players");
        synchronized (players) {
            players.remove(target);
        }
        return null;
    }

    /** ExoEconomy 1.0.0 keeps live boosters in memory; a player who owns one is finished at the next restart. */
    public static String economy(Plugin owner, UUID target, SqlStartupIntegration base) throws Exception {
        try (Connection c = Sqlite.open(base.database(), true)) {
            long live = Sqlite.count(c, "SELECT COUNT(*) FROM boosters WHERE owner = ? AND state IN ('ACTIVE','QUEUED','PAUSED')", target.toString());
            return live > 0 ? "the player owns " + live + " running ExoEconomy booster(s), which ExoEconomy keeps in memory" : null;
        }
    }
}
