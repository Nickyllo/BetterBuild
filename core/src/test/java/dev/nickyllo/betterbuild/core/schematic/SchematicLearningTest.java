package dev.nickyllo.betterbuild.core.schematic;

import dev.nickyllo.betterbuild.core.FakeWorld;
import dev.nickyllo.betterbuild.core.blueprint.*;
import dev.nickyllo.betterbuild.core.build.BuildPlanner;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.design.BlueprintDto;
import dev.nickyllo.betterbuild.core.design.BlueprintMapper;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.learn.StyleLearner;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Teaching the Architect with schematics: files in, better buildings out. */
class SchematicLearningTest {

    private final BlueprintCompiler compiler = new BlueprintCompiler();

    /** A spruce-and-slate cabin, the kind of thing a player saves in Litematica. */
    private CompiledStructure cabin() {
        Palette p = Palette.builder()
                .set(PaletteSlot.FOUNDATION, BlockRef.of("minecraft:cobblestone"))
                .set(PaletteSlot.WALL, BlockRef.of("minecraft:spruce_planks"))
                .set(PaletteSlot.BEAM, BlockRef.of("minecraft:spruce_log"))
                .set(PaletteSlot.ROOF, BlockRef.of("minecraft:deepslate_tiles"))
                .set(PaletteSlot.WINDOW, BlockRef.of("minecraft:glass_pane"))
                .set(PaletteSlot.DOOR, BlockRef.of("minecraft:spruce_door"))
                .set(PaletteSlot.LIGHT, BlockRef.of("minecraft:lantern")).build();
        Box b = new Box(new Vec3i(1, 1, 1), new Vec3i(11, 6, 11));
        return compiler.compile(Blueprint.named("cabaña").size(13, 16, 13).palette(p)
                .add(new Element.SolidFill(new Box(new Vec3i(1, 0, 1), new Vec3i(11, 0, 11)), PaletteSlot.FOUNDATION))
                .add(new Element.PerimeterWall(b, PaletteSlot.WALL, 1))
                .add(new Element.CornerPosts(b, PaletteSlot.BEAM))
                .add(new Element.Opening(b, Direction.SOUTH, 5, 1, 2, 1, Element.OpeningKind.DOOR))
                .add(new Element.Opening(b, Direction.NORTH, 3, 1, 2, 2, Element.OpeningKind.WINDOW))
                .add(new Element.Opening(b, Direction.NORTH, 7, 1, 2, 2, Element.OpeningKind.WINDOW))
                .add(new Element.GableRoof(new Box(new Vec3i(1, 7, 1), new Vec3i(11, 7, 11)), Element.Axis.X, 1, 1, PaletteSlot.ROOF))
                .add(new Element.Marker(new Vec3i(2, 5, 2), PaletteSlot.LIGHT)).build());
    }

    private Path saveAsLitematic(CompiledStructure s, Path dir, String name) throws IOException {
        Path file = dir.resolve(name + ".litematic");
        Schematic schem = SchematicCapture.fromStructure(s);
        SchematicFiles.writeLitematic(renamed(schem, name), file, "Nickyllo", "", 4671);
        return file;
    }

    private static Schematic renamed(Schematic s, String name) {
        Schematic.Builder b = Schematic.builder(name, s.sizeX(), s.sizeY(), s.sizeZ());
        s.forEachBlock((p, r) -> b.set(p.x(), p.y(), p.z(), r));
        return b.build();
    }

