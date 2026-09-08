package dev.nickyllo.betterbuild.core.blueprint;

import java.util.List;

/**
 * A block referenced by namespaced id, never by a Minecraft class.
 *
 * <p>This is what makes one blueprint usable across every supported game version.
 * {@code fallbacks} is an ordered substitution chain: the platform layer takes the
 * first id that exists in the running version. A blueprint asking for
 * {@code tuff_bricks} (1.21+) still builds on 1.20.1 as {@code stone_bricks}
 * instead of failing or leaving a hole.
 */
public record BlockRef(String id, List<String> fallbacks) {

    public static final BlockRef AIR = new BlockRef("minecraft:air", List.of());

    public BlockRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("block id must not be blank");
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        fallbacks = List.copyOf(fallbacks);
    }

    public static BlockRef of(String id) {
        return new BlockRef(id, List.of());
    }

    /** {@code of("minecraft:tuff_bricks", "minecraft:stone_bricks")} — first that resolves wins. */
    public static BlockRef of(String id, String... fallbacks) {
        return new BlockRef(id, List.of(fallbacks));
    }

    public boolean isAir() {
        return id.equals("minecraft:air");
    }

    /** Every id to try, in order, including the primary one. */
    public List<String> resolutionChain() {
        if (fallbacks.isEmpty()) {
            return List.of(id);
        }
        var chain = new java.util.ArrayList<String>(fallbacks.size() + 1);
        chain.add(id);
        chain.addAll(fallbacks);
        return List.copyOf(chain);
    }

    @Override
    public String toString() {
        return id;
    }
}
