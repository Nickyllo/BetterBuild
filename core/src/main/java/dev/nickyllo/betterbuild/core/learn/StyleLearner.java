package dev.nickyllo.betterbuild.core.learn;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Looks at a building in the world and works out the taste behind it.
 *
 * <p>This is how the Architect is taught without a model and without a network: the
 * player builds something by hand, points at it, and the rules below turn it into a
 * {@link StyleProfile} that new designs are then built from.
 *
 * <p>Everything here is a heuristic over block counts and heights. It is deliberately
 * forgiving — a half-finished or unusual building yields a partial profile rather than
 * an error, because refusing to learn is worse than learning roughly.
 */
public final class StyleLearner {

    /** Below this many blocks there is nothing to learn from. */
    private static final int MIN_BLOCKS = 40;

    /** Blocks whose role is obvious from the name, whatever height they sit at. */
    private static final Set<String> LIGHT_WORDS =
            Set.of("lantern", "torch", "glowstone", "sea_lantern", "shroomlight", "campfire");

    public Optional<StyleProfile> learn(WorldView world, Box region, String name) {
        Map<Vec3i, String> solids = new HashMap<>();
        region.forEach(pos -> {
            String id = world.blockIdAt(pos);
            if (id != null && !id.equals("minecraft:air")) {
                solids.put(pos, id);
            }
        });
        if (solids.size() < MIN_BLOCKS) {
            return Optional.empty();
        }

        int groundY = solids.keySet().stream().mapToInt(Vec3i::y).min().orElse(0);
        int topY = solids.keySet().stream().mapToInt(Vec3i::y).max().orElse(0);
        int height = topY - groundY;

        // A roof is whatever sits in the upper third of a building tall enough to have one.
        int roofFrom = height >= 4 ? groundY + (int) Math.ceil(height * 0.66) : topY + 1;

        Map<String, Integer> all = new HashMap<>();
        Map<String, Integer> atGround = new HashMap<>();
        Map<String, Integer> inWalls = new HashMap<>();
        Map<String, Integer> inRoof = new HashMap<>();
        int windows = 0;
        String windowId = null;
        String doorId = null;
        String lightId = null;

        for (var e : solids.entrySet()) {
            String id = e.getValue();
            int y = e.getKey().y();
            all.merge(id, 1, Integer::sum);

            String lower = id.toLowerCase(Locale.ROOT);
            if (lower.contains("glass")) {
                windows++;
                windowId = id;
                continue;
            }
            if (lower.contains("door")) {
                doorId = id;
                continue;
            }
            if (LIGHT_WORDS.stream().anyMatch(lower::contains)) {
                lightId = id;
                continue;
            }

            if (y == groundY) {
                atGround.merge(id, 1, Integer::sum);
            } else if (y >= roofFrom) {
                inRoof.merge(id, 1, Integer::sum);
            } else {
                inWalls.merge(id, 1, Integer::sum);
            }
        }

        String foundation = commonest(atGround);
        String wall = commonest(inWalls);
        String roof = commonest(inRoof);
        String beam = timberIn(inWalls, wall);

        // A building whose walls and roof are the same block still has a roof; fall
        // back rather than leaving the slot empty.
        if (wall == null) wall = foundation;
        if (roof == null) roof = wall;
        if (foundation == null) foundation = wall;

        Palette.Builder palette = Palette.builder();
        set(palette, PaletteSlot.FOUNDATION, foundation);
        set(palette, PaletteSlot.WALL, wall);
        set(palette, PaletteSlot.FLOOR, wall);
        set(palette, PaletteSlot.ROOF, roof);
        set(palette, PaletteSlot.BEAM, beam != null ? beam : wall);
        set(palette, PaletteSlot.WINDOW, windowId);
        set(palette, PaletteSlot.DOOR, doorId);
        set(palette, PaletteSlot.LIGHT, lightId);

        Box footprint = footprintOf(solids.keySet(), groundY);
        int wallHeight = wallHeightOf(solids, groundY, roofFrom, roof);
        int pitch = pitchOf(solids, roofFrom, roof);
        int overhang = overhangOf(solids, roofFrom, roof, footprint);
        int storeys = Math.max(1, Math.round(wallHeight / 4f));
        int perimeter = 2 * (footprint.sizeX() + footprint.sizeZ());
        double windowRatio = perimeter == 0 ? 0 : (double) windows / perimeter;

        return Optional.of(new StyleProfile(
                name, palette.build(), Math.max(3, wallHeight), storeys, pitch, overhang,
                windowRatio, footprint.sizeX(), footprint.sizeZ(), all));
    }

