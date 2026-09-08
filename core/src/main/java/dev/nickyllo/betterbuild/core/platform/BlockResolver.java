package dev.nickyllo.betterbuild.core.platform;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import java.util.Optional;

/**
 * Resolves a version-independent block id against the running game.
 *
 * <p>This one interface is what lets a single blueprint target every Minecraft
 * version: a design asking for a block that does not exist yet in 1.20.1 walks its
 * fallback chain until something real is found, instead of failing the build.
 */
public interface BlockResolver {

    /** True when this exact id exists in the running version's block registry. */
    boolean exists(String blockId);

    /** First id in the chain that exists, or empty if none of them do. */
    default Optional<String> resolve(BlockRef ref) {
        for (String candidate : ref.resolutionChain()) {
            if (exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Resolution with a guaranteed answer, for slots that must not be left empty. */
    default String resolveOr(BlockRef ref, String lastResort) {
        return resolve(ref).orElse(lastResort);
    }
}
