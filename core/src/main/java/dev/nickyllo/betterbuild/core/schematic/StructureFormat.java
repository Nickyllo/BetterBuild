package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Minecraft's own structure files ({@code .nbt}), as saved by structure blocks and
 * found in datapacks — villages, outposts and the like.
 */
public final class StructureFormat {

    private StructureFormat() {
    }

    public static Schematic read(Map<String, Object> root, String name) throws IOException {
        Nbt.NbtList size = Nbt.list(root, "size");
        if (size.size() != 3) throw new IOException("structure size must have 3 entries");
        int sx = ((Number) size.get(0)).intValue();
        int sy = ((Number) size.get(1)).intValue();
        int sz = ((Number) size.get(2)).intValue();

        Nbt.NbtList paletteTag;
        if (root.get("palette") instanceof Nbt.NbtList l) {
            paletteTag = l;
        } else if (root.get("palettes") instanceof Nbt.NbtList many && many.size() > 0
                && many.get(0) instanceof Nbt.NbtList first) {
            paletteTag = first; // Variants (e.g. shipwrecks); the first one is canonical.
        } else {
            throw new IOException("structure has no palette");
        }
        List<BlockRef> palette = LitematicaFormat.readPalette(paletteTag);

        Schematic.Builder out = Schematic.builder(name, sx, sy, sz).format(Schematic.Format.STRUCTURE);
        for (Object o : Nbt.list(root, "blocks").items()) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) o;
            int state = Nbt.integer(block, "state");
            Nbt.NbtList pos = Nbt.list(block, "pos");
            if (state < 0 || state >= palette.size() || pos.size() != 3) continue;
            out.set(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(),
                    ((Number) pos.get(2)).intValue(), palette.get(state));
        }
        return out.build();
    }
}
