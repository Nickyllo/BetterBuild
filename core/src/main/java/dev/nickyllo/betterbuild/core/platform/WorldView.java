package dev.nickyllo.betterbuild.core.platform;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

/**
 * The slice of the Minecraft world the core is allowed to touch. Every supported
 * version and loader implements this and nothing else changes.
 *
 * <p>Deliberately narrow: the core cannot spawn entities, fire events, or reach into
 * the server, so a change in any Minecraft API can only ever break the adapter.
 */
public interface WorldView {

    boolean isSolid(Vec3i pos);

    boolean isAir(Vec3i pos);

    /** True for water and lava, which change how a plot must be prepared. */
    boolean isLiquid(Vec3i pos);

    /** Y of the highest non-air block at this column — the ground the build sits on. */
    int surfaceY(int x, int z);

    /** Places a block; returns false when the platform refused (protection, unloaded chunk). */
    boolean setBlock(Vec3i pos, BlockRef block);

    /** Captures the current contents of a region so a build can be undone exactly. */
    Snapshot snapshot(Box region);

    /** Biome id such as {@code minecraft:plains}, used to pick a fitting palette. */
    String biomeAt(Vec3i pos);

    /** An opaque restore point. Implementations decide how to store it. */
    interface Snapshot {
        void restore();

        Box region();
    }
}
