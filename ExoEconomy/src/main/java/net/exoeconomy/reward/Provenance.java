package net.exoeconomy.reward;

import net.exoeconomy.storage.Database;
import org.bukkit.block.Block;

import java.util.*;

/**
 * Where blocks came from, for blocks that are not naturally generated terrain:
 * PLACED (by a player, or moved/fallen from a placed block), GENERATED (formed by liquids: cobblestone,
 * stone, basalt, obsidian generators, plus snow/ice) and FERTILIZED (crops grown with bone meal).
 * Absent means natural. Indexed by world and chunk; persisted in batches by {@link RewardService}.
 *
 * <p>Each mark remembers the block type it was recorded for. When the block at that position is
 * now a different type, something replaced it without a Bukkit event (typically a regenerating
 * mine or cube plugin setting blocks directly), so the mark no longer describes the block and it
 * is treated as natural ({@code forget-when-block-changes}, on by default).
 *
 * <p>Until the startup load finishes, {@link #loaded()} is false and mining rewards are withheld,
 * because an unknown block could be a placed one. Changes made before the load completes win over
 * loaded rows.
 */
public final class Provenance {
    public static final byte NATURAL = 0, PLACED = 1, GENERATED = 2, FERTILIZED = 3;

    private record Mark(byte kind, String type) {}

    private final Map<String, Map<Long, Map<Integer, Mark>>> worlds = new HashMap<>();
    private final Map<Key, Mark> dirty = new LinkedHashMap<>();
    private volatile boolean loaded;
    private volatile boolean forgetOnTypeChange = true;
    private int size;

    record Key(String world, long chunk, int pos) {}

    public boolean loaded() { return loaded; }
    public int size() { return size; }
    public void forgetOnTypeChange(boolean value) { this.forgetOnTypeChange = value; }

    /** Origin of the block as it is now. */
    public byte get(Block b) {
        return kindOf(b.getWorld().getUID().toString(), b.getX(), b.getY(), b.getZ(), b.getType().name());
    }

    /** Origin of the block at a position that currently has {@code currentType}. */
    public byte kindOf(String world, int x, int y, int z, String currentType) {
        Mark mark = mark(world, x, y, z);
        if (mark == null) return NATURAL;
        if (forgetOnTypeChange && mark.type() != null && !mark.type().equals(currentType)) {
            set(world, x, y, z, NATURAL, null); // stale: the block was replaced without an event
            return NATURAL;
        }
        return mark.kind();
    }

    /** Marks the block with its current type. */
    public void set(Block b, byte kind) { set(b, kind, b.getType().name()); }

    /** Marks the block with the type it is about to have (for events fired before the change). */
    public void set(Block b, byte kind, String type) {
        set(b.getWorld().getUID().toString(), b.getX(), b.getY(), b.getZ(), kind, type);
    }

    public byte get(String world, int x, int y, int z) {
        Mark mark = mark(world, x, y, z);
        return mark == null ? NATURAL : mark.kind();
    }

    private Mark mark(String world, int x, int y, int z) {
        Map<Long, Map<Integer, Mark>> chunks = worlds.get(world);
        if (chunks == null) return null;
        Map<Integer, Mark> chunk = chunks.get(chunkKey(x, z));
        return chunk == null ? null : chunk.get(pos(x, y, z));
    }

    public void set(String world, int x, int y, int z, byte kind, String type) {
        long ck = chunkKey(x, z);
        int p = pos(x, y, z);
        Mark mark = kind == NATURAL ? null : new Mark(kind, type);
        if (put(world, ck, p, mark)) dirty.put(new Key(world, ck, p), mark == null ? new Mark(NATURAL, null) : mark);
    }

    private boolean put(String world, long ck, int p, Mark mark) {
        if (mark == null) {
            Map<Long, Map<Integer, Mark>> chunks = worlds.get(world);
            if (chunks == null) return !loaded;
            Map<Integer, Mark> chunk = chunks.get(ck);
            if (chunk == null) return !loaded;
            if (chunk.remove(p) == null) return !loaded;
            size--;
            if (chunk.isEmpty()) chunks.remove(ck);
            return true;
        }
        Mark previous = worlds.computeIfAbsent(world, w -> new HashMap<>()).computeIfAbsent(ck, c -> new HashMap<>()).put(p, mark);
        if (previous == null) size++;
        return !mark.equals(previous);
    }

    /** Merges rows loaded from the database; rows changed since startup are kept as they are now. */
    public void merge(List<Database.PlacedRow> rows) {
        for (Database.PlacedRow row : rows) {
            if (dirty.containsKey(new Key(row.world(), row.chunk(), row.pos()))) continue;
            put(row.world(), row.chunk(), row.pos(), row.kind() == NATURAL ? null : new Mark(row.kind(), row.type()));
        }
        loaded = true;
    }

    public List<Database.PlacedRow> drainDirty(int max) {
        List<Database.PlacedRow> out = new ArrayList<>(Math.min(max, dirty.size()));
        Iterator<Map.Entry<Key, Mark>> it = dirty.entrySet().iterator();
        while (it.hasNext() && out.size() < max) {
            Map.Entry<Key, Mark> e = it.next();
            out.add(new Database.PlacedRow(e.getKey().world(), e.getKey().chunk(), e.getKey().pos(), e.getValue().kind(), e.getValue().type()));
            it.remove();
        }
        return out;
    }

    /** Puts rows back after a failed write so they are retried. */
    public void requeue(List<Database.PlacedRow> rows) {
        for (Database.PlacedRow row : rows) dirty.putIfAbsent(new Key(row.world(), row.chunk(), row.pos()), new Mark(row.kind(), row.type()));
    }

    public int pending() { return dirty.size(); }

    static long chunkKey(int x, int z) { return ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL); }

    /** 4 bits x, 4 bits z, 12 bits y (+2048), enough for any world height. */
    static int pos(int x, int y, int z) { return ((y + 2048) & 0xFFF) << 8 | (x & 15) << 4 | (z & 15); }
}
