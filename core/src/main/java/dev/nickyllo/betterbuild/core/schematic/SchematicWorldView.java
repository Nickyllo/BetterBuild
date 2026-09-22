package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

/**
 * A schematic seen as a tiny read-only world.
 *
 * <p>This is what lets everything that learns from the world learn from files too,
 * without a line of it changing: the style learner walks this exactly as it walks the
 * area around a player.
 */
public final class SchematicWorldView implements WorldView {

    private final Schematic schematic;

    public SchematicWorldView(Schematic schematic) {
        this.schematic = schematic;
    }

    @Override public boolean isSolid(Vec3i pos) { return !schematic.at(pos).isAir(); }
    @Override public boolean isAir(Vec3i pos) { return schematic.at(pos).isAir(); }
    @Override public String blockIdAt(Vec3i pos) { return schematic.at(pos).id(); }
    @Override public BlockRef blockAt(Vec3i pos) { return schematic.at(pos); }

    @Override
    public boolean isLiquid(Vec3i pos) {
        String id = schematic.at(pos).id();
        return id.equals("minecraft:water") || id.equals("minecraft:lava");
    }

    @Override
    public int surfaceY(int x, int z) {
        for (int y = schematic.sizeY() - 1; y >= 0; y--) {
            if (!schematic.at(x, y, z).isAir()) return y;
        }
        return -1;
    }

    /** Read-only: a file is never modified by looking at it. */
    @Override public boolean setBlock(Vec3i pos, BlockRef block) { return false; }

    @Override public String biomeAt(Vec3i pos) { return "minecraft:plains"; }

    @Override
    public Snapshot snapshot(Box region) {
        return new Snapshot() {
            @Override public void restore() { }
            @Override public Box region() { return region; }
        };
    }
}
