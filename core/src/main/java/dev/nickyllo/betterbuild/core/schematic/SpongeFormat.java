package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;

import java.io.IOException;
import java.util.Map;

/**
 * The Sponge schematic format ({@code .schem}), which WorldEdit writes.
 *
 * <p>Versions 2 and 3 per the SpongePowered specification. v2 keeps everything in the
 * root compound; v3 nests it under {@code Schematic} and moves the palette and data
 * into a {@code Blocks} container. Both index blocks as
 * {@code x + z * Width + y * Width * Length}, stored as varints.
 */
public final class SpongeFormat {

    private SpongeFormat() {
    }

    public static Schematic read(Map<String, Object> root, String name) throws IOException {
        Map<String, Object> s = Nbt.compoundOrNull(root, "Schematic");
        if (s == null) {
            s = root;
        }
        int width = Nbt.integer(s, "Width") & 0xFFFF;
        int height = Nbt.integer(s, "Height") & 0xFFFF;
        int length = Nbt.integer(s, "Length") & 0xFFFF;

        Map<String, Object> palette;
        Object data;
        Map<String, Object> blocks = Nbt.compoundOrNull(s, "Blocks");
        if (blocks != null) {                       // version 3
            palette = Nbt.compound(blocks, "Palette");
            data = blocks.get("Data");
        } else {                                    // version 2
            palette = Nbt.compound(s, "Palette");
            data = s.get("BlockData");
        }
        if (!(data instanceof byte[] bytes)) {
            throw new IOException("the schematic has no block data");
        }

        BlockRef[] byId = new BlockRef[palette.size()];
        for (var e : palette.entrySet()) {
            if (!(e.getValue() instanceof Number n)) {
                throw new IOException("palette index for " + e.getKey() + " is not a number");
            }
            int id = n.intValue();
            if (id < 0 || id >= byId.length) {
                throw new IOException("palette index " + id + " out of range");
            }
            byId[id] = BlockRef.parse(e.getKey());
        }

        Map<String, Object> meta = Nbt.compoundOrNull(s, "Metadata");
        Schematic.Builder out = Schematic.builder(name, width, height, length)
                .author(meta == null ? "" : Nbt.string(meta, "Author", ""))
                .format(Schematic.Format.SPONGE);

        long volume = (long) width * height * length;
        int cursor = 0;
        for (long i = 0; i < volume; i++) {
            int value = 0, shift = 0;
            while (true) {
                if (cursor >= bytes.length) {
                    throw new IOException("block data ends after " + i + " of " + volume + " blocks");
                }
                byte b = bytes[cursor++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("malformed varint in block data");
            }
            if (value >= 0 && value < byId.length && byId[value] != null) {
                int x = (int) (i % width);
                int z = (int) ((i / width) % length);
                int y = (int) (i / ((long) width * length));
                out.set(x, y, z, byId[value]);
            }
        }
        return out.build();
    }
}
