package dev.nickyllo.betterbuild.core.blueprint;

/**
 * Roles a block can play in a building. Elements reference a role, not a block,
 * so recolouring a whole structure ("make it stone") is one palette swap and
 * never requires recompiling the design through the model.
 */
public enum PaletteSlot {
    FOUNDATION,
    WALL,
    BEAM,
    FLOOR,
    ROOF,
    ROOF_EDGE,
    WINDOW,
    DOOR,
    LIGHT,
    ACCENT
}
