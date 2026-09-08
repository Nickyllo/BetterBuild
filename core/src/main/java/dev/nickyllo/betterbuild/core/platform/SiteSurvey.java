package dev.nickyllo.betterbuild.core.platform;

import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

/**
 * What the Architect learns by walking the plot before designing. Gathered by the
 * platform layer from {@link WorldView}, then handed to the design provider so the
 * building fits the ground it stands on.
 */
public record SiteSurvey(Box plot, String biome, int lowestGround, int highestGround,
                         boolean hasWater, int liquidCount) {

    /** Height difference across the plot — the number that decides terracing vs stilts. */
    public int slope() {
        return highestGround - lowestGround;
    }

    public boolean isSteep() {
        return slope() > 4;
    }

    /** Surveys the plot by sampling every column. */
    public static SiteSurvey of(WorldView world, Box plot) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        int liquids = 0;

        for (int x = plot.min().x(); x <= plot.max().x(); x++) {
            for (int z = plot.min().z(); z <= plot.max().z(); z++) {
                int y = world.surfaceY(x, z);
                lowest = Math.min(lowest, y);
                highest = Math.max(highest, y);
                if (world.isLiquid(new Vec3i(x, y, z))) {
                    liquids++;
                }
            }
        }
        return new SiteSurvey(plot, world.biomeAt(plot.center()), lowest, highest,
                liquids > 0, liquids);
    }

    /** How the Architect describes the plot in chat after looking at it. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(plot.sizeX()).append(" by ").append(plot.sizeZ());
        if (slope() > 1) {
            sb.append(", ").append(slope()).append(" blocks of fall across it");
        } else {
            sb.append(", nice and flat");
        }
        if (hasWater) {
            sb.append(", water on part of it");
        }
        return sb.toString();
    }
}