    @Test
    void learnsAStyleFromALitematicaFileAndBuildsInIt(@TempDir Path dir) throws Exception {
        Schematic loaded = SchematicFiles.read(saveAsLitematic(cabin(), dir, "mi_cabana"));

        StyleProfile style = new StyleLearner()
                .learn(new SchematicWorldView(loaded), loaded.box(), loaded.name()).orElseThrow();
        assertEquals("minecraft:spruce_planks", style.palette().get(PaletteSlot.WALL).id());
        assertEquals("minecraft:deepslate_tiles", style.palette().get(PaletteSlot.ROOF).id());
        assertEquals("minecraft:spruce_log", style.palette().get(PaletteSlot.BEAM).id());

        // Desert plot: untaught he picks sandstone; taught by the file, spruce.
        var world = new FakeWorld(64, "minecraft:desert");
        var survey = SiteSurvey.of(world, Box.between(new Vec3i(0, 64, 0), new Vec3i(14, 94, 12)));
        Blueprint built = new ProceduralDesignProvider().design(DesignRequest.inStyle("una casa", survey, style));
        assertEquals("minecraft:spruce_planks", built.palette().get(PaletteSlot.WALL).id());

        var errors = new StructureValidator().validate(compiler.compile(built), built.size()).stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR).toList();
        assertTrue(errors.isEmpty(), "what he learned must still produce a sound building: " + errors);
    }

    @Test
    void doorsNowHaveBothHalvesFacingIn() {
        CompiledStructure s = cabin();
        // Door on the south face (z=11) at x = 1 + 5, sill 1: y=2 lower, y=3 upper.
        BlockRef lower = s.at(new Vec3i(6, 2, 11));
        BlockRef upper = s.at(new Vec3i(6, 3, 11));
        assertEquals("minecraft:spruce_door", lower.id());
        assertEquals("lower", lower.property("half"));
        assertEquals("upper", upper.property("half"));
        assertEquals("north", lower.property("facing"), "a south-wall door faces inward, north");
    }

    @Test
    void moduleIsPlacedRotatedWithItsStatesTurned() {
        // An L-shaped porch step: stairs facing north at the front-left.
        Schematic porch = Schematic.builder("porche", 3, 1, 2)
                .set(0, 0, 0, BlockRef.parse("minecraft:oak_stairs[facing=north,half=bottom]"))
                .set(1, 0, 0, BlockRef.of("minecraft:oak_planks"))
                .set(2, 0, 0, BlockRef.of("minecraft:oak_planks"))
                .set(0, 0, 1, BlockRef.of("minecraft:oak_log").with("axis", "x"))
                .build();

        var s = compiler.compile(Blueprint.named("con porche").size(20, 5, 20)
                .add(new Element.Module(porch, new Vec3i(10, 1, 10), 1)).build());

        // A quarter turn: 3x2 becomes 2x3, and (0,0) -> (sizeZ-1-z, x) = (1, 0).
        BlockRef stair = s.at(new Vec3i(11, 1, 10));
        assertEquals("minecraft:oak_stairs", stair.id());
        assertEquals("east", stair.property("facing"), "north turned a quarter clockwise is east");
        assertEquals("z", s.at(new Vec3i(10, 1, 10)).property("axis"), "a log along x now runs along z");
        assertEquals(4, s.solidCount());
    }

    @Test
    void fourQuarterTurnsGiveBackTheSameSchematic() {
        Schematic s = SchematicCapture.fromStructure(cabin());
        Schematic r = s.rotated(1).rotated(1).rotated(1).rotated(1);
        for (int y = 0; y < s.sizeY(); y++)
            for (int z = 0; z < s.sizeZ(); z++)
                for (int x = 0; x < s.sizeX(); x++)
                    assertEquals(s.at(x, y, z), r.at(x, y, z), "at " + x + "," + y + "," + z);
    }

    @Test
    void rotatesConnectionsAxesAndSignRotation() {
        BlockRef fence = BlockRef.of("minecraft:oak_fence").with("north", "true").with("east", "false")
                .with("south", "false").with("west", "true");
        BlockRef turned = StateRotation.rotate(fence, 1);
        assertEquals("true", turned.property("east"), "the north connection turns to face east");
        assertEquals("true", turned.property("north"), "the west connection turns to face north");
        assertEquals("false", turned.property("south"));

        assertEquals("12", StateRotation.rotate(BlockRef.of("minecraft:oak_sign").with("rotation", "8"), 1).property("rotation"));
        assertEquals("y", StateRotation.rotate(BlockRef.of("minecraft:oak_log").with("axis", "y"), 1).property("axis"));
    }

    @Test
    void theModelIsShownTheBuildingNotJustItsBlocks() {
        String digest = SchematicDigest.of(SchematicCapture.fromStructure(cabin()));
        assertTrue(digest.contains("spruce_planks"), digest);
        assertTrue(digest.contains("gable roof"), "the roof shape should be spelled out:\n" + digest);
        assertTrue(digest.contains("Front, seen from the south"), digest);
        assertTrue(digest.contains("D"), "the door should be visible in the elevation:\n" + digest);
        assertTrue(digest.contains("I"), "the corner posts should show as timber:\n" + digest);
    }

    @Test
    void libraryLoadsAFolderAndExplainsWhatItCouldNotRead(@TempDir Path dir) throws IOException {
        saveAsLitematic(cabin(), dir, "casa 1");
        saveAsLitematic(cabin(), dir, "casa 2");
        Files.createDirectories(dir.resolve("medieval"));
        saveAsLitematic(cabin(), dir.resolve("medieval"), "Torre_Medieval");
        Files.writeString(dir.resolve("roto.litematic"), "esto no es un schematic");
        Files.writeString(dir.resolve("notas.txt"), "ignorado");

        SchematicLibrary lib = SchematicLibrary.load(dir);
        assertEquals(3, lib.all().size(), "casa 1 and casa 2 must stay two schematics: " + lib.names());
        assertEquals(1, lib.failures().size(), "the broken file is reported, not fatal");
        assertTrue(lib.get("torre medieval").isPresent(), "found regardless of case and underscores");
        assertEquals("Torre_Medieval", lib.relevantTo("una torre para el castillo", 1).get(0).name(),
                "a request for a tower should reach for the tower first");
    }

    @Test
    void mapperPlacesKnownModulesAndDropsUnknownOnes() throws Exception {
        Schematic porch = Schematic.builder("porche", 3, 2, 2)
                .set(0, 0, 0, BlockRef.of("minecraft:oak_planks")).build();
        BlueprintMapper mapper = new BlueprintMapper(SchematicLibrary.of(List.of(porch)));

        var wall = new BlueprintDto.ElementDto("SOLID_FILL", new BlueprintDto.BoxDto(0, 0, 0, 4, 0, 4),
                "FOUNDATION", 0, 0, null, 0, 0, 0, 0, null, null, 0, 0, null, 0);
        // Asked for at the far edge: it must be pulled back inside the 10-wide plot.
        var known = new BlueprintDto.ElementDto("MODULE", new BlueprintDto.BoxDto(9, 1, 0, 9, 1, 0),
                null, 0, 0, null, 0, 0, 0, 0, null, null, 0, 0, "Porche", 0);
        var unknown = new BlueprintDto.ElementDto("MODULE", new BlueprintDto.BoxDto(0, 1, 0, 0, 1, 0),
                null, 0, 0, null, 0, 0, 0, 0, null, null, 0, 0, "castillo_inventado", 0);

        Blueprint bp = mapper.toBlueprint(new BlueprintDto("casa", null, List.of(wall, known, unknown)),
                Box.atOrigin(10, 10, 10));

        assertEquals(2, bp.elements().size(), "the invented module is dropped, the rest kept");
        Element.Module m = (Element.Module) bp.elements().get(1);
        assertEquals(7, m.at().x(), "a 3-wide module at x=9 is pulled back to fit in 10");
    }

    @Test
    void exportsWhatIsStandingTrimmedToTheBuilding() {
        FakeWorld world = new FakeWorld(0, "minecraft:plains");
        Vec3i at = new Vec3i(50, 5, 50);
        for (BuildStep step : new BuildPlanner().plan(cabin()).steps()) {
            world.setBlock(step.pos().add(at), step.block());
        }
        // FakeWorld's ground is solid below y=1; look only above it.
        Schematic s = SchematicCapture.fromWorld(world,
                Box.between(at.add(-5, 0, -5), at.add(20, 25, 20)), "exportada").orElseThrow();
        assertEquals(13, s.sizeX(), "trimmed to the cabin plus its eaves, not the whole search box");
        assertEquals(cabin().solidCount(), s.blockCount());
    }
}
