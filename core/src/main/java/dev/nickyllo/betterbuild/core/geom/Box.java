package dev.nickyllo.betterbuild.core.geom;

import java.util.function.Consumer;

/** Inclusive axis-aligned box of block positions. */
public record Box(Vec3i min, Vec3i max) {

    public Box {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("min must not exceed max: " + min + " .. " + max);
        }
    }

    /** Builds a box from two arbitrary corners, normalising them. */
    public static Box between(Vec3i a, Vec3i b) {
        return new Box(
                new Vec3i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                new Vec3i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }

    public static Box atOrigin(int sizeX, int sizeY, int sizeZ) {
        return new Box(Vec3i.ZERO, new Vec3i(sizeX - 1, sizeY - 1, sizeZ - 1));
    }

    public int sizeX() { return max.x() - min.x() + 1; }
    public int sizeY() { return max.y() - min.y() + 1; }
    public int sizeZ() { return max.z() - min.z() + 1; }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(Vec3i p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public Vec3i center() {
        return new Vec3i(
                min.x() + sizeX() / 2,
                min.y() + sizeY() / 2,
                min.z() + sizeZ() / 2);
    }

    public Box translated(Vec3i by) {
        return new Box(min.add(by), max.add(by));
    }

    /** Iterates every position, y-ascending, so callers get bottom-up order for free. */
    public void forEach(Consumer<Vec3i> action) {
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    action.accept(new Vec3i(x, y, z));
                }
            }
        }
    }
}
