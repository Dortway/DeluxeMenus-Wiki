package net.exoeconomy.sell;

import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.util.*;

/**
 * Serializes escrowed items with Paper's ItemStack#serializeAsBytes, which preserves every data
 * component (custom names, enchantments, plugin data), so recovered items keep their identity.
 */
public final class ItemCodec {
    private ItemCodec() {}

    public static byte[] encode(Map<Integer, ItemStack> slots) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); // format version
            out.writeInt(slots.size());
            for (Map.Entry<Integer, ItemStack> e : slots.entrySet()) {
                byte[] item = e.getValue().serializeAsBytes();
                out.writeInt(e.getKey());
                out.writeInt(item.length);
                out.write(item);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static Map<Integer, ItemStack> decode(byte[] data) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != 1) throw new IOException("Unknown escrow format " + version);
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int slot = in.readInt();
                byte[] item = new byte[in.readInt()];
                in.readFully(item);
                out.put(slot, ItemStack.deserializeBytes(item));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public static String summary(Collection<ItemStack> items) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack i : items) counts.merge(i.getType().name() + (i.hasItemMeta() ? "*" : ""), i.getAmount(), Integer::sum);
        StringJoiner j = new StringJoiner(", ");
        counts.forEach((k, v) -> j.add(v + "x " + k));
        String s = j.toString();
        return s.length() > 1000 ? s.substring(0, 997) + "..." : s;
    }
}
