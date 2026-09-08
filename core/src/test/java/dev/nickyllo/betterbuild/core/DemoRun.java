package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.build.BuildPlan;
import dev.nickyllo.betterbuild.core.build.BuildPlanner;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;

/** Runs the whole core pipeline and prints the result, so the geometry can be eyeballed. */
public final class DemoRun {

    public static void main(String[] args) throws Exception {
        var world = new FakeWorld(64, "minecraft:taiga");
        var plot = Box.between(new Vec3i(0, 64, 0), new Vec3i(12, 82, 10));
        var survey = SiteSurvey.of(world, plot);

        System.out.println("SITE     " + survey.describe() + "  [" + survey.biome() + "]");

        Blueprint bp = new ProceduralDesignProvider()
                .design(DesignRequest.fresh("a two storey tavern", survey));
        System.out.println("DESIGN   \"" + bp.name() + "\"  " + bp.elements().size() + " elements");

        CompiledStructure s = new BlueprintCompiler().compile(bp);
        System.out.println("COMPILE  " + s.solidCount() + " blocks, "
                + (s.size() - s.solidCount()) + " cleared, extent " + s.extent());

        var issues = new StructureValidator().validate(s, bp.size());
        System.out.println("VALIDATE " + (issues.isEmpty() ? "clean" : issues.size() + " issue(s)"));
        issues.forEach(i -> System.out.println("         " + i));

        BuildPlan plan = new BuildPlanner().plan(s);
        System.out.println("PLAN     " + plan.size() + " steps, "
                + plan.scaffolding().size() + " scaffold positions");
        System.out.println("MATERIAL " + plan.materials());

        System.out.println("\n--- vertical slice through the middle (z=" + s.extent().sizeZ() / 2 + ") ---");
        render(s, s.extent().sizeZ() / 2);

        System.out.println("\n--- cross section across the roof pitch (x="
                + (s.extent().min().x() + s.extent().sizeX() / 2) + ") ---");
        renderX(s, s.extent().min().x() + s.extent().sizeX() / 2);

        System.out.println("\n--- floor plan at y=2 ---");
        renderPlan(s, 2);

        // Execute the plan into the fake world, exactly as the entity would.
        for (BuildStep step : plan.steps()) {
            world.setBlock(step.pos(), step.block());
        }
        System.out.println("\nBUILT    " + world.placedCount() + " blocks standing in the world");
    }

    private static void render(CompiledStructure s, int z) {
        Box e = s.extent();
        for (int y = e.max().y(); y >= e.min().y(); y--) {
            StringBuilder line = new StringBuilder(String.format("y=%2d |", y));
            for (int x = e.min().x(); x <= e.max().x(); x++) {
                line.append(glyph(s, new Vec3i(x, y, z)));
            }
            System.out.println(line);
        }
    }

    private static void renderX(CompiledStructure s, int x) {
        Box e = s.extent();
        for (int y = e.max().y(); y >= e.min().y(); y--) {
            StringBuilder line = new StringBuilder(String.format("y=%2d |", y));
            for (int z = e.min().z(); z <= e.max().z(); z++) {
                line.append(glyph(s, new Vec3i(x, y, z)));
            }
            System.out.println(line);
        }
    }

    private static void renderPlan(CompiledStructure s, int y) {
        Box e = s.extent();
        for (int z = e.min().z(); z <= e.max().z(); z++) {
            StringBuilder line = new StringBuilder("     |");
            for (int x = e.min().x(); x <= e.max().x(); x++) {
                line.append(glyph(s, new Vec3i(x, y, z)));
            }
            System.out.println(line);
        }
    }

    private static char glyph(CompiledStructure s, Vec3i p) {
        var ref = s.at(p);
        if (ref == null) return ' ';
        if (ref.isAir()) return '.';
        String id = ref.id();
        if (id.contains("door")) return 'D';
        if (id.contains("glass")) return 'o';
        if (id.contains("lantern") || id.contains("torch")) return '*';
        if (id.contains("log")) return 'I';
        if (id.contains("slab")) return '-';
        if (id.contains("tile") || id.contains("brick")) return '=';
        if (id.contains("cobble")) return '%';
        return '#';
    }
}
