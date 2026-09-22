package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rotates a block's state along with its position.
 *
 * <p>Turning a porch 90 degrees moves its blocks, but a stair that kept facing north
 * would now face into a wall. Every orientation-bearing property Minecraft uses for
 * building blocks is handled: {@code facing}, {@code axis}, the sixteen-step
 * {@code rotation} of signs and banners, and the per-side connection flags of
 * fences, panes and walls ({@code north=true} becomes {@code east=true}).
 */
public final class StateRotation {

    private static final String[] CARDINAL = {"north", "east", "south", "west"};

    private StateRotation() {
    }

    /** Clockwise, looking down, by {@code quarterTurns} × 90°. */
    public static BlockRef rotate(BlockRef ref, int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0 || ref.properties().isEmpty()) {
            return ref;
        }
        Map<String, String> out = new TreeMap<>();
        for (var e : ref.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();

            int side = indexOf(key);
            if (side >= 0) {
                // A connection flag: the key itself names a side and has to turn.
                out.put(CARDINAL[(side + q) % 4], value);
                continue;
            }
            switch (key) {
                case "facing", "horizontal_facing" -> {
                    int f = indexOf(value);
                    out.put(key, f >= 0 ? CARDINAL[(f + q) % 4] : value);
                }
                case "axis" -> out.put(key, (q % 2 == 1)
                        ? switch (value) { case "x" -> "z"; case "z" -> "x"; default -> value; }
                        : value);
                case "rotation" -> {
                    try {
                        out.put(key, Integer.toString((Integer.parseInt(value) + 4 * q) % 16));
                    } catch (NumberFormatException ex) {
                        out.put(key, value);
                    }
                }
                default -> out.put(key, value);
            }
        }
        return ref.withProperties(out);
    }

    private static int indexOf(String s) {
        for (int i = 0; i < CARDINAL.length; i++) {
            if (CARDINAL[i].equals(s)) return i;
        }
        return -1;
    }
}
