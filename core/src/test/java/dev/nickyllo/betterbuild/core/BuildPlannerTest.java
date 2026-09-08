package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.*;
import dev.nickyllo.betterbuild.core.build.*;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.geom.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildPlannerTest {

    private final BlueprintCompiler compiler = new BlueprintCompiler();
    private final BuildPlanner planner = new BuildPlanner();

    @Test
    void buildsFromTheBottomUp() {
        var bp = Blueprint.named("tower").size(3, 8, 3)
                .add(new Element.SolidFill(Box.atOrigin(3, 8, 3), PaletteSlot.WALL))
                .build();

        var steps = planner.plan(compiler.compile(bp)).steps();
        int lastY = Integer.MIN_VALUE;
        for (BuildStep step : steps) {
            assertTrue(step.pos().y() >= lastY,
                    "layer order broken: went from y=" + lastY + " to y=" + step.pos().y());
            lastY = step.pos().y();
        }
    }

    @Test
    void clearsBeforePlacingWithinALayer() {
        Box building = Box.atOrigin(7, 4, 7);
        var bp = Blueprint.named("cleared").size(7, 4, 7)
                .add(new Element.PerimeterWall(building, PaletteSlot.WALL, 1))
                .add(new Element.Opening(building, Direction.SOUTH, 3, 1, 2, 1,
                        Element.OpeningKind.VOID))
                .build();

        var steps = planner.plan(compiler.compile(bp)).steps();
        for (int i = 1; i < steps.size(); i++) {
            BuildStep prev = steps.get(i - 1);
            BuildStep cur = steps.get(i);
            if (prev.pos().y() == cur.pos().y() && !prev.isClearing()) {
                assertFalse(cur.isClearing(),
                        "a clearing step must not follow a placement in the same layer");
            }
        }
    }

    @Test
    void everyStepHasSomewhereToStand() {
        var bp = Blueprint.named("house").size(9, 6, 9)
                .add(new Element.SolidFill(new Box(Vec3i.ZERO, new Vec3i(8, 0, 8)), PaletteSlot.FOUNDATION))
                .add(new Element.PerimeterWall(new Box(new Vec3i(0, 1, 0), new Vec3i(8, 5, 8)),
                        PaletteSlot.WALL, 1))
                .build();

        for (BuildStep step : planner.plan(compiler.compile(bp)).steps()) {
            assertNotNull(step.standAt(), "step " + step.index() + " has no standing spot");
            assertNotEquals(step.pos(), step.standAt(),
                    "the builder cannot stand inside the block it is placing");
        }
    }

    @Test
    void groundLevelWorkNeedsNoScaffolding() {
        var bp = Blueprint.named("patio").size(9, 1, 9)
                .add(new Element.FloorSlab(new Box(Vec3i.ZERO, new Vec3i(8, 0, 8)), PaletteSlot.FLOOR))
                .build();

        assertTrue(planner.plan(compiler.compile(bp)).scaffolding().isEmpty(),
                "a flat patio should never call for scaffolding");
    }

    @Test
    void shortfallReportsOnlyWhatIsMissing() {
        var bp = Blueprint.named("wall").size(5, 3, 5)
                .add(new Element.SolidFill(Box.atOrigin(5, 1, 5), PaletteSlot.WALL))
                .build();
        BuildPlan plan = planner.plan(compiler.compile(bp));

        int needed = plan.materials().get("minecraft:oak_planks");
        var missing = BuildPlanner.shortfall(plan, Map.of("minecraft:oak_planks", needed - 4));
        assertEquals(4, missing.get("minecraft:oak_planks"));

        assertTrue(BuildPlanner.shortfall(plan, Map.of("minecraft:oak_planks", needed)).isEmpty(),
                "carrying enough means nothing is missing");
    }
}
