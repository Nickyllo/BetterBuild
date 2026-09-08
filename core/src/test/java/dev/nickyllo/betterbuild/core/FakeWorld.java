package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import java.util.HashMap;
import java.util.Map;

/** In-memory world so the whole pipeline can be tested without launching Minecraft. */
public final class FakeWorld implements WorldView {

    private final Map<Vec3i, String> placed = new HashMap<>();
    private final int groundY;
    private final String biome;

    public FakeWorld(int groundY, String biome) {
        this.groundY = groundY;
        this.biome = biome;
    }

    @Override public boolean isSolid(Vec3i pos) {
        return pos.y() <= groundY || placed.containsKey(pos);
    }

    @Override public boolean isAir(Vec3i pos) { return !isSolid(pos); }

    @Override public boolean isLiquid(Vec3i pos) { return false; }

    @Override public int surfaceY(int x, int z) { return groundY; }

    @Override public boolean setBlock(Vec3i pos, BlockRef block) {
        if (block.isAir()) {
            placed.remove(pos);
        } else {
            placed.put(pos, block.id());
        }
        return true;
    }

    @Override public String biomeAt(Vec3i pos) { return biome; }

    @Override public Snapshot snapshot(Box region) {
        Map<Vec3i, String> before = new HashMap<>(placed);
        return new Snapshot() {
            @Override public void restore() {
                placed.clear();
                placed.putAll(before);
            }
            @Override public Box region() { return region; }
        };
    }

    public int placedCount() { return placed.size(); }
}
