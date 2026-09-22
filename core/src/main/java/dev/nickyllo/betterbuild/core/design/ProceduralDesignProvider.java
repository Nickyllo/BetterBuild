package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.blueprint.Element;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;

/**
 * Builds a sound, if unimaginative, house from fixed rules.
 *
 * <p>It serves three purposes: it is the offline fallback when no model is reachable,
 * it is the reference implementation showing what a provider must emit, and it lets
 * the whole pipeline — compile, validate, plan, build — be exercised and tested
 * without spending a single token.
 */
public final class ProceduralDesignProvider implements DesignProvider {

    private static final int MIN_SIDE = 5;
    private static final int MAX_SIDE = 24;
    private static final int OVERHANG = 1;

    @Override
    public String name() {
        return "Local repertoire";
    }

    @Override
    public Blueprint design(DesignRequest request) throws DesignException {
        SiteSurvey survey = request.survey();
        int width = clamp(survey.plot().sizeX());
        int depth = clamp(survey.plot().sizeZ());

        if (width < MIN_SIDE || depth < MIN_SIDE) {
            throw new DesignException("The plot is too small — I need at least "
                    + MIN_SIDE + " by " + MIN_SIDE + " to fit a building.");
        }

        // A learned style wins over the built-in defaults: the whole point of
        // teaching him is that what he saw beats what he was born knowing.
        StyleProfile learned = request.style().orElse(null);

        int storeys = learned != null
                ? learned.storeys()
                : (asksForTwoStoreys(request.prompt()) ? 2 : 1);
        int wallHeight = learned != null ? learned.wallHeight() : 4 * storeys + 1;
        int pitch = learned != null && learned.hasGableRoof() ? learned.roofPitch() : 1;

        // The roof overhangs by one, so the walls are set in by one: the finished
        // building — eaves included — stays inside the plot the player marked.
        final int inset = OVERHANG;
        int bx0 = inset, bz0 = inset;
        int bx1 = width - 1 - inset, bz1 = depth - 1 - inset;

        Box footprint = new Box(new Vec3i(bx0, 1, bz0), new Vec3i(bx1, wallHeight, bz1));
        Palette palette = learned != null ? learned.palette() : paletteFor(survey);

        Blueprint.Builder b = Blueprint.named(titleFor(request.prompt()))
                .size(width, wallHeight + Math.max(width, depth) / 2 + 4, depth)
                .palette(palette)
                // Plinth: one solid course so the building never floats on sloped ground.
                .add(new Element.SolidFill(
                        new Box(new Vec3i(bx0, 0, bz0), new Vec3i(bx1, 0, bz1)),
                        PaletteSlot.FOUNDATION))
                .add(new Element.FloorSlab(
                        new Box(new Vec3i(bx0, 1, bz0), new Vec3i(bx1, 1, bz1)),
                        PaletteSlot.FLOOR))
                .add(new Element.PerimeterWall(footprint, PaletteSlot.WALL, 1))
                .add(new Element.CornerPosts(footprint, PaletteSlot.BEAM));

        int innerWidth = footprint.sizeX();
        int innerDepth = footprint.sizeZ();

        // Front door, centred on the south face.
        b.add(new Element.Opening(footprint, Direction.SOUTH, innerWidth / 2, 1, 2, 1,
                Element.OpeningKind.DOOR));

        // Windows on every storey, kept clear of the corners.
        for (int storey = 0; storey < storeys; storey++) {
            int sill = 2 + storey * 4;
            for (Direction face : Direction.HORIZONTAL) {
                int span = (face == Direction.NORTH || face == Direction.SOUTH)
                        ? innerWidth : innerDepth;
                // Glaze as densely as whatever he learned from, else every 3 blocks.
                int every = learned != null && learned.windowRatio() > 0
                        ? Math.max(2, (int) Math.round(1.0 / Math.max(0.08, learned.windowRatio())))
                        : 3;
                for (int at = 2; at < span - 2; at += every) {
                    boolean clashesWithDoor = face == Direction.SOUTH && storey == 0
                            && Math.abs(at - innerWidth / 2) <= 1;
                    if (!clashesWithDoor) {
                        b.add(new Element.Opening(footprint, face, at, 1, 2, sill,
                                Element.OpeningKind.WINDOW));
                    }
                }
            }
        }

        Element.Axis ridge = innerWidth >= innerDepth ? Element.Axis.X : Element.Axis.Z;
        int overhang = learned != null ? Math.max(0, Math.min(OVERHANG, learned.overhang())) : OVERHANG;
        if (learned != null && !learned.hasGableRoof()) {
            b.add(new Element.FlatRoof(
                    new Box(new Vec3i(bx0, wallHeight + 1, bz0), new Vec3i(bx1, wallHeight + 1, bz1)),
                    overhang, PaletteSlot.ROOF));
        } else {
            b.add(new Element.GableRoof(
                    new Box(new Vec3i(bx0, wallHeight + 1, bz0), new Vec3i(bx1, wallHeight + 1, bz1)),
                    ridge, pitch, overhang, PaletteSlot.ROOF));
        }

        // Lantern hung against an inside wall — a light floating in the middle of the
        // room would be flagged by the validator, and rightly so.
        b.add(new Element.Marker(new Vec3i(bx0 + 1, wallHeight - 1, bz0 + 1), PaletteSlot.LIGHT));

        return b.build();
    }

