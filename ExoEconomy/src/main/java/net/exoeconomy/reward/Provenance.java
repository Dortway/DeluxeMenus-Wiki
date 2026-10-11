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
 * <p>Until the startup load finishes, {@link #loaded()} is false and mining rewards are withheld,
 * because an unknown block could be a placed one. Changes made before the load completes win over
 * loaded rows.
 */
public final class Provenance {
    public static final byte NATURAL = 0, PLACED = 1, GENERATED = 2, FERTILIZED = 3;

    private final Map<String, Map<Long, Map<Integer, Byte>>> worlds = new HashMap<>();
    private final Map<Key, Byte> dirty = new LinkedHashMap<>();
    private volatile boolean loaded;
    private int size;

    record Key(String world, long chunk, int pos) {}

    public boolean loaded() { return loaded; }
    public int size() { return size; }

    public byte get(Block b) { return get(b.getWorld().getUID().toString(), b.getX(), b.getY(), b.getZ()); }

    public void set(Block b, byte kind) { set(b.getWorld().getUID().toString(), b.getX(), b.getY(), b.getZ(), kind); }

    public byte get(String world, int x, int y, int z) {
        Map<Long, Map<Integer, Byte>> chunks = worlds.get(world);
        if (chunks == null) return NATURAL;
        Map<Integer, Byte> chunk = chunks.get(chunkKey(x, z));
        if (chunk == null) return NATURAL;
        return chunk.getOrDefault(pos(x, y, z), NATURAL);
    }

    public void set(String world, int x, int y, int z, byte kind) {
        long ck = chunkKey(x, z);
        int p = pos(x, y, z);
        if (put(world, ck, p, kind)) dirty.put(new Key(world, ck, p), kind);
    }

    private boolean put(String world, long ck, int p, byte kind) {
        if (kind == NATURAL) {
            Map<Long, Map<Integer, Byte>> chunks = worlds.get(world);
            if (chunks == null) return !loaded;
            Map<Integer, Byte> chunk = chunks.get(ck);
            if (chunk == null) return !loaded;
            if (chunk.remove(p) == null) return !loaded;
            size--;
            if (chunk.isEmpty()) chunks.remove(ck);
            return true;
        }
        Byte previous = worlds.computeIfAbsent(world, w -> new HashMap<>()).computeIfAbsent(ck, c -> new HashMap<>()).put(p, kind);
        if (previous == null) size++;
        return previous == null || previous != kind;
    }

    /** Merges rows loaded from the database; rows changed since startup are kept as they are now. */
    public void merge(List<Database.PlacedRow> rows) {
        for (Database.PlacedRow row : rows) {
            if (dirty.containsKey(new Key(row.world(), row.chunk(), row.pos()))) continue;
            put(row.world(), row.chunk(), row.pos(), row.kind());
        }
        loaded = true;
    }

    public List<Database.PlacedRow> drainDirty(int max) {
        List<Database.PlacedRow> out = new ArrayList<>(Math.min(max, dirty.size()));
        Iterator<Map.Entry<Key, Byte>> it = dirty.entrySet().iterator();
        while (it.hasNext() && out.size() < max) {
            Map.Entry<Key, Byte> e = it.next();
            out.add(new Database.PlacedRow(e.getKey().world(), e.getKey().chunk(), e.getKey().pos(), e.getValue()));
            it.remove();
        }
        return out;
    }

    /** Puts rows back after a failed write so they are retried. */
    public void requeue(List<Database.PlacedRow> rows) {
        for (Database.PlacedRow row : rows) dirty.putIfAbsent(new Key(row.world(), row.chunk(), row.pos()), row.kind());
    }

    public int pending() { return dirty.size(); }

    static long chunkKey(int x, int z) { return ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL); }

    /** 4 bits x, 4 bits z, 12 bits y (+2048), enough for any world height. */
    static int pos(int x, int y, int z) { return ((y + 2048) & 0xFFF) << 8 | (x & 15) << 4 | (z & 15); }
}
