package dev.nickyllo.betterbuild.core.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minecraft's NBT format, read and written without a single Minecraft class.
 *
 * <p>Every schematic format — Litematica, Sponge/WorldEdit, vanilla structures — is
 * gzipped NBT underneath, so this is the one piece all three readers share.
 *
 * <p>Tags map onto plain Java: {@code Byte, Short, Integer, Long, Float, Double,
 * byte[], String, int[], long[]}, {@link NbtList} for lists and
 * {@code Map<String, Object>} (insertion-ordered) for compounds.
 *
 * <p>Files come from the internet, so they are treated as hostile: nesting depth,
 * array lengths and string sizes are capped, and a malformed file fails with an
 * {@link IOException} instead of exhausting memory.
 */
public final class Nbt {

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5,
            DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10,
            INT_ARRAY = 11, LONG_ARRAY = 12;

    private static final int MAX_DEPTH = 512;
    /** Largest array accepted: 64M entries covers any schematic we will agree to load. */
    private static final int MAX_ARRAY = 64 * 1024 * 1024;

    private Nbt() {
    }

    /** A list tag. The element type is kept so an empty list can be written back faithfully. */
    public record NbtList(byte elementType, List<Object> items) {
        public NbtList {
            items = List.copyOf(items);
        }

        public static NbtList of(byte elementType, List<?> items) {
            return new NbtList(elementType, new ArrayList<>(items));
        }

        public static NbtList empty(byte elementType) {
            return new NbtList(elementType, List.of());
        }

        public int size() {
            return items.size();
        }

        public Object get(int i) {
            return items.get(i);
        }
    }

    /** The root compound and the name it was stored under. */
    public record Root(String name, Map<String, Object> tag) {
    }

    // ------------------------------------------------------------------ reading

    /** Reads a root compound, gzipped or not — the first two bytes decide. */
    public static Root read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        InputStream body = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(in) : in;

        DataInputStream data = new DataInputStream(body);
        byte type = data.readByte();
        if (type != COMPOUND) {
            throw new IOException("not an NBT file: root tag is type " + type + ", expected a compound");
        }
        String name = data.readUTF();
        return new Root(name, readCompound(data, 0));
    }

    private static Object readPayload(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH + " levels");
        }
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                yield a;
            }
            case STRING -> in.readUTF();
            case LIST -> {
                byte element = in.readByte();
                int n = length(in);
                if (element == END && n > 0) {
                    throw new IOException("list of END tags with " + n + " entries");
                }
                List<Object> items = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) {
                    items.add(readPayload(in, element, depth + 1));
                }
                yield new NbtList(element, items);
            }
            case COMPOUND -> readCompound(in, depth + 1);
            case INT_ARRAY -> {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                yield a;
            }
            case LONG_ARRAY -> {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                yield a;
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        };
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == END) {
                return map;
            }
            String key = in.readUTF();
            map.put(key, readPayload(in, type, depth));
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_ARRAY) {
            throw new IOException("NBT array length out of range: " + n);
        }
        return n;
    }

    // ------------------------------------------------------------------ writing

    /** Writes a gzipped root compound, the way every schematic tool expects it. */
    public static void write(OutputStream raw, String rootName, Map<String, Object> root) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(raw);
        DataOutputStream out = new DataOutputStream(gz);
        out.writeByte(COMPOUND);
        out.writeUTF(rootName);
        writeCompound(out, root);
        out.flush();
        gz.finish();
    }

    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (var e : map.entrySet()) {
            out.writeByte(typeOf(e.getValue()));
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(END);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case BYTE -> out.writeByte((Byte) v);
            case SHORT -> out.writeShort((Short) v);
            case INT -> out.writeInt((Integer) v);
            case LONG -> out.writeLong((Long) v);
            case FLOAT -> out.writeFloat((Float) v);
            case DOUBLE -> out.writeDouble((Double) v);
            case BYTE_ARRAY -> {
                byte[] a = (byte[]) v;
                out.writeInt(a.length);
                out.write(a);
            }
            case STRING -> out.writeUTF((String) v);
            case LIST -> {
                NbtList list = (NbtList) v;
                out.writeByte(list.items().isEmpty() ? list.elementType() : typeOf(list.get(0)));
                out.writeInt(list.size());
                for (Object item : list.items()) writePayload(out, item);
            }
            case COMPOUND -> writeCompound(out, (Map<String, Object>) v);
            case INT_ARRAY -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int x : a) out.writeInt(x);
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long x : a) out.writeLong(x);
            }
            default -> throw new IOException("cannot write " + v);
        }
    }

    private static byte typeOf(Object v) {
        if (v instanceof Byte) return BYTE;
        if (v instanceof Short) return SHORT;
        if (v instanceof Integer) return INT;
        if (v instanceof Long) return LONG;
        if (v instanceof Float) return FLOAT;
        if (v instanceof Double) return DOUBLE;
        if (v instanceof byte[]) return BYTE_ARRAY;
        if (v instanceof String) return STRING;
        if (v instanceof NbtList) return LIST;
        if (v instanceof Map) return COMPOUND;
        if (v instanceof int[]) return INT_ARRAY;
        if (v instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("not an NBT value: " + (v == null ? "null" : v.getClass()));
    }

    // ------------------------------------------------------------------ reading helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String key) throws IOException {
        Object v = parent.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IOException("missing compound '" + key + "'");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compoundOrNull(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    public static NbtList list(Map<String, Object> parent, String key) throws IOException {
        Object v = parent.get(key);
        if (v instanceof NbtList l) return l;
        throw new IOException("missing list '" + key + "'");
    }

    /** Any numeric tag as an int — formats disagree on short vs int for sizes. */
    public static int integer(Map<String, Object> parent, String key) throws IOException {
        Object v = parent.get(key);
        if (v instanceof Number n) return n.intValue();
        throw new IOException("missing number '" + key + "'");
    }

    public static int integerOr(Map<String, Object> parent, String key, int fallback) {
        Object v = parent.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public static String string(Map<String, Object> parent, String key, String fallback) {
        Object v = parent.get(key);
        return v instanceof String s ? s : fallback;
    }

    /** A compound of the shape {x, y, z}, as Litematica stores positions and sizes. */
    public static int[] xyz(Map<String, Object> parent, String key) throws IOException {
        Map<String, Object> c = compound(parent, key);
        return new int[]{integer(c, "x"), integer(c, "y"), integer(c, "z")};
    }

    public static Map<String, Object> xyz(int x, int y, int z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }
}
