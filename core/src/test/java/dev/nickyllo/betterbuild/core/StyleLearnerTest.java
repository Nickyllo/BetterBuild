package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.*;
import dev.nickyllo.betterbuild.core.build.BuildPlanner;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.learn.StyleLearner;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teaching the Architect by example: build something, let him look at it, and check
 * that what he builds next carries the lesson.
 */
class StyleLearnerTest {

    private final BlueprintCompiler compiler = new BlueprintCompiler();
    private final BuildPlanner planner = new BuildPlanner();
    private final StyleLearner learner = new StyleLearner();
    private final ProceduralDesignProvider provider = new ProceduralDesignProvider();

    /** Places a structure into a world, exactly as the mod does in game. */
    private void place(FakeWorld world, CompiledStructure s, Vec3i origin) {
        for (BuildStep step : planner.plan(s).steps()) {
            world.setBlock(step.pos().add(origin), step.block());
        }
    }

    private Palette deepslatePalette() {
        return Palette.builder()
                .set(PaletteSlot.FOUNDATION, BlockRef.of("minecraft:cobblestone"))
                .set(PaletteSlot.WALL, BlockRef.of("minecraft:spruce_planks"))
                .set(PaletteSlot.BEAM, BlockRef.of("minecraft:spruce_log"))
                .set(PaletteSlot.FLOOR, BlockRef.of("minecraft:spruce_planks"))
                .set(PaletteSlot.ROOF, BlockRef.of("minecraft:deepslate_tiles"))
                .set(PaletteSlot.WINDOW, BlockRef.of("minecraft:glass_pane"))
                .set(PaletteSlot.DOOR, BlockRef.of("minecraft:spruce_door"))
                .set(PaletteSlot.LIGHT, BlockRef.of("minecraft:lantern"))
                .build();
    }

    /** A hand-made building, the kind a player would put up themselves. */
    private Blueprint handMade(Palette palette, int wallHeight, int pitch) {
        Box building = new Box(new Vec3i(1, 1, 1), new Vec3i(11, wallHeight, 11));
        return Blueprint.named("cabaña").size(13, wallHeight + 10, 13).palette(palette)
                .add(new Element.SolidFill(
                        new Box(new Vec3i(1, 0, 1), new Vec3i(11, 0, 11)), PaletteSlot.FOUNDATION))
                .add(new Element.PerimeterWall(building, PaletteSlot.WALL, 1))
                .add(new Element.CornerPosts(building, PaletteSlot.BEAM))
                .add(new Element.Opening(building, dev.nickyllo.betterbuild.core.geom.Direction.SOUTH,
                        5, 1, 2, 1, Element.OpeningKind.DOOR))
                .add(new Element.Opening(building, dev.nickyllo.betterbuild.core.geom.Direction.NORTH,
                        3, 1, 2, 2, Element.OpeningKind.WINDOW))
                .add(new Element.Opening(building, dev.nickyllo.betterbuild.core.geom.Direction.NORTH,
                        7, 1, 2, 2, Element.OpeningKind.WINDOW))
                .add(new Element.GableRoof(
                        new Box(new Vec3i(1, wallHeight + 1, 1), new Vec3i(11, wallHeight + 1, 11)),
                        Element.Axis.X, pitch, 1, PaletteSlot.ROOF))
                .add(new Element.Marker(new Vec3i(2, wallHeight - 1, 2), PaletteSlot.LIGHT))
                .build();
    }

    @Test
    void recognisesTheMaterialsOfABuildingItDidNotMake() {
        var world = new FakeWorld(64, "minecraft:plains");
        var origin = new Vec3i(100, 64, 100);
        place(world, compiler.compile(handMade(deepslatePalette(), 6, 1)), origin);

        StyleProfile style = learner.learn(world,
                Box.between(origin, origin.add(13, 20, 13)), "cabaña").orElseThrow();

        assertEquals("minecraft:spruce_planks", style.palette().get(PaletteSlot.WALL).id(),
                "should spot what the walls are made of");
        assertEquals("minecraft:deepslate_tiles", style.palette().get(PaletteSlot.ROOF).id(),
                "should spot the roofing separately from the walls");
        assertEquals("minecraft:spruce_log", style.palette().get(PaletteSlot.BEAM).id(),
                "exposed timber should be told apart from plain walls");
        assertEquals("minecraft:glass_pane", style.palette().get(PaletteSlot.WINDOW).id());
        assertEquals("minecraft:spruce_door", style.palette().get(PaletteSlot.DOOR).id());
        assertEquals("minecraft:lantern", style.palette().get(PaletteSlot.LIGHT).id());
    }

