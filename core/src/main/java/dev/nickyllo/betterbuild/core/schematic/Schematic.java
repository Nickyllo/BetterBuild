package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * A building someone made, loaded from a schematic file.
 *
 * <p>Stored as a palette plus one palette index per position, the same way the file
 * formats do it, so a large schematic costs four bytes a block rather than a map
 * entry and an object each. Palette entry 0 is always air.
 *
 * <p>Positions are relative: (0,0,0) is the minimum corner.
 */
public final class Schematic {

    /** Beyond this the file is a map, not a building, and we will not hold it in memory. */
    public static final long MAX_VOLUME = 8L * 1024 * 1024;

    public enum Format { LITEMATICA, SPONGE, STRUCTURE }

    private final String name;
    private final String author;
    private final Format format;
    private final int sizeX, sizeY, sizeZ;
    private final List<BlockRef> palette;
    private final int[] data;

    private Schematic(String name, String author, Format format, int sizeX, int sizeY, int sizeZ,
                      List<BlockRef> palette, int[] data) {
        this.name = name;
        this.author = author;
        this.format = format;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = List.copyOf(palette);
        this.data = data;
    }

    public String name() { return name; }
    public String author() { return author; }
    public Format format() { return format; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public List<BlockRef> palette() { return palette; }

    public Box box() {
        return Box.atOrigin(sizeX, sizeY, sizeZ);
    }

    public BlockRef at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return BlockRef.AIR;
        }
        return palette.get(data[index(x, y, z)]);
    }

    public BlockRef at(Vec3i p) {
        return at(p.x(), p.y(), p.z());
    }

    /** Every non-air block, y-ascending — the order a builder would lay them. */
    public void forEachBlock(BiConsumer<Vec3i, BlockRef> action) {
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockRef ref = palette.get(data[index(x, y, z)]);
                    if (!ref.isAir()) {
                        action.accept(new Vec3i(x, y, z), ref);
                    }
                }
            }
        }
    }

    public long blockCount() {
        long n = 0;
        for (int i : data) {
            if (!palette.get(i).isAir()) n++;
        }
        return n;
    }

    /** Blocks by id, most used first is up to the caller; sorted by id for stable output. */
    public Map<String, Integer> blockCounts() {
        int[] perEntry = new int[palette.size()];
        for (int i : data) perEntry[i]++;
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < perEntry.length; i++) {
            BlockRef ref = palette.get(i);
            if (perEntry[i] > 0 && !ref.isAir()) {
                counts.merge(ref.id(), perEntry[i], Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Turned clockwise (looking down) by {@code quarterTurns} × 90°, block states
     * included, and re-anchored so the minimum corner is at the origin again.
     */
    public Schematic rotated(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0) {
            return this;
        }
        int nx = (q % 2 == 0) ? sizeX : sizeZ;
        int nz = (q % 2 == 0) ? sizeZ : sizeX;

        List<BlockRef> rotatedPalette = new ArrayList<>(palette.size());
        for (BlockRef ref : palette) {
            rotatedPalette.add(StateRotation.rotate(ref, q));
        }

        int[] out = new int[data.length];
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    // One quarter turn clockwise maps north to east: (x, z) -> (-z, x),
                    // shifted back so the result starts at zero.
                    int rx = x, rz = z, w = sizeX, d = sizeZ;
                    for (int i = 0; i < q; i++) {
                        int tx = d - 1 - rz;
                        rz = rx;
                        rx = tx;
                        int tw = w;
                        w = d;
                        d = tw;
                    }
                    out[(y * nz + rz) * nx + rx] = data[index(x, y, z)];
                }
            }
        }
        return new Schematic(name, author, format, nx, sizeY, nz, rotatedPalette, out);
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    // ------------------------------------------------------------------ building one

    public static Builder builder(String name, int sizeX, int sizeY, int sizeZ) {
        return new Builder(name, sizeX, sizeY, sizeZ);
    }

    /** Assembles a schematic block by block, deduplicating states into the palette. */
    public static final class Builder {
        private final String name;
        private final int sizeX, sizeY, sizeZ;
        private final List<BlockRef> palette = new ArrayList<>();
        private final Map<BlockRef, Integer> ids = new HashMap<>();
        private final int[] data;
        private String author = "";
        private Format format = Format.LITEMATICA;

        private Builder(String name, int sizeX, int sizeY, int sizeZ) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("schematic size must be positive: "
                        + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            long volume = (long) sizeX * sizeY * sizeZ;
            if (volume > MAX_VOLUME) {
                throw new IllegalArgumentException("schematic too large: " + volume
                        + " blocks (limit " + MAX_VOLUME + ")");
            }
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.data = new int[(int) volume];
            palette.add(BlockRef.AIR);
            ids.put(BlockRef.AIR, 0);
        }

        public Builder author(String author) {
            this.author = author == null ? "" : author;
            return this;
        }

        public Builder format(Format format) {
            this.format = format;
            return this;
        }

        public Builder set(int x, int y, int z, BlockRef ref) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                return this; // Out-of-bounds entries in a file are dropped, not fatal.
            }
            int id = ref.isAir() ? 0 : ids.computeIfAbsent(ref, r -> {
                palette.add(r);
                return palette.size() - 1;
            });
            data[(y * sizeZ + z) * sizeX + x] = id;
            return this;
        }

        public Schematic build() {
            return new Schematic(name, author, format, sizeX, sizeY, sizeZ, palette, data);
        }
    }
}
