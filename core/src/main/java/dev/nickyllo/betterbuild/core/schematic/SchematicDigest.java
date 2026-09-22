package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.learn.StyleLearner;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A schematic described in text a language model can learn from.
 *
 * <p>A model cannot read a {@code .litematic}, and would drown in a block list. What
 * it can use is what a person would notice looking at the building: its size, what it
 * is made of and where, how tall the storeys are, how steep the roof — and the front
 * elevation and ground plan drawn in characters, so proportion and rhythm (door
 * centred, windows every three blocks, posts at the corners) come across directly.
 */
public final class SchematicDigest {

    /** Drawings are downsampled beyond this many characters wide. */
    private static final int MAX_DRAW = 40;

    public static final String LEGEND = "# solid  ^ roofing  I timber  / stairs  - slab  o glass  "
            + "D door  + fence/wall/bars  * light  & leaves  ~ water";

    private SchematicDigest() {
    }

    public static String of(Schematic s) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(s.name()).append(" — ")
                .append(s.sizeX()).append(" wide, ").append(s.sizeY()).append(" tall, ")
                .append(s.sizeZ()).append(" deep, ").append(s.blockCount()).append(" blocks");
        if (!s.author().isBlank()) sb.append(", by ").append(s.author());
        sb.append('\n');

        Optional<StyleProfile> style = new StyleLearner()
                .learn(new SchematicWorldView(s), s.box(), s.name());
        // Roofing drawn apart from walls: otherwise a gable seen side-on is just a block.
        String roofId = style.map(p -> p.palette().get(PaletteSlot.ROOF).id()).orElse(null);
        style.ifPresent(p -> {
            sb.append("Shape: ").append(p.storeys()).append(p.storeys() == 1 ? " storey" : " storeys")
                    .append(", walls ").append(p.wallHeight()).append(" high, ")
                    .append(p.hasGableRoof() ? "gable roof rising " + p.roofPitch() + " per step" : "flat roof")
                    .append(p.overhang() > 0 ? ", eaves out " + p.overhang() : "")
                    .append(String.format(Locale.ROOT, ", about %d glass blocks per 10 blocks of wall",
                            Math.round(p.windowRatio() * 10)))
                    .append('\n');
            sb.append("Materials by role: ");
            for (PaletteSlot slot : new PaletteSlot[]{PaletteSlot.FOUNDATION, PaletteSlot.WALL,
                    PaletteSlot.BEAM, PaletteSlot.ROOF, PaletteSlot.WINDOW, PaletteSlot.DOOR}) {
                sb.append(slot.name().toLowerCase(Locale.ROOT)).append('=')
                        .append(p.palette().get(slot).id().replace("minecraft:", "")).append("  ");
            }
            sb.append('\n');
        });

        sb.append("Most used: ");
        s.blockCounts().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(6)
                .forEach(e -> sb.append(e.getKey().replace("minecraft:", "")).append(" ×")
                        .append(e.getValue()).append("  "));
        sb.append('\n');

        int step = Math.max(1, (int) Math.ceil(Math.max(s.sizeX(), s.sizeZ()) / (double) MAX_DRAW));
        if (step > 1) {
            sb.append("(drawings below show one character per ").append(step).append(" blocks)\n");
        }
        int floor = groundFloor(s);
        sb.append("Front, seen from the south:\n").append(elevation(s, step, roofId));
        sb.append("Plan at height ").append(floor).append(", north at the top:\n")
                .append(plan(s, floor, step, roofId));
        return sb.toString();
    }

    /** South face: for each column, the first block met looking north. */
    static String elevation(Schematic s, int step, String roofId) {
        StringBuilder out = new StringBuilder();
        boolean started = false;
        for (int y = s.sizeY() - 1; y >= 0; y -= step) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < s.sizeX(); x += step) {
                char c = ' ';
                for (int z = s.sizeZ() - 1; z >= 0 && c == ' '; z--) {
                    c = glyph(s.at(x, y, z), roofId);
                }
                row.append(c);
            }
            String line = row.toString().stripTrailing();
            if (line.isEmpty() && !started) continue; // Skip empty sky above the roof.
            started = true;
            out.append("  |").append(line).append('\n');
        }
        return out.toString();
    }

    static String plan(Schematic s, int y, int step, String roofId) {
        StringBuilder out = new StringBuilder();
        for (int z = 0; z < s.sizeZ(); z += step) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < s.sizeX(); x += step) {
                row.append(glyph(s.at(x, y, z), roofId));
            }
            out.append("  |").append(row.toString().stripTrailing()).append('\n');
        }
        return out.toString();
    }

    /**
     * The first layer that is mostly open: above the foundation and the floor slab,
     * where the walls, the door and the rooms show. A solid layer draws as a filled
     * rectangle and tells the model nothing.
     */
    private static int groundFloor(Schematic s) {
        int area = s.sizeX() * s.sizeZ();
        int lowest = -1;
        for (int y = 0; y < s.sizeY(); y++) {
            int solid = 0;
            for (int z = 0; z < s.sizeZ(); z++) {
                for (int x = 0; x < s.sizeX(); x++) {
                    if (!s.at(x, y, z).isAir()) solid++;
                }
            }
            if (solid == 0) continue;
            if (lowest < 0) lowest = y;
            if (solid < area * 0.6) return y;
        }
        return Math.max(0, lowest);
    }

    static char glyph(BlockRef ref, String roofId) {
        if (ref.isAir()) return ' ';
        String id = ref.id();
        if (id.equals(roofId)) return '^';
        if (id.contains("glass")) return 'o';
        if (id.endsWith("_door")) return 'D';
        if (id.contains("lantern") || id.contains("torch") || id.contains("glowstone")
                || id.contains("shroomlight") || id.contains("campfire")) return '*';
        if (id.endsWith("_stairs")) return '/';
        if (id.endsWith("_slab")) return '-';
        if (id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_wood")) return 'I';
        if (id.endsWith("_fence") || id.endsWith("_wall") || id.endsWith("_bars")
                || id.endsWith("_fence_gate")) return '+';
        if (id.endsWith("leaves")) return '&';
        if (id.endsWith("water")) return '~';
        return '#';
    }
}
