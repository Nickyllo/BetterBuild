package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Litematica's {@code .litematic} format, read and written.
 *
 * <p>Checked against Litematica's own source (sakura-ryoko/litematica, branch
 * LTS/26.2): {@code LitematicaSchematic}, {@code LitematicaBlockStateContainer} and
 * {@code LitematicaBitArray}. The parts that are easy to get wrong:
 * <ul>
 *   <li>Block states are bit-packed <i>across</i> long boundaries — an entry may start
 *       in one long and end in the next.</li>
 *   <li>Bits per entry is {@code max(2, ceil(log2(paletteSize)))}.</li>
 *   <li>Index order is {@code y * (sizeX * sizeZ) + z * sizeX + x}.</li>
 *   <li>A region's size may be negative on any axis; the stored blocks start at the
 *       region's minimum corner regardless.</li>
 * </ul>
 */
public final class LitematicaFormat {

    /** The version Litematica writes for 26.2 ({@code SCHEMATIC_VERSION}). */
    public static final int VERSION = 7;
    public static final int SUB_VERSION = 1;

    private LitematicaFormat() {
    }

    public static Schematic read(Map<String, Object> root, String name) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions.isEmpty()) {
            throw new IOException("the schematic has no regions");
        }
        Map<String, Object> meta = Nbt.compoundOrNull(root, "Metadata");
        String author = meta == null ? "" : Nbt.string(meta, "Author", "");

        // A schematic can hold several regions; lay them out in one shared frame.
        record Region(int[] min, int[] size, Map<String, Object> tag) { }
        List<Region> parsed = new ArrayList<>();
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        for (Object value : regions.values()) {
            if (!(value instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> tag = (Map<String, Object>) value;
            int[] pos = Nbt.xyz(tag, "Position");
            int[] signed = Nbt.xyz(tag, "Size");
            int[] size = new int[3];
            int[] min = new int[3];
            for (int a = 0; a < 3; a++) {
                if (signed[a] == 0) throw new IOException("region with zero size");
                size[a] = Math.abs(signed[a]);
                min[a] = signed[a] < 0 ? pos[a] + signed[a] + 1 : pos[a];
                lo[a] = Math.min(lo[a], min[a]);
                hi[a] = Math.max(hi[a], min[a] + size[a] - 1);
            }
            parsed.add(new Region(min, size, tag));
        }
        if (parsed.isEmpty()) {
            throw new IOException("the schematic has no readable regions");
        }

        Schematic.Builder out = Schematic.builder(name,
                        hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1)
                .author(author)
                .format(Schematic.Format.LITEMATICA);

        for (Region r : parsed) {
            List<BlockRef> palette = readPalette(Nbt.list(r.tag(), "BlockStatePalette"));
            Object raw = r.tag().get("BlockStates");
            if (!(raw instanceof long[] longs)) {
                throw new IOException("region has no BlockStates array");
            }
            int bits = bitsFor(palette.size());
            long volume = (long) r.size()[0] * r.size()[1] * r.size()[2];
            if (longs.length < (volume * bits + 63) / 64) {
                throw new IOException("BlockStates is shorter than the region needs");
            }
            int layer = r.size()[0] * r.size()[2];
            int ox = r.min()[0] - lo[0], oy = r.min()[1] - lo[1], oz = r.min()[2] - lo[2];

            for (int y = 0; y < r.size()[1]; y++) {
                for (int z = 0; z < r.size()[2]; z++) {
                    for (int x = 0; x < r.size()[0]; x++) {
                        long index = (long) y * layer + (long) z * r.size()[0] + x;
                        int id = get(longs, bits, index);
                        if (id > 0 && id < palette.size()) {
                            out.set(ox + x, oy + y, oz + z, palette.get(id));
                        }
                    }
                }
            }
        }
        return out.build();
    }

    /**
     * Writes a single-region schematic Litematica can open, place as a hologram and
     * verify against the world.
     *
     * @param dataVersion the running game's data version. Litematica runs its data
     *                    fixers on anything older, so writing a stale value would have
     *                    it "upgrade" block states that were already current.
     */
    public static Map<String, Object> write(Schematic s, String author, String description,
                                            int dataVersion, long timeMillis) {
        List<BlockRef> palette = s.palette(); // Entry 0 is air, as Litematica expects.
        int bits = bitsFor(palette.size());
        long volume = (long) s.sizeX() * s.sizeY() * s.sizeZ();
        long[] longs = new long[(int) ((volume * bits + 63) / 64)];
        Map<BlockRef, Integer> ids = new java.util.HashMap<>();
        for (int i = 0; i < palette.size(); i++) ids.put(palette.get(i), i);

        int layer = s.sizeX() * s.sizeZ();
        for (int y = 0; y < s.sizeY(); y++) {
            for (int z = 0; z < s.sizeZ(); z++) {
                for (int x = 0; x < s.sizeX(); x++) {
                    int id = ids.get(s.at(x, y, z));
                    if (id != 0) {
                        set(longs, bits, (long) y * layer + (long) z * s.sizeX() + x, id);
                    }
                }
            }
        }

        List<Object> paletteTags = new ArrayList<>();
        for (BlockRef ref : palette) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", ref.id());
            if (!ref.properties().isEmpty()) {
                entry.put("Properties", new LinkedHashMap<String, Object>(ref.properties()));
            }
            paletteTags.add(entry);
        }

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", Nbt.xyz(0, 0, 0));
        region.put("Size", Nbt.xyz(s.sizeX(), s.sizeY(), s.sizeZ()));
        region.put("BlockStatePalette", Nbt.NbtList.of(Nbt.COMPOUND, paletteTags));
        region.put("BlockStates", longs);
        region.put("TileEntities", Nbt.NbtList.empty(Nbt.COMPOUND));
        region.put("Entities", Nbt.NbtList.empty(Nbt.COMPOUND));
        region.put("PendingBlockTicks", Nbt.NbtList.empty(Nbt.COMPOUND));
        region.put("PendingFluidTicks", Nbt.NbtList.empty(Nbt.COMPOUND));

        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put(s.name(), region);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Name", s.name());
        meta.put("Author", author);
        meta.put("Description", description);
        meta.put("RegionCount", 1);
        meta.put("TotalVolume", (int) volume);
        meta.put("TotalBlocks", (int) s.blockCount());
        meta.put("TimeCreated", timeMillis);
        meta.put("TimeModified", timeMillis);
        meta.put("EnclosingSize", Nbt.xyz(s.sizeX(), s.sizeY(), s.sizeZ()));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("MinecraftDataVersion", dataVersion);
        root.put("Version", VERSION);
        root.put("SubVersion", SUB_VERSION);
        root.put("Metadata", meta);
        root.put("Regions", regions);
        return root;
    }

    static List<BlockRef> readPalette(Nbt.NbtList list) throws IOException {
        List<BlockRef> palette = new ArrayList<>(list.size());
        for (Object o : list.items()) {
            if (!(o instanceof Map)) throw new IOException("palette entry is not a compound");
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) o;
            String id = Nbt.string(entry, "Name", null);
            if (id == null) throw new IOException("palette entry without a Name");
            Map<String, String> props = new TreeMap<>();
            Map<String, Object> p = Nbt.compoundOrNull(entry, "Properties");
            if (p != null) {
                for (var e : p.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
            }
            palette.add(new BlockRef(id, List.of(), props));
        }
        return palette;
    }

    /** {@code max(2, ceil(log2(n)))}, exactly as LitematicaBlockStateContainer computes it. */
    static int bitsFor(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** Reads entry {@code index}; entries may straddle two longs. */
    static int get(long[] longs, int bits, long index) {
        long bitPos = index * bits;
        int word = (int) (bitPos >>> 6);
        int offset = (int) (bitPos & 63);
        long value = longs[word] >>> offset;
        int available = 64 - offset;
        if (available < bits) {
            value |= longs[word + 1] << available;
        }
        return (int) (value & ((1L << bits) - 1));
    }

    static void set(long[] longs, int bits, long index, int value) {
        long mask = (1L << bits) - 1;
        long v = value & mask;
        long bitPos = index * bits;
        int word = (int) (bitPos >>> 6);
        int offset = (int) (bitPos & 63);
        longs[word] = (longs[word] & ~(mask << offset)) | (v << offset);
        int available = 64 - offset;
        if (available < bits) {
            int spill = bits - available;
            longs[word + 1] = (longs[word + 1] & ~((1L << spill) - 1)) | (v >>> available);
        }
    }
}
