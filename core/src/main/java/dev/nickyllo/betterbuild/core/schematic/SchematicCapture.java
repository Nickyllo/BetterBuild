package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import java.util.Optional;

/** Turns what is standing in the world, or what the compiler produced, into a schematic. */
public final class SchematicCapture {

    private SchematicCapture() {
    }

    /**
     * Copies a region of the world, trimmed to the blocks actually in it, so exporting
     * "around me" does not produce a file that is mostly sky.
     *
     * @return empty when the region holds nothing but air
     */
    public static Optional<Schematic> fromWorld(WorldView world, Box region, String name) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        region.forEach(p -> {
            if (!world.blockAt(p).isAir()) {
                lo[0] = Math.min(lo[0], p.x()); hi[0] = Math.max(hi[0], p.x());
                lo[1] = Math.min(lo[1], p.y()); hi[1] = Math.max(hi[1], p.y());
                lo[2] = Math.min(lo[2], p.z()); hi[2] = Math.max(hi[2], p.z());
            }
        });
        if (lo[0] == Integer.MAX_VALUE) {
            return Optional.empty();
        }
        Vec3i min = new Vec3i(lo[0], lo[1], lo[2]);
        Schematic.Builder b = Schematic.builder(name,
                hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1);
        new Box(min, new Vec3i(hi[0], hi[1], hi[2])).forEach(p -> {
            BlockRef ref = world.blockAt(p);
            if (!ref.isAir()) {
                Vec3i r = p.sub(min);
                b.set(r.x(), r.y(), r.z(), ref);
            }
        });
        return Optional.of(b.build());
    }

    /** A compiled design as a schematic, e.g. to open it in Litematica before building. */
    public static Schematic fromStructure(CompiledStructure structure) {
        Box e = structure.extent();
        Schematic.Builder b = Schematic.builder(structure.name(), e.sizeX(), e.sizeY(), e.sizeZ());
        for (var entry : structure.blocks().entrySet()) {
            if (!entry.getValue().isAir()) {
                Vec3i r = entry.getKey().sub(e.min());
                b.set(r.x(), r.y(), r.z(), entry.getValue());
            }
        }
        return b.build();
    }
}
