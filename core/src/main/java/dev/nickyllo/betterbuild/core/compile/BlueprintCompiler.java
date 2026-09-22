package dev.nickyllo.betterbuild.core.compile;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.blueprint.Element;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a {@link Blueprint} into concrete blocks.
 *
 * <p>This class is the reason the mod can trust a language model. The model only ever
 * produces primitives; every actual block position comes from the fixed rules here,
 * so the output is deterministic, reproducible, and cheap to re-derive when the
 * player says "raise the roof" — one field changes and we recompile locally.
 */
public final class BlueprintCompiler {

    /** Openings never cut deeper than this, so a bad offset can't tunnel through a build. */
    private static final int MAX_OPENING_DEPTH = 4;

    public CompiledStructure compile(Blueprint blueprint) {
        Map<Vec3i, BlockRef> out = new LinkedHashMap<>();
        Palette palette = blueprint.palette();

        for (Element element : blueprint.elements()) {
            apply(element, palette, out);
        }
        return new CompiledStructure(blueprint.name(), out);
    }

    private void apply(Element element, Palette palette, Map<Vec3i, BlockRef> out) {
        // Dispatched with instanceof rather than a pattern switch: the core compiles to
        // Java 17 bytecode so Minecraft 1.20.x, which runs on Java 17, can load it.
        if (element instanceof Element.SolidFill e) {
            e.area().forEach(p -> out.put(p, palette.get(e.slot())));

        } else if (element instanceof Element.FloorSlab e) {
            {
                Box a = e.area();
                int y = a.min().y();
                for (int x = a.min().x(); x <= a.max().x(); x++) {
                    for (int z = a.min().z(); z <= a.max().z(); z++) {
                        out.put(new Vec3i(x, y, z), palette.get(e.slot()));
                    }
                }
            }

        } else if (element instanceof Element.PerimeterWall e) {
            compileWall(e, palette, out);

        } else if (element instanceof Element.Pillar e) {
            for (int i = 0; i < e.height(); i++) {
                out.put(e.base().up(i), palette.get(e.slot()));
            }

        } else if (element instanceof Element.CornerPosts e) {
            {
                Box a = e.area();
                BlockRef block = palette.get(e.slot());
                int[][] corners = {
                        {a.min().x(), a.min().z()}, {a.max().x(), a.min().z()},
                        {a.min().x(), a.max().z()}, {a.max().x(), a.max().z()}
                };
                for (int[] c : corners) {
                    for (int y = a.min().y(); y <= a.max().y(); y++) {
                        out.put(new Vec3i(c[0], y, c[1]), block);
                    }
                }
            }

        } else if (element instanceof Element.GableRoof e) {
            compileGableRoof(e, palette, out);

        } else if (element instanceof Element.FlatRoof e) {
            {
                Box f = e.footprint();
                int y = f.min().y();
                for (int x = f.min().x() - e.overhang(); x <= f.max().x() + e.overhang(); x++) {
                    for (int z = f.min().z() - e.overhang(); z <= f.max().z() + e.overhang(); z++) {
                        boolean edge = x < f.min().x() || x > f.max().x()
                                || z < f.min().z() || z > f.max().z();
                        out.put(new Vec3i(x, y, z),
                                palette.get(edge ? PaletteSlot.ROOF_EDGE : e.slot()));
                    }
                }
            }

        } else if (element instanceof Element.Opening e) {
            compileOpening(e, palette, out);

        } else if (element instanceof Element.Marker e) {
            out.put(e.pos(), palette.get(e.slot()));

        } else if (element instanceof Element.Module e) {
            e.schematic().rotated(e.rotation())
                    .forEachBlock((pos, ref) -> out.put(e.at().add(pos), ref));
        }
    }

    private void compileWall(Element.PerimeterWall e, Palette palette, Map<Vec3i, BlockRef> out) {
        Box a = e.area();
        BlockRef block = palette.get(e.slot());
        int t = e.thickness();
        for (int y = a.min().y(); y <= a.max().y(); y++) {
            for (int x = a.min().x(); x <= a.max().x(); x++) {
                for (int z = a.min().z(); z <= a.max().z(); z++) {
                    boolean inRing = x < a.min().x() + t || x > a.max().x() - t
                            || z < a.min().z() + t || z > a.max().z() - t;
                    if (inRing) {
                        out.put(new Vec3i(x, y, z), block);
                    }
                }
            }
        }
    }

