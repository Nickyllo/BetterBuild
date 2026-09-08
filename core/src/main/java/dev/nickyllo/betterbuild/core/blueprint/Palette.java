package dev.nickyllo.betterbuild.core.blueprint;

import java.util.EnumMap;
import java.util.Map;

/** Maps every {@link PaletteSlot} to a concrete block, with a safe default per slot. */
public final class Palette {

    private final Map<PaletteSlot, BlockRef> blocks;

    private Palette(Map<PaletteSlot, BlockRef> blocks) {
        this.blocks = new EnumMap<>(blocks);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Plain oak build — the fallback when the model gives us nothing usable. */
    public static Palette defaultOak() {
        return builder()
                .set(PaletteSlot.FOUNDATION, BlockRef.of("minecraft:cobblestone"))
                .set(PaletteSlot.WALL, BlockRef.of("minecraft:oak_planks"))
                .set(PaletteSlot.BEAM, BlockRef.of("minecraft:oak_log"))
                .set(PaletteSlot.FLOOR, BlockRef.of("minecraft:oak_planks"))
                .set(PaletteSlot.ROOF, BlockRef.of("minecraft:dark_oak_planks"))
                .set(PaletteSlot.ROOF_EDGE, BlockRef.of("minecraft:dark_oak_slab"))
                .set(PaletteSlot.WINDOW, BlockRef.of("minecraft:glass_pane"))
                .set(PaletteSlot.DOOR, BlockRef.of("minecraft:oak_door"))
                .set(PaletteSlot.LIGHT, BlockRef.of("minecraft:lantern", "minecraft:torch"))
                .set(PaletteSlot.ACCENT, BlockRef.of("minecraft:oak_fence"))
                .build();
    }

    public BlockRef get(PaletteSlot slot) {
        BlockRef ref = blocks.get(slot);
        if (ref == null) {
            throw new IllegalStateException("palette has no block for slot " + slot);
        }
        return ref;
    }

    public Map<PaletteSlot, BlockRef> asMap() {
        return Map.copyOf(blocks);
    }

    /** Returns a copy with one slot replaced — the basis of "make the roof slate". */
    public Palette with(PaletteSlot slot, BlockRef ref) {
        var copy = new EnumMap<>(blocks);
        copy.put(slot, ref);
        return new Palette(copy);
    }

    public static final class Builder {
        private final Map<PaletteSlot, BlockRef> blocks = new EnumMap<>(PaletteSlot.class);

        public Builder set(PaletteSlot slot, BlockRef ref) {
            blocks.put(slot, ref);
            return this;
        }

        /** Any slot left unset inherits the oak default, so a partial palette is still valid. */
        public Palette build() {
            var complete = new EnumMap<PaletteSlot, BlockRef>(PaletteSlot.class);
            for (PaletteSlot slot : PaletteSlot.values()) {
                BlockRef ref = blocks.get(slot);
                complete.put(slot, ref != null ? ref : DEFAULTS.get(slot));
            }
            return new Palette(complete);
        }
    }

    private static final Map<PaletteSlot, BlockRef> DEFAULTS = Map.of(
            PaletteSlot.FOUNDATION, BlockRef.of("minecraft:cobblestone"),
            PaletteSlot.WALL, BlockRef.of("minecraft:oak_planks"),
            PaletteSlot.BEAM, BlockRef.of("minecraft:oak_log"),
            PaletteSlot.FLOOR, BlockRef.of("minecraft:oak_planks"),
            PaletteSlot.ROOF, BlockRef.of("minecraft:dark_oak_planks"),
            PaletteSlot.ROOF_EDGE, BlockRef.of("minecraft:dark_oak_slab"),
            PaletteSlot.WINDOW, BlockRef.of("minecraft:glass_pane"),
            PaletteSlot.DOOR, BlockRef.of("minecraft:oak_door"),
            PaletteSlot.LIGHT, BlockRef.of("minecraft:lantern", "minecraft:torch"),
            PaletteSlot.ACCENT, BlockRef.of("minecraft:oak_fence"));
}
