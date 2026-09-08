package dev.nickyllo.betterbuild.core.compile;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result of compiling a blueprint: every position the structure touches, mapped
 * to the block that belongs there. Positions are still relative to the blueprint
 * origin — translating into the world happens at build time.
 *
 * <p>Air is represented explicitly (not omitted) because "this position must be
 * cleared" is different from "this position is not part of the build".
 */
public final class CompiledStructure {

    private final Map<Vec3i, BlockRef> blocks;
    private final String name;

    CompiledStructure(String name, Map<Vec3i, BlockRef> blocks) {
        this.name = name;
        this.blocks = Collections.unmodifiableMap(blocks);
    }

    public String name() {
        return name;
    }

    public Map<Vec3i, BlockRef> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    /** Positions that receive a real block, i.e. excluding the cleared ones. */
    public long solidCount() {
        return blocks.values().stream().filter(b -> !b.isAir()).count();
    }

    public BlockRef at(Vec3i pos) {
        return blocks.get(pos);
    }

    public boolean isSolidAt(Vec3i pos) {
        BlockRef ref = blocks.get(pos);
        return ref != null && !ref.isAir();
    }

    /** Tight bounding box of everything the structure occupies. */
    public Box extent() {
        if (blocks.isEmpty()) {
            return new Box(Vec3i.ZERO, Vec3i.ZERO);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vec3i p : blocks.keySet()) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        return new Box(new Vec3i(minX, minY, minZ), new Vec3i(maxX, maxY, maxZ));
    }

    /**
     * How many of each block the build consumes — what the Architect asks you for
     * when his inventory falls short. Sorted by id so the chat message is stable.
     */
    public Map<String, Integer> billOfMaterials() {
        Map<String, Integer> bom = new TreeMap<>();
        for (BlockRef ref : blocks.values()) {
            if (!ref.isAir()) {
                bom.merge(ref.id(), 1, Integer::sum);
            }
        }
        return bom;
    }

    /** Same structure moved by {@code offset}; used to anchor a design in the world. */
    public CompiledStructure translated(Vec3i offset) {
        Map<Vec3i, BlockRef> moved = new LinkedHashMap<>(blocks.size());
        for (var e : blocks.entrySet()) {
            moved.put(e.getKey().add(offset), e.getValue());
        }
        return new CompiledStructure(name, moved);
    }
}
