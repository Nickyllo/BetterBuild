package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SchematicFormatsTest {

    // ------------------------------------------------------------------ Litematica reference

    /**
     * LitematicaBitArray.getAt / setAt, transcribed line for line from Litematica's
     * source (sakura-ryoko/litematica, LTS/26.2). Our implementation is checked
     * against this, not against itself.
     */
    static final class ReferenceBitArray {
        final long[] longArray;
        final int bitsPerEntry;
        final long maxEntryValue;

        ReferenceBitArray(int bits, long size) {
            bitsPerEntry = bits;
            maxEntryValue = (1L << bits) - 1L;
            longArray = new long[(int) (roundUp(size * bits, 64L) / 64L)];
        }

        ReferenceBitArray(int bits, long[] longs) {
            bitsPerEntry = bits;
            maxEntryValue = (1L << bits) - 1L;
            longArray = longs;
        }

        void setAt(long index, int value) {
            long startOffset = index * (long) this.bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);
            this.longArray[startArrIndex] = this.longArray[startArrIndex] & ~(this.maxEntryValue << startBitOffset) | ((long) value & this.maxEntryValue) << startBitOffset;
            if (startArrIndex != endArrIndex) {
                int endOffset = 64 - startBitOffset;
                int j1 = this.bitsPerEntry - endOffset;
                this.longArray[endArrIndex] = this.longArray[endArrIndex] >>> j1 << j1 | ((long) value & this.maxEntryValue) >> endOffset;
            }
        }

        int getAt(long index) {
            long startOffset = index * (long) this.bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);
            if (startArrIndex == endArrIndex) {
                return (int) (this.longArray[startArrIndex] >>> startBitOffset & this.maxEntryValue);
            } else {
                int endOffset = 64 - startBitOffset;
                return (int) ((this.longArray[startArrIndex] >>> startBitOffset | this.longArray[endArrIndex] << endOffset) & this.maxEntryValue);
            }
        }

        static long roundUp(long value, long interval) {
            if (interval == 0L) return 0L;
            if (value == 0L) return interval;
            if (value < 0L) interval *= -1L;
            long i = value % interval;
            return i == 0L ? value : value + interval - i;
        }
    }

    @Test
    void bitPackingMatchesLitematicaExactlyForEveryWidth() {
        Random random = new Random(42);
        for (int bits = 2; bits <= 20; bits++) {
            int n = 1000;
            int max = (1 << bits) - 1;
            int[] values = new int[n];
            for (int i = 0; i < n; i++) values[i] = random.nextInt(max + 1);

            // Written by Litematica's code, read by ours.
            ReferenceBitArray ref = new ReferenceBitArray(bits, n);
            for (int i = 0; i < n; i++) ref.setAt(i, values[i]);
            for (int i = 0; i < n; i++) {
                assertEquals(values[i], LitematicaFormat.get(ref.longArray, bits, i),
                        "our reader disagrees with Litematica at bits=" + bits + " index=" + i);
            }

            // Written by ours, read by Litematica's code.
            long[] ours = new long[(int) ((n * (long) bits + 63) / 64)];
            for (int i = 0; i < n; i++) LitematicaFormat.set(ours, bits, i, values[i]);
            ReferenceBitArray back = new ReferenceBitArray(bits, ours);
            for (int i = 0; i < n; i++) {
                assertEquals(values[i], back.getAt(i),
                        "Litematica would misread our file at bits=" + bits + " index=" + i);
            }
            assertArrayEquals(ref.longArray, ours, "packed longs differ at bits=" + bits);
        }
    }

    @Test
    void bitsPerEntryFollowsLitematicasFormula() {
        assertEquals(2, LitematicaFormat.bitsFor(1));
        assertEquals(2, LitematicaFormat.bitsFor(4));
        assertEquals(3, LitematicaFormat.bitsFor(5));
        assertEquals(4, LitematicaFormat.bitsFor(16));
        assertEquals(5, LitematicaFormat.bitsFor(17));
    }

    private static Schematic sample() {
        Schematic.Builder b = Schematic.builder("prueba", 5, 4, 3);
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 3; z++) b.set(x, 0, z, BlockRef.of("minecraft:cobblestone"));
        b.set(1, 1, 1, BlockRef.parse("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"));
        b.set(4, 3, 2, BlockRef.of("minecraft:lantern").with("hanging", "true"));
        // Enough distinct states to force entries across long boundaries.
        for (int i = 0; i < 20; i++) {
            b.set(i % 5, 2, i % 3, BlockRef.of("minecraft:oak_fence").with("north", i % 2 == 0 ? "true" : "false")
                    .with("east", Integer.toString(i)));
        }
        return b.build();
    }

    private static void assertSameBlocks(Schematic a, Schematic b) {
        assertEquals(a.sizeX(), b.sizeX());
        assertEquals(a.sizeY(), b.sizeY());
        assertEquals(a.sizeZ(), b.sizeZ());
        for (int y = 0; y < a.sizeY(); y++)
            for (int z = 0; z < a.sizeZ(); z++)
                for (int x = 0; x < a.sizeX(); x++)
                    assertEquals(a.at(x, y, z), b.at(x, y, z), "block at " + x + "," + y + "," + z);
    }

    @Test
    void litematicSurvivesARoundTripThroughBytes() throws IOException {
        Schematic original = sample();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Nbt.write(bytes, "", LitematicaFormat.write(original, "Nickyllo", "", 4671, 0L));

        Schematic back = SchematicFiles.read(new ByteArrayInputStream(bytes.toByteArray()), "prueba.litematic");
        assertSameBlocks(original, back);
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]",
                back.at(1, 1, 1).stateString(), "block states must survive, not just ids");
        assertEquals("Nickyllo", back.author());
    }

    @Test
    void litematicWritesWhatLitematicaReads() throws IOException {
        Map<String, Object> root = LitematicaFormat.write(sample(), "a", "d", 4671, 123L);
        assertEquals(7, root.get("Version"));
        assertEquals(4671, root.get("MinecraftDataVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> region = (Map<String, Object>) ((Map<String, Object>) root.get("Regions")).get("prueba");
        Nbt.NbtList palette = (Nbt.NbtList) region.get("BlockStatePalette");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) palette.get(0);
        assertEquals("minecraft:air", first.get("Name"), "Litematica expects air at palette index 0");
        assertTrue(region.get("Size") instanceof Map, "Size is an {x,y,z} compound");
    }

    /** A region stored with negative size along an axis, as Litematica does for boxes drawn backwards. */
    @Test
    void negativeRegionSizesAreAnchoredAtTheMinimumCorner() throws IOException {
        List<Object> palette = List.of(entry("minecraft:air"), entry("minecraft:stone"));
        int bits = LitematicaFormat.bitsFor(2);
        long[] longs = new long[1];
        // Size (-2,1,1): two blocks along x. Index 0 is the minimum corner.
        LitematicaFormat.set(longs, bits, 0, 1);

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", Nbt.xyz(10, 0, 0));
        region.put("Size", Nbt.xyz(-2, 1, 1));
        region.put("BlockStatePalette", Nbt.NbtList.of(Nbt.COMPOUND, palette));
        region.put("BlockStates", longs);
        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("r", region);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Regions", regions);

        Schematic s = LitematicaFormat.read(root, "neg");
        assertEquals(2, s.sizeX());
        assertEquals("minecraft:stone", s.at(0, 0, 0).id());
        assertTrue(s.at(1, 0, 0).isAir());
    }

    @Test
    void severalRegionsShareOneFrame() throws IOException {
        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("a", oneBlockRegion(0, 0, 0, "minecraft:stone"));
        regions.put("b", oneBlockRegion(4, 2, 0, "minecraft:oak_log"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Regions", regions);

        Schematic s = LitematicaFormat.read(root, "multi");
        assertEquals(5, s.sizeX());
        assertEquals(3, s.sizeY());
        assertEquals("minecraft:stone", s.at(0, 0, 0).id());
        assertEquals("minecraft:oak_log", s.at(4, 2, 0).id());
    }

    private static Map<String, Object> oneBlockRegion(int x, int y, int z, String id) {
        long[] longs = new long[1];
        LitematicaFormat.set(longs, 2, 0, 1);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Position", Nbt.xyz(x, y, z));
        r.put("Size", Nbt.xyz(1, 1, 1));
        r.put("BlockStatePalette", Nbt.NbtList.of(Nbt.COMPOUND, List.of(entry("minecraft:air"), entry(id))));
        r.put("BlockStates", longs);
        return r;
    }

    private static Map<String, Object> entry(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", id);
        return m;
    }

    // ------------------------------------------------------------------ Sponge (WorldEdit)

    private static byte[] varints(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    /** 200 distinct states, so indices above 127 need two-byte varints. */
    private static Map<String, Object> spongeBody(int w, int h, int l, int[][] expected) {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        for (int i = 1; i < 200; i++) palette.put("minecraft:block_" + i, i);

        int[] data = new int[w * h * l];
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) {
                    int v = (x * 31 + y * 7 + z * 13) % 200;
                    data[x + z * w + y * w * l] = v;
                    expected[0][x + z * w + y * w * l] = v;
                }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Width", (short) w);
        body.put("Height", (short) h);
        body.put("Length", (short) l);
        body.put("__palette", palette);
        body.put("__data", varints(data));
        return body;
    }

    @Test
    void readsSpongeVersion2AsWorldEditWritesIt() throws IOException {
        int w = 7, h = 3, l = 5;
        int[][] expected = {new int[w * h * l]};
        Map<String, Object> body = spongeBody(w, h, l, expected);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("Width", body.get("Width"));
        root.put("Height", body.get("Height"));
        root.put("Length", body.get("Length"));
        root.put("Palette", body.get("__palette"));
        root.put("BlockData", body.get("__data"));

        checkSponge(root, "casa.schem", w, h, l, expected[0]);
    }

    @Test
    void readsSpongeVersion3() throws IOException {
        int w = 4, h = 6, l = 9;
        int[][] expected = {new int[w * h * l]};
        Map<String, Object> body = spongeBody(w, h, l, expected);
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", body.get("__palette"));
        blocks.put("Data", body.get("__data"));
        Map<String, Object> schem = new LinkedHashMap<>();
        schem.put("Version", 3);
        schem.put("Width", body.get("Width"));
        schem.put("Height", body.get("Height"));
        schem.put("Length", body.get("Length"));
        schem.put("Blocks", blocks);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", schem);

        checkSponge(root, "torre.schem", w, h, l, expected[0]);
    }

    private static void checkSponge(Map<String, Object> root, String file, int w, int h, int l, int[] expected)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Nbt.write(bytes, "Schematic", root);
        Schematic s = SchematicFiles.read(new ByteArrayInputStream(bytes.toByteArray()), file);
        assertEquals(Schematic.Format.SPONGE, s.format());
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) {
                    int v = expected[x + z * w + y * w * l];
                    BlockRef got = s.at(x, y, z);
                    if (v == 0) assertTrue(got.isAir());
                    else assertEquals("minecraft:block_" + v, got.id(), "at " + x + "," + y + "," + z);
                }
    }

    @Test
    void parsesSpongeStateStringsIntoProperties() {
        BlockRef r = BlockRef.parse("minecraft:oak_stairs[facing=north,half=top]");
        assertEquals("minecraft:oak_stairs", r.id());
        assertEquals("north", r.property("facing"));
        assertEquals("top", r.property("half"));
        assertEquals("minecraft:oak_stairs[facing=north,half=top]", r.stateString());
    }

    // ------------------------------------------------------------------ vanilla structures

    @Test
    void readsVanillaStructureFiles() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("size", Nbt.NbtList.of(Nbt.INT, List.of(3, 2, 2)));
        Map<String, Object> door = entry("minecraft:oak_door");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("half", "upper");
        props.put("facing", "south");
        door.put("Properties", props);
        root.put("palette", Nbt.NbtList.of(Nbt.COMPOUND, List.of(entry("minecraft:stone"), door)));
        root.put("blocks", Nbt.NbtList.of(Nbt.COMPOUND, List.of(
                block(0, 0, 0, 0), block(2, 1, 1, 1))));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Nbt.write(bytes, "", root);
        Schematic s = SchematicFiles.read(new ByteArrayInputStream(bytes.toByteArray()), "pozo.nbt");
        assertEquals(Schematic.Format.STRUCTURE, s.format());
        assertEquals("minecraft:stone", s.at(0, 0, 0).id());
        assertEquals("upper", s.at(2, 1, 1).property("half"));
        assertEquals(2, s.blockCount());
    }

    private static Map<String, Object> block(int x, int y, int z, int state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pos", Nbt.NbtList.of(Nbt.INT, List.of(x, y, z)));
        m.put("state", state);
        return m;
    }

    // ------------------------------------------------------------------ hostile input

    @Test
    void refusesFilesThatAreNotSchematics() {
        assertThrows(IOException.class,
                () -> SchematicFiles.read(new ByteArrayInputStream("hola".getBytes()), "x.litematic"));
    }

    @Test
    void refusesAbsurdArrayLengthsInsteadOfRunningOutOfMemory() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(Nbt.COMPOUND);
        out.writeUTF("");
        out.writeByte(Nbt.LONG_ARRAY);
        out.writeUTF("BlockStates");
        out.writeInt(Integer.MAX_VALUE);
        IOException ex = assertThrows(IOException.class,
                () -> Nbt.read(new ByteArrayInputStream(raw.toByteArray())));
        assertTrue(ex.getMessage().contains("out of range"), ex.getMessage());
    }

    @Test
    void refusesSchematicsTooBigToHold() {
        assertThrows(IllegalArgumentException.class, () -> Schematic.builder("mapa", 4096, 256, 4096));
    }

    @Test
    void explainsOldMcEditFilesInsteadOfMisreadingThem() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Blocks", new byte[1]);
        root.put("Materials", "Alpha");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Nbt.write(bytes, "Schematic", root);
        IOException ex = assertThrows(IOException.class,
                () -> SchematicFiles.read(new ByteArrayInputStream(bytes.toByteArray()), "vieja.schematic"));
        assertTrue(ex.getMessage().contains("1.13"), ex.getMessage());
    }
}