    /** Height from the ground to where roof material starts. */
    private int wallHeightOf(Map<Vec3i, String> solids, int groundY, int roofFrom, String roof) {
        int lowestRoof = Integer.MAX_VALUE;
        for (var e : solids.entrySet()) {
            if (e.getValue().equals(roof) && e.getKey().y() >= roofFrom) {
                lowestRoof = Math.min(lowestRoof, e.getKey().y());
            }
        }
        if (lowestRoof == Integer.MAX_VALUE) {
            return solids.keySet().stream().mapToInt(Vec3i::y).max().orElse(groundY) - groundY;
        }
        return lowestRoof - groundY;
    }

    /**
     * Rise per step inward across the roof. A flat roof spans one height and yields 0;
     * a gable spans several and yields how fast it climbs.
     */
    private int pitchOf(Map<Vec3i, String> solids, int roofFrom, String roof) {
        List<Vec3i> roofBlocks = new ArrayList<>();
        for (var e : solids.entrySet()) {
            if (e.getValue().equals(roof) && e.getKey().y() >= roofFrom) {
                roofBlocks.add(e.getKey());
            }
        }
        if (roofBlocks.isEmpty()) {
            return 0;
        }
        int minY = roofBlocks.stream().mapToInt(Vec3i::y).min().orElse(0);
        int maxY = roofBlocks.stream().mapToInt(Vec3i::y).max().orElse(0);
        int rise = maxY - minY;
        if (rise == 0) {
            return 0;
        }
        int minZ = roofBlocks.stream().mapToInt(Vec3i::z).min().orElse(0);
        int maxZ = roofBlocks.stream().mapToInt(Vec3i::z).max().orElse(0);
        int minX = roofBlocks.stream().mapToInt(Vec3i::x).min().orElse(0);
        int maxX = roofBlocks.stream().mapToInt(Vec3i::x).max().orElse(0);
        int span = Math.min(maxX - minX, maxZ - minZ);
        int steps = Math.max(1, span / 2);
        return Math.max(1, Math.round((float) rise / steps));
    }

    /** How far the roof sticks out past the walls below it. */
    private int overhangOf(Map<Vec3i, String> solids, int roofFrom, String roof, Box walls) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (var e : solids.entrySet()) {
            if (e.getValue().equals(roof) && e.getKey().y() >= roofFrom) {
                minX = Math.min(minX, e.getKey().x());
                maxX = Math.max(maxX, e.getKey().x());
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return 0;
        }
        int out = Math.max(walls.min().x() - minX, maxX - walls.max().x());
        return Math.max(0, Math.min(3, out));
    }

    /** Bounding box of the lowest course, i.e. the building's actual footprint. */
    private Box footprintOf(Set<Vec3i> positions, int groundY) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vec3i p : positions) {
            if (p.y() <= groundY + 1) {
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return new Box(Vec3i.ZERO, Vec3i.ZERO);
        }
        return new Box(new Vec3i(minX, groundY, minZ), new Vec3i(maxX, groundY, maxZ));
    }

    /** Exposed timber: a log or stem used in the walls, distinct from the wall block. */
    private String timberIn(Map<String, Integer> walls, String wallBlock) {
        String best = null;
        int bestCount = 0;
        for (var e : walls.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            boolean timber = lower.contains("_log") || lower.contains("_stem")
                    || lower.contains("stripped");
            if (timber && !e.getKey().equals(wallBlock) && e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private String commonest(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void set(Palette.Builder b, PaletteSlot slot, String id) {
        if (id != null) {
            b.set(slot, BlockRef.of(id));
        }
    }
}
