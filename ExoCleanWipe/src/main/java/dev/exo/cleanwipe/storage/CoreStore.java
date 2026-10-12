package dev.exo.cleanwipe.storage;

import dev.exo.cleanwipe.storage.Records.Account;
import dev.exo.cleanwipe.storage.Records.IpEntry;
import dev.exo.cleanwipe.storage.Records.Job;
import dev.exo.cleanwipe.storage.Records.JobState;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Lock;
import dev.exo.cleanwipe.storage.Records.NameEntry;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.Punishment;
import dev.exo.cleanwipe.storage.Records.PunishmentKind;
import dev.exo.cleanwipe.storage.Records.Step;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.storage.Records.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * ExoCleanWipe's own database: identities, IP history, punishments, wipe jobs and locks. One connection, every
 * method synchronised; all statements are prepared (no user input is ever concatenated into SQL).
 */
public final class CoreStore implements AutoCloseable {

    public static final String ALL = "*";
    private static final int SCHEMA = 1;

    private final Connection c;

    public CoreStore(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create " + file.getParent(), e);
        }
        this.c = Sqlite.open(file, false);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            int version = 0;
            try (ResultSet rs = st.executeQuery("SELECT v FROM meta WHERE k='schema'")) {
                if (rs.next()) {
                    version = Integer.parseInt(rs.getString(1));
                }
            }
            if (version > SCHEMA) {
                throw new SQLException("exocleanwipe.db was written by a newer ExoCleanWipe (schema " + version + ")");
            }
            if (version < 1) {
                c.setAutoCommit(false);
                try {
                    st.execute("""
                            CREATE TABLE accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL,
                              first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)""");
                    st.execute("CREATE INDEX accounts_name ON accounts(name_lower)");
                    st.execute("""
                            CREATE TABLE name_history (uuid TEXT NOT NULL, name TEXT NOT NULL, first_seen INTEGER NOT NULL,
                              last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, name))""");
                    st.execute("""
                            CREATE TABLE ip_history (uuid TEXT NOT NULL, ip TEXT NOT NULL, first_seen INTEGER NOT NULL,
                              last_seen INTEGER NOT NULL, logins INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (uuid, ip))""");
                    st.execute("CREATE INDEX ip_history_ip ON ip_history(ip)");
                    st.execute("""
                            CREATE TABLE punishments (id INTEGER PRIMARY KEY AUTOINCREMENT,
                              kind TEXT NOT NULL CHECK (kind IN ('ACCOUNT','IP_GROUP')), uuid TEXT NOT NULL,
                              name TEXT NOT NULL, reason TEXT NOT NULL, actor_uuid TEXT, actor_name TEXT NOT NULL,
                              created_at INTEGER NOT NULL, expires_at INTEGER, job_id INTEGER,
                              active INTEGER NOT NULL DEFAULT 1, removed_at INTEGER, removed_by TEXT, removed_note TEXT)""");
                    st.execute("CREATE INDEX punishments_uuid ON punishments(uuid, active)");
                    st.execute("""
                            CREATE TABLE punished_ips (punishment_id INTEGER NOT NULL REFERENCES punishments(id),
                              ip TEXT NOT NULL, PRIMARY KEY (punishment_id, ip))""");
                    st.execute("CREATE INDEX punished_ips_ip ON punished_ips(ip)");
                    st.execute("""
                            CREATE TABLE ip_restricted (punishment_id INTEGER NOT NULL REFERENCES punishments(id),
                              uuid TEXT NOT NULL, name TEXT, ip TEXT NOT NULL, first_blocked INTEGER NOT NULL,
                              last_blocked INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 1,
                              PRIMARY KEY (punishment_id, uuid))""");
                    st.execute("""
                            CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, state TEXT NOT NULL,
                              actor_uuid TEXT, actor_name TEXT NOT NULL, reason TEXT, options TEXT NOT NULL DEFAULT '',
                              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, finished_at INTEGER,
                              punishment_id INTEGER, note TEXT)""");
                    st.execute("""
                            CREATE TABLE job_targets (job_id INTEGER NOT NULL REFERENCES jobs(id), uuid TEXT NOT NULL,
                              name TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY (job_id, uuid))""");
                    st.execute("""
                            CREATE TABLE job_steps (job_id INTEGER NOT NULL REFERENCES jobs(id), uuid TEXT NOT NULL,
                              integration TEXT NOT NULL, phase TEXT NOT NULL, state TEXT NOT NULL, detail TEXT,
                              attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
                              PRIMARY KEY (job_id, uuid, integration))""");
                    st.execute("CREATE INDEX job_steps_pending ON job_steps(state, phase)");
                    st.execute("""
                            CREATE TABLE locks (uuid TEXT PRIMARY KEY, job_id INTEGER NOT NULL, reason TEXT NOT NULL,
                              created_at INTEGER NOT NULL)""");
                    st.execute("""
                            CREATE TABLE vpn_cache (ip TEXT PRIMARY KEY, verdict TEXT NOT NULL, detail TEXT,
                              provider TEXT NOT NULL, checked_at INTEGER NOT NULL)""");
                    st.execute("""
                            CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL,
                              action TEXT NOT NULL, targets TEXT, reason TEXT, result TEXT, detail TEXT)""");
                    st.execute("INSERT OR REPLACE INTO meta (k, v) VALUES ('schema', '1')");
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        c.close();
    }

    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs several statements atomically. */
    public synchronized <T> T transaction(Work<T> work) throws SQLException {
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static String key(UUID uuid) {
        return uuid == null ? ALL : uuid.toString();
    }

    private static UUID uuidOrNull(String s) {
        return s == null || s.equals(ALL) ? null : UUID.fromString(s);
    }

    // ------------------------------------------------------------------ identities

    public synchronized void recordLogin(UUID uuid, String name, String ip, long now) throws SQLException {
        transaction(conn -> {
            Sqlite.update(conn, """
                    INSERT INTO accounts (uuid, name, name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, name_lower=excluded.name_lower, last_seen=excluded.last_seen""",
                    uuid, name, name.toLowerCase(Locale.ROOT), now, now);
            Sqlite.update(conn, """
                    INSERT INTO name_history (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
                    ON CONFLICT(uuid, name) DO UPDATE SET last_seen=excluded.last_seen""", uuid, name, now, now);
            if (ip != null) {
                Sqlite.update(conn, """
                        INSERT INTO ip_history (uuid, ip, first_seen, last_seen, logins) VALUES (?, ?, ?, ?, 1)
                        ON CONFLICT(uuid, ip) DO UPDATE SET last_seen=excluded.last_seen, logins=logins+1""",
                        uuid, ip, now, now);
            }
            return null;
        });
    }

    public synchronized boolean isKnown(UUID uuid) throws SQLException {
        return Sqlite.count(c, "SELECT COUNT(*) FROM accounts WHERE uuid=?", uuid) > 0;
    }

    public synchronized Optional<Account> account(UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, first_seen, last_seen FROM accounts WHERE uuid=?")) {
            Sqlite.bind(ps, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    /** All accounts that have used this name, most recent holder first. */
    public synchronized List<Account> accountsByName(String name) throws SQLException {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.uuid, a.name, a.first_seen, a.last_seen FROM accounts a
                WHERE a.name_lower = ? OR a.uuid IN (SELECT uuid FROM name_history WHERE lower(name) = ?)
                ORDER BY (a.name_lower = ?) DESC, a.last_seen DESC""")) {
            String lower = name.toLowerCase(Locale.ROOT);
            Sqlite.bind(ps, lower, lower, lower);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(account(rs));
                }
            }
        }
        return out;
    }

    public synchronized List<String> namesStartingWith(String prefix, int limit) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM accounts WHERE name_lower LIKE ? ESCAPE '\\' ORDER BY last_seen DESC LIMIT ?")) {
            String escaped = prefix.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            Sqlite.bind(ps, escaped + "%", limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    public synchronized List<UUID> allAccountIds() throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM accounts"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(UUID.fromString(rs.getString(1)));
            }
        }
        return out;
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4));
    }

    public synchronized List<NameEntry> nameHistory(UUID uuid) throws SQLException {
        List<NameEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, first_seen, last_seen FROM name_history WHERE uuid=? ORDER BY first_seen")) {
            Sqlite.bind(ps, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NameEntry(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
        }
        return out;
    }

    public synchronized List<IpEntry> ipsOf(UUID uuid) throws SQLException {
        return ipQuery("SELECT uuid, ip, first_seen, last_seen, logins FROM ip_history WHERE uuid=? ORDER BY last_seen DESC", uuid);
    }

    public synchronized List<IpEntry> accountsOnIp(String ip) throws SQLException {
        return ipQuery("SELECT uuid, ip, first_seen, last_seen, logins FROM ip_history WHERE ip=? ORDER BY last_seen DESC", ip);
    }

    private List<IpEntry> ipQuery(String sql, Object param) throws SQLException {
        List<IpEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Sqlite.bind(ps, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new IpEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5)));
                }
            }
        }
        return out;
    }

    public synchronized int purgeIpHistoryBefore(long cutoff) throws SQLException {
        return Sqlite.update(c, "DELETE FROM ip_history WHERE last_seen < ?", cutoff);
    }

    // ------------------------------------------------------------------ punishments

    public synchronized long insertPunishment(Connection conn, PunishmentKind kind, UUID uuid, String name, String reason,
                                              UUID actor, String actorName, long now, Long expiresAt, Long jobId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO punishments (kind, uuid, name, reason, actor_uuid, actor_name, created_at, expires_at, job_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            Sqlite.bind(ps, kind.name(), uuid, name, reason, actor, actorName, now, expiresAt, jobId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public synchronized void addPunishedIp(Connection conn, long punishmentId, String ip) throws SQLException {
        Sqlite.update(conn, "INSERT OR IGNORE INTO punished_ips (punishment_id, ip) VALUES (?, ?)", punishmentId, ip);
    }

    private static final String P_COLS = "id, kind, uuid, name, reason, actor_name, created_at, expires_at, job_id, active";

    private static Punishment punishment(ResultSet rs) throws SQLException {
        long exp = rs.getLong(8);
        Long expires = rs.wasNull() ? null : exp;
        long job = rs.getLong(9);
        Long jobId = rs.wasNull() ? null : job;
        return new Punishment(rs.getLong(1), PunishmentKind.valueOf(rs.getString(2)), UUID.fromString(rs.getString(3)),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), expires, jobId, rs.getInt(10) != 0);
    }

    private List<Punishment> punishments(String sql, Object... params) throws SQLException {
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Sqlite.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(punishment(rs));
                }
            }
        }
        return out;
    }

    /** Active (not removed) punishments of an account, newest first. Expiry is checked by the caller. */
    public synchronized List<Punishment> activePunishments(UUID uuid) throws SQLException {
        return punishments("SELECT " + P_COLS + " FROM punishments WHERE uuid=? AND active=1 ORDER BY created_at DESC", uuid);
    }

    public synchronized List<Punishment> history(UUID uuid, int limit) throws SQLException {
        return punishments("SELECT " + P_COLS + " FROM punishments WHERE uuid=? ORDER BY created_at DESC LIMIT ?", uuid, limit);
    }

    public synchronized Optional<Punishment> activeIpPunishment(String ip) throws SQLException {
        List<Punishment> list = punishments("SELECT p." + P_COLS.replace(", ", ", p.") + " FROM punishments p "
                + "JOIN punished_ips i ON i.punishment_id = p.id WHERE i.ip=? AND p.active=1 AND p.kind='IP_GROUP' "
                + "ORDER BY p.created_at DESC LIMIT 1", ip);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public synchronized Optional<Punishment> punishment(long id) throws SQLException {
        List<Punishment> list = punishments("SELECT " + P_COLS + " FROM punishments WHERE id=?", id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public synchronized List<String> punishedIps(long punishmentId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT ip FROM punished_ips WHERE punishment_id=? ORDER BY ip")) {
            Sqlite.bind(ps, punishmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    public synchronized boolean deactivate(long id, String by, String note, long now) throws SQLException {
        return Sqlite.update(c, "UPDATE punishments SET active=0, removed_at=?, removed_by=?, removed_note=? WHERE id=? AND active=1",
                now, by, note, id) > 0;
    }

    public synchronized void recordRestricted(long punishmentId, UUID uuid, String name, String ip, long now) throws SQLException {
        Sqlite.update(c, """
                INSERT INTO ip_restricted (punishment_id, uuid, name, ip, first_blocked, last_blocked, attempts)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(punishment_id, uuid) DO UPDATE SET last_blocked=excluded.last_blocked, ip=excluded.ip,
                  name=excluded.name, attempts=attempts+1""", punishmentId, uuid, name, ip, now, now);
    }

    public synchronized int releaseRestricted(long punishmentId) throws SQLException {
        return Sqlite.update(c, "DELETE FROM ip_restricted WHERE punishment_id=?", punishmentId);
    }

    // ------------------------------------------------------------------ jobs

    public synchronized long insertJob(Connection conn, JobType type, UUID actor, String actorName, String reason,
                                       String options, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO jobs (type, state, actor_uuid, actor_name, reason, options, created_at, updated_at)
                VALUES (?, 'RUNNING', ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            Sqlite.bind(ps, type.name(), actor, actorName, reason, options, now, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public synchronized void setJobPunishment(Connection conn, long jobId, long punishmentId) throws SQLException {
        Sqlite.update(conn, "UPDATE jobs SET punishment_id=? WHERE id=?", punishmentId, jobId);
    }

    public synchronized void insertTarget(Connection conn, long jobId, UUID uuid, String name, String role) throws SQLException {
        Sqlite.update(conn, "INSERT OR IGNORE INTO job_targets (job_id, uuid, name, role) VALUES (?, ?, ?, ?)", jobId, uuid, name, role);
    }

    public synchronized void insertStep(Connection conn, long jobId, UUID uuid, String integration, Phase phase, long now)
            throws SQLException {
        Sqlite.update(conn, """
                INSERT OR IGNORE INTO job_steps (job_id, uuid, integration, phase, state, attempts, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?)""", jobId, key(uuid), integration, phase.name(), now);
    }

    public synchronized void insertLock(Connection conn, UUID uuid, long jobId, String reason, long now) throws SQLException {
        Sqlite.update(conn, """
                INSERT INTO locks (uuid, job_id, reason, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET job_id=excluded.job_id, reason=excluded.reason""", key(uuid), jobId, reason, now);
    }

    private static Job job(ResultSet rs) throws SQLException {
        long p = rs.getLong(9);
        Long pid = rs.wasNull() ? null : p;
        return new Job(rs.getLong(1), JobType.valueOf(rs.getString(2)), JobState.valueOf(rs.getString(3)), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), pid, rs.getString(10));
    }

    private static final String J_COLS = "id, type, state, actor_name, reason, options, created_at, updated_at, punishment_id, note";

    public synchronized Optional<Job> job(long id) throws SQLException {
        List<Job> list = jobs("SELECT " + J_COLS + " FROM jobs WHERE id=?", id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public synchronized List<Job> recentJobs(int limit) throws SQLException {
        return jobs("SELECT " + J_COLS + " FROM jobs ORDER BY id DESC LIMIT ?", limit);
    }

    public synchronized List<Job> jobsIn(Collection<JobState> states) throws SQLException {
        List<Job> out = new ArrayList<>();
        for (JobState s : states) {
            out.addAll(jobs("SELECT " + J_COLS + " FROM jobs WHERE state=? ORDER BY id", s.name()));
        }
        return out;
    }

    private List<Job> jobs(String sql, Object... params) throws SQLException {
        List<Job> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Sqlite.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(job(rs));
                }
            }
        }
        return out;
    }

    public synchronized void setJobState(long jobId, JobState state, String note, long now) throws SQLException {
        boolean finished = state == JobState.COMPLETED || state == JobState.CANCELLED;
        Sqlite.update(c, "UPDATE jobs SET state=?, note=COALESCE(?, note), updated_at=?, finished_at=? WHERE id=?",
                state.name(), note, now, finished ? now : null, jobId);
    }

    public synchronized List<Target> targets(long jobId) throws SQLException {
        List<Target> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT job_id, uuid, name, role FROM job_targets WHERE job_id=? ORDER BY role DESC, name")) {
            Sqlite.bind(ps, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Target(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    private static Step step(ResultSet rs) throws SQLException {
        return new Step(rs.getLong(1), uuidOrNull(rs.getString(2)), rs.getString(3), Phase.valueOf(rs.getString(4)),
                StepState.valueOf(rs.getString(5)), rs.getString(6), rs.getInt(7), rs.getLong(8));
    }

    private static final String S_COLS = "job_id, uuid, integration, phase, state, detail, attempts, updated_at";

    public synchronized List<Step> steps(long jobId) throws SQLException {
        return stepQuery("SELECT " + S_COLS + " FROM job_steps WHERE job_id=? ORDER BY phase, integration, uuid", jobId);
    }

    public synchronized List<Step> pendingSteps(Phase phase) throws SQLException {
        return stepQuery("SELECT " + S_COLS + " FROM job_steps WHERE state='PENDING' AND phase=? ORDER BY job_id, rowid", phase.name());
    }

    /** Oldest pending steps of a phase, at most {@code limit} (bounded batches for the live runner). */
    public synchronized List<Step> pendingSteps(Phase phase, int limit) throws SQLException {
        return stepQuery("SELECT " + S_COLS + " FROM job_steps WHERE state='PENDING' AND phase=? ORDER BY job_id, rowid LIMIT ?",
                phase.name(), limit);
    }

    private List<Step> stepQuery(String sql, Object... params) throws SQLException {
        List<Step> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Sqlite.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(step(rs));
                }
            }
        }
        return out;
    }

    public synchronized void updateStep(long jobId, UUID uuid, String integration, StepState state, String detail, long now)
            throws SQLException {
        Sqlite.update(c, """
                UPDATE job_steps SET state=?, detail=?, attempts=attempts+1, updated_at=?
                WHERE job_id=? AND uuid=? AND integration=?""", state.name(), detail, now, jobId, key(uuid), integration);
    }

    /**
     * Puts failed or blocked steps of a job back to PENDING for a retry, and steps that were skipped because their
     * plugin looked uninstalled (so a job hit by that can be finished once the plugin is detected).
     */
    public synchronized int resetProblemSteps(long jobId, long now) throws SQLException {
        return Sqlite.update(c, "UPDATE job_steps SET state='PENDING', updated_at=? WHERE job_id=? AND (state IN ('FAILED','BLOCKED')"
                + " OR (state='SKIPPED' AND detail IN ('plugin not installed', 'plugin not enabled')))", now, jobId);
    }

    // ------------------------------------------------------------------ locks

    public synchronized Optional<Lock> lockOf(UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, job_id, reason, created_at FROM locks WHERE uuid=?")) {
            Sqlite.bind(ps, key(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Lock(uuidOrNull(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getLong(4)));
                }
            }
        }
        return Optional.empty();
    }

    public synchronized List<Lock> locksOfJob(long jobId) throws SQLException {
        List<Lock> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, job_id, reason, created_at FROM locks WHERE job_id=?")) {
            Sqlite.bind(ps, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Lock(uuidOrNull(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    public synchronized void lock(UUID uuid, long jobId, String reason, long now) throws SQLException {
        insertLock(c, uuid, jobId, reason, now);
    }

    public synchronized boolean unlock(UUID uuid) throws SQLException {
        return Sqlite.update(c, "DELETE FROM locks WHERE uuid=?", key(uuid)) > 0;
    }

    public synchronized int unlockJob(long jobId) throws SQLException {
        return Sqlite.update(c, "DELETE FROM locks WHERE job_id=?", jobId);
    }

    // ------------------------------------------------------------------ vpn cache

    public record VpnEntry(String verdict, String detail, long checkedAt) {
    }

    public synchronized Optional<VpnEntry> vpnCached(String ip, long notBefore) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT verdict, detail, checked_at FROM vpn_cache WHERE ip=? AND checked_at>=?")) {
            Sqlite.bind(ps, ip, notBefore);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new VpnEntry(rs.getString(1), rs.getString(2), rs.getLong(3))) : Optional.empty();
            }
        }
    }

    public synchronized void vpnStore(String ip, String verdict, String detail, String provider, long now) throws SQLException {
        Sqlite.update(c, """
                INSERT INTO vpn_cache (ip, verdict, detail, provider, checked_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(ip) DO UPDATE SET verdict=excluded.verdict, detail=excluded.detail, provider=excluded.provider,
                  checked_at=excluded.checked_at""", ip, verdict, detail, provider, now);
    }

    // ------------------------------------------------------------------ audit

    public synchronized void audit(String actor, String action, String targets, String reason, String result, String detail, long now)
            throws SQLException {
        Sqlite.update(c, "INSERT INTO audit (at, actor, action, targets, reason, result, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                now, actor, action, targets, reason, result, detail);
    }
}
