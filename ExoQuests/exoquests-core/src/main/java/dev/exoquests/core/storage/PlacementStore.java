package dev.exoquests.core.storage;

import dev.exoquests.core.util.BlockPos;
import dev.exoquests.core.util.MaterialFamilies;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Persistent placement tracking used to stop place-and-break farming.
 *
 * <p>Operations are queued in call order and executed in batches, each batch in one transaction on the
 * database thread. Because the queue is FIFO, a break that is queued after a place always observes that
 * place. Futures complete only after the batch has committed.</p>
 *
 * <p>The table holds one row per currently existing tracked block that a player placed; rows are removed
 * when the block is broken, exploded, burnt, replaced or grown over, so its size is bounded by the number
 * of such blocks in the world rather than by the number of actions.</p>
 */
public final class PlacementStore {

    private static final int MAX_BATCH = 4_096;

    /** Block to test, with its current material. */
    public record Target(BlockPos pos, String material) {
    }

    /** Piston movement of a block. */
    public record Move(BlockPos from, BlockPos to) {
    }

    private interface Op {
        void run(Connection c) throws SQLException;

        void complete();

        void fail(Throwable t);
    }

    private final Database db;
    private final LongSupplier clock;
    private final ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public PlacementStore(Database db, LongSupplier clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Records a player-placed block (or planted sapling when {@code kind == SAPLING}). */
    public CompletableFuture<Void> place(BlockPos pos, String material, PlacementMark.Kind kind, UUID owner) {
        long now = clock.getAsLong();
        return enqueue(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO placed_blocks(world, x, y, z, material, kind, owner, placed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(ps, pos, 1);
                ps.setString(5, material);
                ps.setInt(6, kind.ordinal());
                ps.setString(7, owner == null ? null : owner.toString());
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Removes the marks at the targets and returns how many targets were natural: either unmarked, or
     * marked for a different material (stale mark).
     */
    public CompletableFuture<Integer> consumeNatural(List<Target> targets) {
        List<Target> copy = List.copyOf(targets);
        return enqueue(c -> {
            int natural = 0;
            for (Target t : copy) {
                Optional<PlacementMark> mark = select(c, t.pos());
                if (mark.isEmpty() || !MaterialFamilies.sameFamily(mark.get().material(), t.material())) {
                    natural++;
                }
                if (mark.isPresent()) {
                    delete(c, t.pos());
                }
            }
            return natural;
        });
    }

    /** Removes and returns sapling marks at the given positions (tree growth). */
    public CompletableFuture<List<PlacementMark>> consumeSaplings(List<BlockPos> positions) {
        List<BlockPos> copy = List.copyOf(positions);
        return enqueue(c -> {
            List<PlacementMark> found = new ArrayList<>();
            for (BlockPos p : copy) {
                Optional<PlacementMark> mark = select(c, p);
                if (mark.isPresent() && mark.get().kind() == PlacementMark.Kind.SAPLING) {
                    found.add(mark.get());
                    delete(c, p);
                }
            }
            return found;
        });
    }

    /** Deletes marks at positions whose block was destroyed or replaced without a player break. */
    public CompletableFuture<Void> clear(List<BlockPos> positions) {
        List<BlockPos> copy = List.copyOf(positions);
        return enqueue(c -> {
            for (BlockPos p : copy) {
                delete(c, p);
            }
            return null;
        });
    }

    /** Moves marks along with piston-pushed blocks. All sources are read before any destination is written. */
    public CompletableFuture<Void> move(List<Move> moves) {
        List<Move> copy = List.copyOf(moves);
        return enqueue(c -> {
            List<PlacementMark> moved = new ArrayList<>();
            List<BlockPos> targets = new ArrayList<>();
            for (Move m : copy) {
                Optional<PlacementMark> mark = select(c, m.from());
                if (mark.isPresent()) {
                    moved.add(mark.get());
                    targets.add(m.to());
                }
            }
            for (Move m : copy) {
                delete(c, m.from());
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO placed_blocks(world, x, y, z, material, kind, owner, placed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < moved.size(); i++) {
                    PlacementMark mark = moved.get(i);
                    bind(ps, targets.get(i), 1);
                    ps.setString(5, mark.material());
                    // A moved sapling cannot exist (saplings are destroyed by pistons); keep the kind anyway.
                    ps.setInt(6, mark.kind().ordinal());
                    ps.setString(7, mark.owner() == null ? null : mark.owner().toString());
                    ps.setLong(8, mark.placedAt());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public CompletableFuture<Optional<PlacementMark>> get(BlockPos pos) {
        return enqueue(c -> select(c, pos));
    }

    public CompletableFuture<Long> count() {
        return enqueue(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM placed_blocks");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private <T> CompletableFuture<T> enqueue(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        queue.add(new Op() {
            private T result;

            @Override
            public void run(Connection c) throws SQLException {
                result = work.run(c);
            }

            @Override
            public void complete() {
                future.complete(result);
            }

            @Override
            public void fail(Throwable t) {
                future.completeExceptionally(t);
            }
        });
        scheduleDrain();
        return future;
    }

    private void scheduleDrain() {
        if (scheduled.compareAndSet(false, true)) {
            List<Op> batch = new ArrayList<>();
            db.submit(c -> {
                scheduled.set(false);
                Op op;
                while (batch.size() < MAX_BATCH && (op = queue.poll()) != null) {
                    batch.add(op);
                }
                for (Op o : batch) {
                    o.run(c);
                }
                return null;
            }).whenComplete((ok, error) -> {
                if (error != null && batch.isEmpty()) {
                    // The database rejected the work (closed): fail everything still queued.
                    scheduled.set(false);
                    Op pending;
                    while ((pending = queue.poll()) != null) {
                        pending.fail(error);
                    }
                    return;
                }
                for (Op o : batch) {
                    if (error == null) {
                        o.complete();
                    } else {
                        o.fail(error);
                    }
                }
                if (!queue.isEmpty()) {
                    scheduleDrain();
                }
            });
        }
    }

    private static void bind(PreparedStatement ps, BlockPos pos, int start) throws SQLException {
        ps.setString(start, pos.world());
        ps.setInt(start + 1, pos.x());
        ps.setInt(start + 2, pos.y());
        ps.setInt(start + 3, pos.z());
    }

    private static Optional<PlacementMark> select(Connection c, BlockPos pos) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT material, kind, owner, placed_at FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            bind(ps, pos, 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String owner = rs.getString(3);
                int kind = rs.getInt(2);
                PlacementMark.Kind k = kind == 1 ? PlacementMark.Kind.SAPLING : PlacementMark.Kind.PLACED;
                return Optional.of(new PlacementMark(pos, rs.getString(1), k,
                        owner == null ? null : UUID.fromString(owner), rs.getLong(4)));
            }
        }
    }

    private static void delete(Connection c, BlockPos pos) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            bind(ps, pos, 1);
            ps.executeUpdate();
        }
    }
}
