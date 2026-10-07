package dev.exoquests.core.storage;

import dev.exoquests.core.quest.QuestAction;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestPool;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Persistence for daily assignments, progress and completions. All methods run inside a transaction. */
public final class QuestStore {

    private QuestStore() {
    }

    public static List<AssignmentRow> load(Connection c, UUID uuid, String period) throws SQLException {
        List<AssignmentRow> rows = new ArrayList<>(3);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT slot, quest_id, target, reward, progress, completed_at FROM daily_assignments "
                        + "WHERE uuid = ? AND period = ? ORDER BY slot")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, period);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getLong(6);
                    boolean completed = !rs.wasNull();
                    rows.add(new AssignmentRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5), completed));
                }
            }
        }
        return rows;
    }

    /**
     * Returns the player's assignment for {@code period}, creating it if absent. Existing rows are never
     * replaced, so reconnects, restarts and reloads cannot reroll quests. Missing slots (which only occur
     * after manual database edits) are filled with quests not already assigned.
     */
    public static List<AssignmentRow> loadOrAssign(Connection c, UUID uuid, String period, QuestPool pool,
                                                   Random random, long now) throws SQLException {
        List<AssignmentRow> existing = load(c, uuid, period);
        if (existing.size() >= QuestPool.SLOTS) {
            return existing;
        }
        Set<Integer> usedSlots = new HashSet<>();
        Set<String> usedQuests = new HashSet<>();
        for (AssignmentRow r : existing) {
            usedSlots.add(r.slot());
            usedQuests.add(r.questId());
        }
        List<QuestDefinition> candidates = new ArrayList<>(pool.pick(random));
        // If a picked quest is already assigned, draw replacements from the rest of the pool.
        candidates.removeIf(d -> usedQuests.contains(d.id()));
        if (candidates.size() < QuestPool.SLOTS - existing.size()) {
            for (QuestDefinition d : pool.enabled()) {
                if (!usedQuests.contains(d.id()) && !candidates.contains(d)) {
                    candidates.add(d);
                }
            }
        }
        int next = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_assignments(uuid, period, slot, quest_id, target, reward, progress, assigned_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?)")) {
            for (int slot = 0; slot < QuestPool.SLOTS; slot++) {
                if (usedSlots.contains(slot)) {
                    continue;
                }
                QuestDefinition d = candidates.get(next++);
                ps.setString(1, uuid.toString());
                ps.setString(2, period);
                ps.setInt(3, slot);
                ps.setString(4, d.id());
                ps.setInt(5, d.target());
                ps.setInt(6, d.reward());
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        }
        return load(c, uuid, period);
    }

    /** Persists progress monotonically; never lowers progress and never touches completed slots. */
    public static void saveProgress(Connection c, UUID uuid, String period, int slot, int progress)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE daily_assignments SET progress = MAX(progress, MIN(?, target - 1)) "
                        + "WHERE uuid = ? AND period = ? AND slot = ? AND completed_at IS NULL")) {
            ps.setInt(1, Math.max(0, progress));
            ps.setString(2, uuid.toString());
            ps.setString(3, period);
            ps.setInt(4, slot);
            ps.executeUpdate();
        }
    }

    /**
     * Marks a slot completed and credits its reward in the same transaction. The conditional update on
     * {@code completed_at IS NULL} guarantees that at most one attempt per slot is credited, regardless
     * of how many callers race.
     */
    public static CompletionResult complete(Connection c, UUID uuid, String period, int slot, long maxBalance,
                                            long now) throws SQLException {
        String completionId = UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE daily_assignments SET progress = target, completed_at = ?, completion_id = ? "
                        + "WHERE uuid = ? AND period = ? AND slot = ? AND completed_at IS NULL")) {
            ps.setLong(1, now);
            ps.setString(2, completionId);
            ps.setString(3, uuid.toString());
            ps.setString(4, period);
            ps.setInt(5, slot);
            if (ps.executeUpdate() != 1) {
                return CompletionResult.notCredited(slot);
            }
        }
        String questId;
        int reward;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT quest_id, reward FROM daily_assignments WHERE uuid = ? AND period = ? AND slot = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, period);
            ps.setInt(3, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("completed slot vanished");
                }
                questId = rs.getString(1);
                reward = rs.getInt(2);
            }
        }
        PointsStore.Change change = PointsStore.credit(c, uuid, reward, maxBalance, "quest:" + questId,
                "quest:" + completionId, "system", now);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO quest_completions(completion_id, uuid, period, slot, quest_id, reward, credited, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, completionId);
            ps.setString(2, uuid.toString());
            ps.setString(3, period);
            ps.setInt(4, slot);
            ps.setString(5, questId);
            ps.setInt(6, reward);
            ps.setLong(7, change.delta());
            ps.setLong(8, now);
            ps.executeUpdate();
        }
        return new CompletionResult(true, slot, questId, reward, change.delta(), change.balance(), completionId);
    }

    /**
     * Applies progress for a player who is not online (for example a sapling they planted grew while they
     * were away). Creates the day's assignment if needed so the result is identical to an online credit.
     */
    public static List<CompletionResult> applyOffline(Connection c, UUID uuid, String period, QuestAction action,
                                                      QuestPool pool, Random random, long maxBalance, long now)
            throws SQLException {
        List<CompletionResult> results = new ArrayList<>();
        for (AssignmentRow row : loadOrAssign(c, uuid, period, pool, random, now)) {
            if (row.completed()) {
                continue;
            }
            QuestDefinition def = pool.get(row.questId()).orElse(null);
            if (def == null || !def.matches(action)) {
                continue;
            }
            long next = Math.min((long) row.target(), (long) row.progress() + action.amount());
            if (next >= row.target()) {
                results.add(complete(c, uuid, period, row.slot(), maxBalance, now));
            } else {
                saveProgress(c, uuid, period, row.slot(), (int) next);
            }
        }
        return results;
    }

    /** Deletes the player's assignment for a period (admin reset). Earned points are kept. */
    public static int reset(Connection c, UUID uuid, String period) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM daily_assignments WHERE uuid = ? AND period = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, period);
            return ps.executeUpdate();
        }
    }

    /** Deletes assignment rows for periods strictly before {@code periodKey} (ISO dates sort lexically). */
    public static int pruneBefore(Connection c, String periodKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM daily_assignments WHERE period < ?")) {
            ps.setString(1, periodKey);
            return ps.executeUpdate();
        }
    }

    public static int completionCount(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM quest_completions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
