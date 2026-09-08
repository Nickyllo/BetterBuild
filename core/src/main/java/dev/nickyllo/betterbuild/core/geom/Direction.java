package dev.nickyllo.betterbuild.core.geom;

/**
 * Cardinal directions using Minecraft's convention (north = -Z, east = +X),
 * declared here so the core never imports a Minecraft class.
 */
public enum Direction {
    NORTH(0, 0, -1),
    EAST(1, 0, 0),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    private final int dx, dy, dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public int dx() { return dx; }
    public int dy() { return dy; }
    public int dz() { return dz; }

    public boolean isHorizontal() {
        return this != UP && this != DOWN;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            case UP -> DOWN;
            case DOWN -> UP;
        };
    }

    /** Clockwise looking down, for rotating a whole blueprint. */
    public Direction rotateY() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public static final Direction[] HORIZONTAL = { NORTH, EAST, SOUTH, WEST };
}
