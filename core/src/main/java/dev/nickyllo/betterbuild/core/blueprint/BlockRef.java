package dev.nickyllo.betterbuild.core.blueprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A block referenced by namespaced id, never by a Minecraft class.
 *
 * <p>This is what makes one blueprint usable across every supported game version.
 * {@code fallbacks} is an ordered substitution chain: the platform layer takes the
 * first id that exists in the running version. A blueprint asking for
 * {@code tuff_bricks} (1.21+) still builds on 1.20.1 as {@code stone_bricks}
 * instead of failing or leaving a hole.
 *
 * <p>{@code properties} carries the block state — which way a stair faces, which half
 * of a door this is, which sides a fence connects on. Schematics are full of these and
 * a building read from one looks wrong without them. The platform applies the ones
 * the resolved block understands and ignores the rest, so a property never makes a
 * block fail to place.
 */
public record BlockRef(String id, List<String> fallbacks, Map<String, String> properties) {

    public static final BlockRef AIR = new BlockRef("minecraft:air", List.of());

    public BlockRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("block id must not be blank");
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        fallbacks = List.copyOf(fallbacks);
        // Sorted, so two refs with the same state compare equal and print identically.
        properties = properties == null || properties.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(properties));
    }

    public BlockRef(String id, List<String> fallbacks) {
        this(id, fallbacks, Map.of());
    }

    public static BlockRef of(String id) {
        return new BlockRef(id, List.of());
    }

    /** {@code of("minecraft:tuff_bricks", "minecraft:stone_bricks")} — first that resolves wins. */
    public static BlockRef of(String id, String... fallbacks) {
        return new BlockRef(id, List.of(fallbacks));
    }

    /**
     * Parses the block state notation used by commands and Sponge schematics:
     * {@code minecraft:oak_stairs[facing=north,half=bottom]}.
     */
    public static BlockRef parse(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("empty block state");
        }
        int open = state.indexOf('[');
        if (open < 0) {
            return of(state.trim());
        }
        int close = state.lastIndexOf(']');
        if (close < open) {
            throw new IllegalArgumentException("unclosed block state: " + state);
        }
        Map<String, String> props = new TreeMap<>();
        String inner = state.substring(open + 1, close).trim();
        if (!inner.isEmpty()) {
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("bad property '" + pair + "' in " + state);
                }
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new BlockRef(state.substring(0, open).trim(), List.of(), props);
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air") || id.equals("minecraft:structure_void");
    }

    /**
     * Something a player can walk through once placed. Doors count: without that, a
     * building whose only way in is a door would be reported as a sealed box.
     */
    public boolean isPassable() {
        return isAir() || id.endsWith("_door") || id.endsWith("_fence_gate");
    }

    public String property(String key) {
        return properties.get(key);
    }

    /** Same block with one property set, e.g. {@code door.with("half", "upper")}. */
    public BlockRef with(String key, String value) {
        Map<String, String> copy = new TreeMap<>(properties);
        copy.put(key, value);
        return new BlockRef(id, fallbacks, copy);
    }

    /** Same block and fallbacks with a whole new set of properties. */
    public BlockRef withProperties(Map<String, String> props) {
        return new BlockRef(id, fallbacks, props);
    }

    /** Every id to try, in order, including the primary one. */
    public List<String> resolutionChain() {
        if (fallbacks.isEmpty()) {
            return List.of(id);
        }
        List<String> chain = new ArrayList<>(fallbacks.size() + 1);
        chain.add(id);
        chain.addAll(fallbacks);
        return List.copyOf(chain);
    }

    /** Inverse of {@link #parse}: {@code id[key=value,...]}, or just the id. */
    public String stateString() {
        if (properties.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var e : properties.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return stateString();
    }
}
