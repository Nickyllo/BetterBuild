package dev.nickyllo.betterbuild.core.geom;

/** Immutable integer block position. Version-independent: no Minecraft types. */
public record Vec3i(int x, int y, int z) {

    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    public Vec3i add(int dx, int dy, int dz) {
        return new Vec3i(x + dx, y + dy, z + dz);
    }

    public Vec3i add(Vec3i o) {
        return new Vec3i(x + o.x, y + o.y, z + o.z);
    }

    public Vec3i sub(Vec3i o) {
        return new Vec3i(x - o.x, y - o.y, z - o.z);
    }

    public Vec3i up(int n) {
        return new Vec3i(x, y + n, z);
    }

    public Vec3i offset(Direction d, int n) {
        return new Vec3i(x + d.dx() * n, y + d.dy() * n, z + d.dz() * n);
    }

    /** Squared horizontal distance; avoids a sqrt in hot pathfinding-ish loops. */
    public long horizontalDistSq(Vec3i o) {
        long dx = (long) x - o.x, dz = (long) z - o.z;
        return dx * dx + dz * dz;
    }

    public long distSq(Vec3i o) {
        long dx = (long) x - o.x, dy = (long) y - o.y, dz = (long) z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
