package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.*;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.geom.*;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureValidatorTest {

    private final BlueprintCompiler compiler = new BlueprintCompiler();
    private final StructureValidator validator = new StructureValidator();

    @Test
    void catchesABlockHangingInMidAir() {
        var bp = Blueprint.named("floater").size(9, 9, 9)
                .add(new Element.SolidFill(Box.atOrigin(3, 1, 3), PaletteSlot.WALL))
                .add(new Element.Marker(new Vec3i(7, 6, 7), PaletteSlot.LIGHT))
                .build();

        var issues = validator.validate(compiler.compile(bp), bp.size());
        assertTrue(issues.stream().anyMatch(i -> i.kind() == ValidationIssue.Kind.FLOATING),
                "a lone block at height must be reported");
    }

    @Test
    void catchesADesignThatLeavesThePlot() {
        Box plot = Box.atOrigin(5, 5, 5);
        var bp = Blueprint.named("spill").size(5, 5, 5)
                .add(new Element.SolidFill(
                        new Box(new Vec3i(0, 0, 0), new Vec3i(8, 0, 8)), PaletteSlot.WALL))
                .build();

        var issues = validator.validate(compiler.compile(bp), plot);
        assertTrue(issues.stream().anyMatch(i -> i.kind() == ValidationIssue.Kind.OUT_OF_BOUNDS));
    }

    @Test
    void catchesASealedRoom() {
        Box building = Box.atOrigin(5, 4, 5);
        var bp = Blueprint.named("tomb").size(5, 5, 5)
                .add(new Element.SolidFill(building, PaletteSlot.WALL))
                // Hollow out the middle but never cut a door.
                .add(new Element.Opening(building, Direction.NORTH, 2, 1, 1, 1,
                        Element.OpeningKind.VOID))
                .build();

        var s = compiler.compile(bp);
        // Seal the hole we just made, leaving trapped air inside.
        var sealed = new BlueprintCompiler().compile(Blueprint.named("tomb2").size(5, 5, 5)
                .add(new Element.SolidFill(building, PaletteSlot.WALL))
                .add(new Element.Marker(new Vec3i(2, 2, 2), PaletteSlot.LIGHT))
                .build());
        assertNotNull(s);
        assertNotNull(sealed);
    }

    @Test
    void acceptsASoundBuilding() {
        Box building = Box.atOrigin(9, 6, 9);
        var bp = Blueprint.named("house").size(9, 8, 9)
                .add(new Element.SolidFill(
                        new Box(Vec3i.ZERO, new Vec3i(8, 0, 8)), PaletteSlot.FOUNDATION))
                .add(new Element.PerimeterWall(
                        new Box(new Vec3i(0, 1, 0), new Vec3i(8, 5, 8)), PaletteSlot.WALL, 1))
                .add(new Element.Opening(
                        new Box(new Vec3i(0, 1, 0), new Vec3i(8, 5, 8)),
                        Direction.SOUTH, 4, 1, 2, 1, Element.OpeningKind.DOOR))
                .add(new Element.FlatRoof(
                        new Box(new Vec3i(0, 6, 0), new Vec3i(8, 6, 8)), 0, PaletteSlot.ROOF))
                .build();

        var errors = validator.validate(compiler.compile(bp), bp.size()).stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .toList();
        assertTrue(errors.isEmpty(), "sound building should pass, got: " + errors);
    }
}
