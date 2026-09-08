package dev.nickyllo.betterbuild.core.agent;

/**
 * What the Architect is doing. Each state maps to visible behaviour in the world —
 * the player should be able to tell these apart by looking, without opening anything.
 */
public enum ArchitectState {
    /** Following the player or standing where told. */
    IDLE,
    /** Turned toward the player, listening. */
    LISTENING,
    /** Walking to the marked plot. The design is generated during this time. */
    WALKING_TO_SITE,
    /** Circling the plot, reading the ground. */
    SURVEYING,
    /** Standing still, thinking. */
    DESIGNING,
    /** Showing the full-size outline and waiting for a yes. */
    PROPOSING,
    /** Placing blocks. */
    BUILDING,
    /** Out of material — either asking, or off fetching it. */
    GATHERING,
    /** Stuck, and has said why. */
    BLOCKED,
    /** Finished; the work is standing. */
    DONE;

    public boolean isBusy() {
        return this == WALKING_TO_SITE || this == SURVEYING || this == DESIGNING
                || this == BUILDING || this == GATHERING;
    }
}