    /** Picks materials that belong in the biome the plot sits in. */
    private Palette paletteFor(SiteSurvey survey) {
        String biome = survey.biome() == null ? "" : survey.biome();
        if (biome.contains("desert") || biome.contains("badlands")) {
            return Palette.builder()
                    .set(PaletteSlot.FOUNDATION, BlockRef.of("minecraft:smooth_sandstone"))
                    .set(PaletteSlot.WALL, BlockRef.of("minecraft:sandstone"))
                    .set(PaletteSlot.BEAM, BlockRef.of("minecraft:stripped_acacia_log"))
                    .set(PaletteSlot.FLOOR, BlockRef.of("minecraft:smooth_sandstone"))
                    .set(PaletteSlot.ROOF, BlockRef.of("minecraft:acacia_planks"))
                    .set(PaletteSlot.ROOF_EDGE, BlockRef.of("minecraft:acacia_slab"))
                    .set(PaletteSlot.DOOR, BlockRef.of("minecraft:acacia_door"))
                    .build();
        }
        if (biome.contains("taiga") || biome.contains("snow") || biome.contains("frozen")) {
            return Palette.builder()
                    .set(PaletteSlot.FOUNDATION, BlockRef.of("minecraft:cobblestone"))
                    .set(PaletteSlot.WALL, BlockRef.of("minecraft:spruce_planks"))
                    .set(PaletteSlot.BEAM, BlockRef.of("minecraft:spruce_log"))
                    .set(PaletteSlot.FLOOR, BlockRef.of("minecraft:spruce_planks"))
                    .set(PaletteSlot.ROOF, BlockRef.of("minecraft:deepslate_tiles", "minecraft:stone_bricks"))
                    .set(PaletteSlot.ROOF_EDGE, BlockRef.of("minecraft:deepslate_tile_slab", "minecraft:stone_brick_slab"))
                    .set(PaletteSlot.DOOR, BlockRef.of("minecraft:spruce_door"))
                    .build();
        }
        return Palette.defaultOak();
    }

    /** "two", "dos" or "2 plantas" — players type in whatever language they play in. */
    private static boolean asksForTwoStoreys(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
        return p.contains("two") || p.contains("dos ") || p.contains("2 plantas") || p.contains("2 pisos");
    }

    private String titleFor(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            return "House";
        }
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    private int clamp(int side) {
        return Math.min(MAX_SIDE, side);
    }
}
