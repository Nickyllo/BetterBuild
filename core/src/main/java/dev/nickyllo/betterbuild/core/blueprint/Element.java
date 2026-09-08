package dev.nickyllo.betterbuild.core.blueprint;

import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

/**
 * One architectural primitive. This is the entire vocabulary the language model is
 * allowed to speak: it never emits block coordinates, only these shapes, which the
 * compiler turns into blocks deterministically.
 *
 * <p>Elements are applied in order and later ones overwrite earlier ones, so an
 * {@link Opening} punched after a {@link PerimeterWall} simply wins on those positions.
 */
public sealed interface Element {

    /** The region this element can affect, used for bounds checking before compiling. */
    Box bounds();

    /** Solid block of material — foundations, plinths, filled volumes. */
    record SolidFill(Box area, PaletteSlot slot) implements Element {
        @Override public Box bounds() { return area; }
    }

    /** One flat layer at {@code area.min().y()}, ignoring the box's height. */
    record FloorSlab(Box area, PaletteSlot slot) implements Element {
        @Override public Box bounds() { return area; }
    }

    /** Hollow rectangular wall of the given thickness, full height of the box. */
    record PerimeterWall(Box area, PaletteSlot slot, int thickness) implements Element {
        public PerimeterWall {
            if (thickness < 1) throw new IllegalArgumentException("thickness must be >= 1");
        }
        @Override public Box bounds() { return area; }
    }

    /** Vertical post from {@code base} upward. */
    record Pillar(Vec3i base, int height, PaletteSlot slot) implements Element {
        public Pillar {
            if (height < 1) throw new IllegalArgumentException("height must be >= 1");
        }
        @Override public Box bounds() { return new Box(base, base.up(height - 1)); }
    }

    /** Corner posts of a box — the visible timber frame of a medieval build. */
    record CornerPosts(Box area, PaletteSlot slot) implements Element {
        @Override public Box bounds() { return area; }
    }

    /**
     * Gable roof rising from the top of {@code footprint}. It slopes along the
     * {@code ridgeAxis}: {@link Axis#X} makes the ridge run east-west.
     */
    record GableRoof(Box footprint, Axis ridgeAxis, int pitch, int overhang, PaletteSlot slot)
            implements Element {
        public GableRoof {
            if (pitch < 1) throw new IllegalArgumentException("pitch must be >= 1");
            if (overhang < 0) throw new IllegalArgumentException("overhang must be >= 0");
        }
        @Override
        public Box bounds() {
            int span = ridgeAxis == Axis.X ? footprint.sizeZ() : footprint.sizeX();
            int rise = (span / 2 + 1) * pitch;
            return new Box(
                    footprint.min().add(-overhang, 0, -overhang),
                    footprint.max().add(overhang, rise, overhang));
        }
    }

    /** Flat roof: one slab layer on top of the footprint, plus optional overhang. */
    record FlatRoof(Box footprint, int overhang, PaletteSlot slot) implements Element {
        @Override
        public Box bounds() {
            return new Box(
                    footprint.min().add(-overhang, 0, -overhang),
                    footprint.max().add(overhang, 0, overhang));
        }
    }

    /**
     * A hole in one face of {@code building}, optionally framed and filled.
     *
     * @param face       which wall of the building to cut
     * @param offset     distance along that face from its left edge (facing the building)
     * @param sill       height above the building floor where the opening starts
     */
    record Opening(Box building, Direction face, int offset, int width, int height, int sill,
                   OpeningKind kind) implements Element {
        public Opening {
            if (!face.isHorizontal()) throw new IllegalArgumentException("face must be horizontal");
            if (width < 1 || height < 1) throw new IllegalArgumentException("opening must be at least 1x1");
            if (offset < 0 || sill < 0) throw new IllegalArgumentException("offset and sill must be >= 0");
        }
        @Override public Box bounds() { return building; }
    }

    /** Single decorative or functional block — lanterns, chimneys' tops, signage. */
    record Marker(Vec3i pos, PaletteSlot slot) implements Element {
        @Override public Box bounds() { return new Box(pos, pos); }
    }

    enum Axis { X, Z }

    enum OpeningKind {
        /** Cut through and leave empty — arches, passages. */
        VOID,
        /** Cut through and glaze with the WINDOW slot. */
        WINDOW,
        /** Cut through, place the DOOR slot at the bottom, leave the rest clear. */
        DOOR
    }
}
