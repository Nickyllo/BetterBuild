package dev.nickyllo.betterbuild.core.learn;

import dev.nickyllo.betterbuild.core.blueprint.Palette;
import java.util.Map;
import java.util.Optional;

/**
 * What the Architect learned from looking at a building someone else made.
 *
 * <p>Not a copy of that building — a description of its taste: which blocks, how tall
 * the storeys, how steep the roof, how many windows. A new design built from this
 * profile looks like it belongs next to the original without being the same house.
 *
 * @param source       name the player gave it, for "build me another like the tavern"
 * @param palette      blocks in the roles they were found playing
 * @param wallHeight   floor to eaves, in blocks
 * @param storeys      how many floors were detected
 * @param roofPitch    blocks of rise per step inward; 0 means a flat roof
 * @param overhang     how far the eaves stick out past the walls
 * @param windowRatio  windows per block of wall length, so a new building is glazed
 *                     as densely as the one it learned from
 * @param footprint    width x depth of the original, to keep proportions
 */
public record StyleProfile(String source, Palette palette, int wallHeight, int storeys,
                           int roofPitch, int overhang, double windowRatio,
                           int footprintWidth, int footprintDepth,
                           Map<String, Integer> blockCounts) {

    public StyleProfile {
        blockCounts = Map.copyOf(blockCounts);
    }

    public boolean hasGableRoof() {
        return roofPitch > 0;
    }

    /** Proportion of the original, used to size a new building sensibly. */
    public double aspect() {
        return footprintDepth == 0 ? 1.0 : (double) footprintWidth / footprintDepth;
    }

    /** How the Architect describes what he picked up, in chat. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(footprintWidth).append("x").append(footprintDepth);
        sb.append(", ").append(storeys).append(storeys == 1 ? " planta" : " plantas");
        sb.append(", muros de ").append(wallHeight);
        sb.append(hasGableRoof() ? ", tejado a dos aguas" : ", tejado plano");
        String wall = palette.get(dev.nickyllo.betterbuild.core.blueprint.PaletteSlot.WALL).id();
        sb.append(", paredes de ").append(wall.replace("minecraft:", ""));
        return sb.toString();
    }

    /** Empty when the region held nothing worth learning from. */
    public static Optional<StyleProfile> none() {
        return Optional.empty();
    }
}
