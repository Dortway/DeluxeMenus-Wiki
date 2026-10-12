package dev.exo.cleanwipe.integrations.sql;

import dev.exo.cleanwipe.storage.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ChunkCollector 1.1.2 keeps each collector as one binary snapshot ({@code collectors.snapshot}). The header written
 * by its {@code SnapshotCodec} is {@code int version (1|2), UTF id, UTF owner, UTF ownerName, UTF state, ...}; only
 * the header is read here (item bytes are never decoded). Matching rows are deleted, which also deletes the items
 * stored in the collector. Unfinished collector transactions block the step.
 */
public final class ChunkCollectorIntegration extends SqlStartupIntegration {

    private static final String TERMINAL = "('DONE','ABORTED','RESOLVED_BEFORE','RESOLVED_AFTER')";

    public ChunkCollectorIntegration(Path database) {
        super("ChunkCollector", database);
    }

    @Override
    public String covers() {
        return "chunk collectors owned by the player and the items stored in them";
    }

    @Override
    protected List<String> requiredTables() {
        return List.of("collectors", "transactions");
    }

    /** Owner UUID from a snapshot header, or null when the header is not a known format. */
    static UUID ownerOf(byte[] snapshot) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            int version = in.readInt();
            if (version != 1 && version != 2) {
                return null;
            }
            in.readUTF(); // collector id
            return UUID.fromString(in.readUTF());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected Plan plan(Connection c, UUID target, long now) throws SQLException {
        List<String> ids = new ArrayList<>();
        int unreadable = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT id, snapshot FROM collectors"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID owner = ownerOf(rs.getBytes(2));
                if (owner == null) {
                    unreadable++;
                } else if (target == null || owner.equals(target)) {
                    ids.add(rs.getString(1));
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (unreadable > 0) {
            notes.add(unreadable + " collector snapshot(s) have an unknown format and are left untouched");
        }
        if (ids.isEmpty()) {
            return new Plan(List.of(), List.of(), notes);
        }
        String in = "id IN (" + "?,".repeat(ids.size() - 1) + "?)";
        List<String> blockers = new ArrayList<>();
        String txIn = "collector IN (" + "?,".repeat(ids.size() - 1) + "?)";
        long open = Sqlite.count(c, "SELECT COUNT(*) FROM transactions WHERE phase NOT IN " + TERMINAL + " AND " + txIn, ids.toArray());
        if (open > 0) {
            blockers.add(open + " unfinished collector transactions (restart once more so ChunkCollector can recover them, then /wipe retry)");
        }
        return new Plan(List.of(new Change("chunk collectors", "collectors", in, new ArrayList<>(ids), null)), blockers, notes);
    }
}
