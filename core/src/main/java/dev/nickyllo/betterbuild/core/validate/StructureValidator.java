package dev.nickyllo.betterbuild.core.validate;

import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a compiled structure before a single block is placed in the world.
 *
 * <p>This is the safety net that lets a language model design buildings: the model can
 * be wrong, but nothing it produces reaches the world without passing these rules
 * first. When a check fails the Architect asks for a targeted correction rather than
 * regenerating the whole design.
 */
public final class StructureValidator {

    /** Reporting more than this many positions per issue is noise. */
    private static final int MAX_REPORTED = 16;

    public List<ValidationIssue> validate(CompiledStructure structure, Box allowed) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (structure.solidCount() == 0) {
            issues.add(ValidationIssue.error(ValidationIssue.Kind.EMPTY,
                    "The design produced no blocks.", List.of()));
            return issues;
        }

        checkBounds(structure, allowed, issues);
        checkFloating(structure, issues);
        checkEntrance(structure, issues);
        return issues;
    }

    private void checkBounds(CompiledStructure s, Box allowed, List<ValidationIssue> issues) {
        List<Vec3i> outside = new ArrayList<>();
        for (var e : s.blocks().entrySet()) {
            if (!e.getValue().isAir() && !allowed.contains(e.getKey())) {
                outside.add(e.getKey());
                if (outside.size() >= MAX_REPORTED) break;
            }
        }
        if (!outside.isEmpty()) {
            issues.add(ValidationIssue.error(ValidationIssue.Kind.OUT_OF_BOUNDS,
                    "The design spills outside the marked plot.", outside));
        }
    }

    /**
     * A block is floating when nothing touches it on any of its six sides. Diagonal
     * contact does not count — a build held together only at the corners reads as
     * broken in game even though it technically stands.
     */
    private void checkFloating(CompiledStructure s, List<ValidationIssue> issues) {
        int groundY = s.extent().min().y();
        List<Vec3i> floating = new ArrayList<>();

        for (var e : s.blocks().entrySet()) {
            if (e.getValue().isAir()) continue;
            Vec3i p = e.getKey();
            if (p.y() == groundY) continue;

            boolean supported = false;
            for (Direction d : Direction.values()) {
                if (s.isSolidAt(p.offset(d, 1))) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                floating.add(p);
                if (floating.size() >= MAX_REPORTED) break;
            }
        }
        if (!floating.isEmpty()) {
            issues.add(ValidationIssue.error(ValidationIssue.Kind.FLOATING,
                    floating.size() + " block(s) would hang in mid-air.", floating));
        }
    }

    /**
     * Flood-fills the interior from a cleared position and checks it escapes the
     * structure's bounding box. If the fill is fully contained, the player has been
     * walled in — which is exactly the failure a generated building tends to produce.
     */
    private void checkEntrance(CompiledStructure s, List<ValidationIssue> issues) {
        Vec3i start = findInteriorAir(s);
        if (start == null) {
            issues.add(ValidationIssue.warning(ValidationIssue.Kind.NO_ENTRANCE,
                    "The design has no interior space to enter.", List.of()));
            return;
        }

        Box extent = s.extent();
        Set<Vec3i> seen = new HashSet<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        boolean escaped = false;

        while (!queue.isEmpty() && seen.size() < 100_000) {
            Vec3i p = queue.poll();
            if (!extent.contains(p)) {
                escaped = true;
                break;
            }
            for (Direction d : Direction.HORIZONTAL) {
                Vec3i n = p.offset(d, 1);
                if (!s.isSolidAt(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
            Vec3i up = p.up(1);
            if (!s.isSolidAt(up) && seen.add(up)) queue.add(up);
        }

        if (!escaped) {
            issues.add(ValidationIssue.error(ValidationIssue.Kind.UNREACHABLE_INTERIOR,
                    "The interior is sealed — there is no way in or out.", List.of(start)));
        }
    }

    /** An air position that the structure explicitly cleared, i.e. a room, not the sky. */
    private Vec3i findInteriorAir(CompiledStructure s) {
        for (var e : s.blocks().entrySet()) {
            if (e.getValue().isAir()) {
                return e.getKey();
            }
        }
        return null;
    }
}
