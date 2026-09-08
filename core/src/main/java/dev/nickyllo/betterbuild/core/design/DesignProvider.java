package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;

/**
 * Source of designs. Implemented by the procedural generator (offline, always works)
 * and by the model-backed provider.
 *
 * <p>Keeping this an interface is what makes the mod usable without an API key: the
 * Architect stays a working entity that builds from the local repertoire, and only
 * loses the ability to invent new things.
 */
public interface DesignProvider {

    Blueprint design(DesignRequest request) throws DesignException;

    /** Shown to the player when choosing or falling back. */
    String name();

    /** False when the provider cannot run right now (no key, no network, quota spent). */
    default boolean isAvailable() {
        return true;
    }

    class DesignException extends Exception {
        public DesignException(String message) {
            super(message);
        }

        public DesignException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
