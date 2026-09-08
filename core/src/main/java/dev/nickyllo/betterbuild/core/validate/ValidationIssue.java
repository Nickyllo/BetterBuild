package dev.nickyllo.betterbuild.core.validate;

import dev.nickyllo.betterbuild.core.geom.Vec3i;
import java.util.List;

/**
 * Something wrong with a compiled structure. Errors block the build; warnings are
 * reported to the player but do not stop the Architect.
 */
public record ValidationIssue(Kind kind, Severity severity, String message, List<Vec3i> positions) {

    public ValidationIssue {
        positions = List.copyOf(positions);
    }

    public static ValidationIssue error(Kind kind, String message, List<Vec3i> positions) {
        return new ValidationIssue(kind, Severity.ERROR, message, positions);
    }

    public static ValidationIssue warning(Kind kind, String message, List<Vec3i> positions) {
        return new ValidationIssue(kind, Severity.WARNING, message, positions);
    }

    public enum Severity { ERROR, WARNING }

    public enum Kind {
        /** Blocks outside the area the player marked out. */
        OUT_OF_BOUNDS,
        /** Solid blocks with no neighbour at all — they would look like they hover. */
        FLOATING,
        /** No door or opening reaches the outside. */
        NO_ENTRANCE,
        /** There is an enclosed space no player can reach. */
        UNREACHABLE_INTERIOR,
        /** Nothing was produced. */
        EMPTY
    }

    @Override
    public String toString() {
        String where = positions.isEmpty() ? "" : " at " + positions.get(0)
                + (positions.size() > 1 ? " (+" + (positions.size() - 1) + " more)" : "");
        return severity + " " + kind + ": " + message + where;
    }
}