    @Test
    void readsTheShapeNotJustTheMaterials() {
        var world = new FakeWorld(64, "minecraft:plains");
        var origin = new Vec3i(0, 64, 0);
        place(world, compiler.compile(handMade(deepslatePalette(), 9, 1)), origin);

        StyleProfile style = learner.learn(world,
                Box.between(origin, origin.add(13, 25, 13)), "casa alta").orElseThrow();

        assertEquals(11, style.footprintWidth(), "footprint should match what was built");
        assertEquals(9, style.wallHeight(), "walls built 9 high must be read back as 9");
        assertEquals(2, style.storeys(), "9-high walls are two storeys");
        assertTrue(style.hasGableRoof(), "a pitched roof should not be read as flat");
    }

    @Test
    void readsWallHeightExactlyWhateverTheBuildingsHeight() {
        // The bug this guards: walls were measured against a line at two thirds of the
        // total height, so a 5-high house came back as 8 and a 6-high one as 8 too.
        for (int walls : new int[]{4, 5, 6, 9, 13}) {
            var world = new FakeWorld(64, "minecraft:plains");
            var origin = new Vec3i(0, 64, 0);
            place(world, compiler.compile(handMade(deepslatePalette(), walls, 1)), origin);
            StyleProfile style = learner.learn(world,
                    Box.between(origin, origin.add(13, walls + 12, 13)), "h" + walls).orElseThrow();
            assertEquals(walls, style.wallHeight(), "walls " + walls + " high");
            assertTrue(style.hasGableRoof());
        }
    }

    @Test
    void tellsAFlatRoofFromAPitchedOne() {
        var world = new FakeWorld(64, "minecraft:plains");
        var origin = new Vec3i(0, 64, 0);
        Box building = new Box(new Vec3i(1, 1, 1), new Vec3i(11, 6, 11));
        var flat = Blueprint.named("plana").size(13, 12, 13).palette(deepslatePalette())
                .add(new Element.SolidFill(
                        new Box(new Vec3i(1, 0, 1), new Vec3i(11, 0, 11)), PaletteSlot.FOUNDATION))
                .add(new Element.PerimeterWall(building, PaletteSlot.WALL, 1))
                .add(new Element.FlatRoof(
                        new Box(new Vec3i(1, 7, 1), new Vec3i(11, 7, 11)), 0, PaletteSlot.ROOF))
                .build();
        place(world, compiler.compile(flat), origin);

        StyleProfile style = learner.learn(world,
                Box.between(origin, origin.add(13, 20, 13)), "plana").orElseThrow();
        assertFalse(style.hasGableRoof(), "a flat roof must not be reported as pitched");
    }

    @Test
    void refusesToLearnFromAlmostNothing() {
        var world = new FakeWorld(64, "minecraft:plains");
        world.setBlock(new Vec3i(0, 65, 0), BlockRef.of("minecraft:stone"));

        assertEquals(Optional.empty(),
                learner.learn(world, Box.between(new Vec3i(0, 64, 0), new Vec3i(5, 70, 5)), "nada"),
                "a couple of blocks are not a building");
    }

    @Test
    void whatItLearnsShowsUpInWhatItBuildsNext() throws Exception {
        var world = new FakeWorld(64, "minecraft:desert");
        var origin = new Vec3i(0, 64, 0);
        place(world, compiler.compile(handMade(deepslatePalette(), 6, 1)), origin);

        StyleProfile style = learner.learn(world,
                Box.between(origin, origin.add(13, 20, 13)), "cabaña").orElseThrow();

        // The plot is desert. Left alone he would build in sandstone; taught, he should not.
        Box plot = Box.between(new Vec3i(200, 64, 200), new Vec3i(216, 94, 216));
        var survey = SiteSurvey.of(world, plot);

        var taught = provider.design(DesignRequest.inStyle("una casa", survey, style));
        assertEquals("minecraft:spruce_planks", taught.palette().get(PaletteSlot.WALL).id(),
                "the lesson should beat the biome default");
        assertEquals("minecraft:deepslate_tiles", taught.palette().get(PaletteSlot.ROOF).id());

        var untaught = provider.design(DesignRequest.fresh("una casa", survey));
        assertEquals("minecraft:sandstone", untaught.palette().get(PaletteSlot.WALL).id(),
                "without teaching he should still fall back to the biome");

        // And the taught design must still be buildable, not just pretty on paper.
        var errors = new StructureValidator()
                .validate(compiler.compile(taught), taught.size()).stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .toList();
        assertTrue(errors.isEmpty(), "a learned design still has to stand up: " + errors);
    }
}
