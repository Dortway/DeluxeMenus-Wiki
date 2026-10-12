package dev.exo.cleanwipe.integrations.vanilla;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal, lossless reader/writer for Minecraft's binary NBT (the gzip-compressed player files in
 * {@code world/players/data}). Every tag keeps its exact type so untouched data is written back byte-for-byte
 * equivalent. Strings use Java's modified UTF-8, the same encoding Minecraft uses.
 */
public final class Nbt {

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    private static final int MAX_DEPTH = 512;

    /** A typed tag. For LIST, {@code value} is a {@link ListTag}; for COMPOUND a {@code Map<String, Tag>}. */
    public record Tag(byte type, Object value) {
        @SuppressWarnings("unchecked")
        public Map<String, Tag> compound() {
            if (type != COMPOUND) {
                throw new IllegalStateException("not a compound");
            }
            return (Map<String, Tag>) value;
        }

        public ListTag list() {
            if (type != LIST) {
                throw new IllegalStateException("not a list");
            }
            return (ListTag) value;
        }
    }

    public record ListTag(byte elementType, List<Tag> items) {
    }

    /** Root of a file: a named compound. */
    public record Root(String name, Map<String, Tag> compound) {
    }

    private Nbt() {
    }

    public static Tag emptyList() {
        return new Tag(LIST, new ListTag(END, new ArrayList<>()));
    }

    public static Tag intTag(int v) {
        return new Tag(INT, v);
    }

    public static Tag floatTag(float v) {
        return new Tag(FLOAT, v);
    }

    public static Root readGzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return read(new DataInputStream(in));
        }
    }

    public static byte[] writeGzip(Root root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            write(out, root);
        }
        return bytes.toByteArray();
    }

    public static Root read(DataInputStream in) throws IOException {
        byte type = in.readByte();
        if (type != COMPOUND) {
            throw new IOException("root tag is not a compound (type " + type + ")");
        }
        String name = in.readUTF();
        return new Root(name, readCompound(in, 0));
    }

    public static void write(DataOutputStream out, Root root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(root.name());
        writeCompound(out, root.compound());
    }

    private static Map<String, Tag> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("nbt nested too deeply");
        }
        Map<String, Tag> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == END) {
                return map;
            }
            String key = in.readUTF();
            map.put(key, new Tag(type, readPayload(in, type, depth + 1)));
        }
    }

    private static Object readPayload(DataInputStream in, byte type, int depth) throws IOException {
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: {
                byte[] b = new byte[checkedLength(in.readInt())];
                in.readFully(b);
                return b;
            }
            case STRING: return in.readUTF();
            case LIST: {
                byte element = in.readByte();
                int n = checkedLength(in.readInt());
                List<Tag> items = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) {
                    items.add(new Tag(element, readPayload(in, element, depth + 1)));
                }
                return new ListTag(element, items);
            }
            case COMPOUND: return readCompound(in, depth);
            case INT_ARRAY: {
                int[] a = new int[checkedLength(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case LONG_ARRAY: {
                long[] a = new long[checkedLength(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("unknown nbt tag type " + type);
        }
    }

    private static int checkedLength(int n) throws IOException {
        if (n < 0 || n > 64 * 1024 * 1024) {
            throw new IOException("bad nbt length " + n);
        }
        return n;
    }

    private static void writeCompound(DataOutputStream out, Map<String, Tag> map) throws IOException {
        for (Map.Entry<String, Tag> e : map.entrySet()) {
            out.writeByte(e.getValue().type());
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(END);
    }

    private static void writePayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type()) {
            case BYTE -> out.writeByte((Byte) tag.value());
            case SHORT -> out.writeShort((Short) tag.value());
            case INT -> out.writeInt((Integer) tag.value());
            case LONG -> out.writeLong((Long) tag.value());
            case FLOAT -> out.writeFloat((Float) tag.value());
            case DOUBLE -> out.writeDouble((Double) tag.value());
            case BYTE_ARRAY -> {
                byte[] b = (byte[]) tag.value();
                out.writeInt(b.length);
                out.write(b);
            }
            case STRING -> out.writeUTF((String) tag.value());
            case LIST -> {
                ListTag l = (ListTag) tag.value();
                out.writeByte(l.items().isEmpty() ? END : l.elementType());
                out.writeInt(l.items().size());
                for (Tag t : l.items()) {
                    writePayload(out, t);
                }
            }
            case COMPOUND -> writeCompound(out, tag.compound());
            case INT_ARRAY -> {
                int[] a = (int[]) tag.value();
                out.writeInt(a.length);
                for (int v : a) {
                    out.writeInt(v);
                }
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) tag.value();
                out.writeInt(a.length);
                for (long v : a) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("unknown nbt tag type " + tag.type());
        }
    }
}