    /**
     * Stepped gable roof. Height rises {@code pitch} blocks per step toward the ridge;
     * for a pitch above 1 the column is filled downward so the roof has no gaps you
     * can see the sky through. The triangular gable ends are filled with the WALL slot
     * — without that the building is open at both ends.
     */
    private void compileGableRoof(Element.GableRoof e, Palette palette, Map<Vec3i, BlockRef> out) {
        Box f = e.footprint();
        BlockRef roof = palette.get(e.slot());
        BlockRef gableFill = palette.get(PaletteSlot.WALL);
        int baseY = f.min().y();
        int o = e.overhang();

        int minX = f.min().x() - o, maxX = f.max().x() + o;
        int minZ = f.min().z() - o, maxZ = f.max().z() + o;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int step = e.ridgeAxis() == Element.Axis.X
                        ? Math.min(z - f.min().z(), f.max().z() - z)
                        : Math.min(x - f.min().x(), f.max().x() - x);

                int y = baseY + step * e.pitch();
                // Close the vertical gaps a steep pitch would otherwise leave.
                for (int fill = 0; fill < e.pitch(); fill++) {
                    int fy = y - fill;
                    if (fy >= baseY - o * e.pitch()) {
                        out.put(new Vec3i(x, fy, z), roof);
                    }
                }

                // Fill the gable triangle below the slope at the two ends.
                boolean atGableEnd = e.ridgeAxis() == Element.Axis.X
                        ? (x == f.min().x() || x == f.max().x())
                        : (z == f.min().z() || z == f.max().z());
                if (atGableEnd && step > 0) {
                    for (int gy = baseY; gy < y - e.pitch() + 1; gy++) {
                        out.putIfAbsent(new Vec3i(x, gy, z), gableFill);
                    }
                }
            }
        }
    }

    /**
     * Cuts a hole through a wall. The depth is discovered from what is already in the
     * map — elements are applied in order, so the wall is present by now — which means
     * one opening definition works for a wall of any thickness.
     */
    private void compileOpening(Element.Opening e, Palette palette, Map<Vec3i, BlockRef> out) {
        Box b = e.building();
        Direction inward = e.face().opposite();

        for (int w = 0; w < e.width(); w++) {
            for (int h = 0; h < e.height(); h++) {
                Vec3i start = faceStart(b, e.face(), e.offset() + w, b.min().y() + e.sill() + h);
                if (start == null) {
                    continue;
                }
                Vec3i cursor = start;
                for (int d = 0; d < MAX_OPENING_DEPTH; d++) {
                    if (d > 0 && !isSolid(out, cursor)) {
                        break;
                    }
                    out.put(cursor, BlockRef.AIR);
                    cursor = cursor.offset(inward, 1);
                }

                if (e.kind() == Element.OpeningKind.WINDOW) {
                    out.put(start, palette.get(PaletteSlot.WINDOW));
                } else if (e.kind() == Element.OpeningKind.DOOR && h < 2) {
                    // Both halves, explicitly. Setting a door block directly does not
                    // grow its upper half the way placing one by hand does, so a door
                    // emitted as one block would stand in the world as half a door.
                    // Facing inward matches a player placing it from outside.
                    out.put(start, palette.get(PaletteSlot.DOOR)
                            .with("facing", inward.name().toLowerCase(java.util.Locale.ROOT))
                            .with("half", h == 0 ? "lower" : "upper"));
                }
            }
        }
    }

    /**
     * Position on the given face. {@code along} is measured from the building's
     * minimum corner on that face's axis (X for north/south, Z for east/west).
     * Returns null when the offset falls outside the building.
     */
    private Vec3i faceStart(Box b, Direction face, int along, int y) {
        return switch (face) {
            case NORTH -> {
                int x = b.min().x() + along;
                yield x > b.max().x() ? null : new Vec3i(x, y, b.min().z());
            }
            case SOUTH -> {
                int x = b.min().x() + along;
                yield x > b.max().x() ? null : new Vec3i(x, y, b.max().z());
            }
            case WEST -> {
                int z = b.min().z() + along;
                yield z > b.max().z() ? null : new Vec3i(b.min().x(), y, z);
            }
            case EAST -> {
                int z = b.min().z() + along;
                yield z > b.max().z() ? null : new Vec3i(b.max().x(), y, z);
            }
            default -> null;
        };
    }

    private boolean isSolid(Map<Vec3i, BlockRef> out, Vec3i pos) {
        BlockRef ref = out.get(pos);
        return ref != null && !ref.isAir();
    }
}
