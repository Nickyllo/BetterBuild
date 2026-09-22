package dev.nickyllo.betterbuild.core.design;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The wire shape the model is allowed to produce.
 *
 * <p>Deliberately flat and primitive-only: every field is an int, a String or a
 * nested record, so the JSON schema derived from these types is strict and the model
 * physically cannot return a shape the mapper does not understand. Unions and
 * inheritance are avoided for the same reason.
 *
 * <p>Nothing here mentions block coordinates. The model describes a building; the
 * compiler decides where the blocks go.
 */
public record BlueprintDto(
        @JsonPropertyDescription("Short name for the building, 2-4 words.")
        String name,

        @JsonPropertyDescription("Blocks used for each role in the building.")
        PaletteDto palette,

        @JsonPropertyDescription("Building elements, applied in order. Later elements "
                + "overwrite earlier ones, so cut openings after the wall they pierce.")
        List<ElementDto> elements) {

    public record PaletteDto(
            @JsonPropertyDescription("Namespaced block id for the plinth, e.g. minecraft:cobblestone")
            String foundation,
            @JsonPropertyDescription("Namespaced block id for walls, e.g. minecraft:spruce_planks")
            String wall,
            @JsonPropertyDescription("Namespaced block id for visible timber posts, e.g. minecraft:dark_oak_log")
            String beam,
            @JsonPropertyDescription("Namespaced block id for floors")
            String floor,
            @JsonPropertyDescription("Namespaced block id for roofing")
            String roof,
            @JsonPropertyDescription("Namespaced block id for roof edges, usually a slab")
            String roofEdge,
            @JsonPropertyDescription("Namespaced block id for glazing, e.g. minecraft:glass_pane")
            String window,
            @JsonPropertyDescription("Namespaced block id for doors, e.g. minecraft:oak_door")
            String door,
            @JsonPropertyDescription("Namespaced block id for lighting, e.g. minecraft:lantern")
            String light,
            @JsonPropertyDescription("Namespaced block id for trim and detail")
            String accent) {
    }

    /** Inclusive corner-to-corner region, relative to the plot's minimum corner. */
    public record BoxDto(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    /**
     * One element. Which fields matter depends on {@code kind}; the rest are ignored,
     * which keeps a single flat schema usable for every primitive.
     */
    public record ElementDto(
            @JsonPropertyDescription("One of: SOLID_FILL, FLOOR_SLAB, PERIMETER_WALL, "
                    + "PILLAR, CORNER_POSTS, GABLE_ROOF, FLAT_ROOF, OPENING, MARKER, MODULE")
            String kind,

            @JsonPropertyDescription("Region the element occupies. For GABLE_ROOF and "
                    + "FLAT_ROOF this is the flat footprint at the height the roof starts. "
                    + "For OPENING it is the building being pierced.")
            BoxDto area,

            @JsonPropertyDescription("Palette role: FOUNDATION, WALL, BEAM, FLOOR, ROOF, "
                    + "ROOF_EDGE, WINDOW, DOOR, LIGHT or ACCENT")
            String slot,

            @JsonPropertyDescription("PERIMETER_WALL only: wall thickness in blocks, usually 1")
            int thickness,

            @JsonPropertyDescription("PILLAR only: height in blocks")
            int height,

            @JsonPropertyDescription("OPENING only: which wall to pierce - NORTH, SOUTH, EAST or WEST")
            String face,

            @JsonPropertyDescription("OPENING only: distance along that wall from the "
                    + "building's minimum corner")
            int offset,

            @JsonPropertyDescription("OPENING only: width in blocks")
            int width,

            @JsonPropertyDescription("OPENING only: height in blocks")
            int openingHeight,

            @JsonPropertyDescription("OPENING only: blocks above the building floor where "
                    + "the opening starts. Use 1 for a door, 2 or 3 for a window.")
            int sill,

            @JsonPropertyDescription("OPENING only: DOOR, WINDOW or VOID")
            String openingKind,

            @JsonPropertyDescription("GABLE_ROOF only: axis the ridge runs along, X or Z")
            String ridgeAxis,

            @JsonPropertyDescription("GABLE_ROOF only: blocks of rise per step inward, usually 1")
            int pitch,

            @JsonPropertyDescription("Roofs only: how far the eaves extend past the walls, usually 1")
            int overhang,

            @JsonPropertyDescription("MODULE only: exact name of one of the modules you were offered. "
                    + "It is placed with its minimum corner at area x1,y1,z1.")
            String module,

            @JsonPropertyDescription("MODULE only: quarter turns clockwise seen from above, 0 to 3")
            int rotation) {
    }
}
