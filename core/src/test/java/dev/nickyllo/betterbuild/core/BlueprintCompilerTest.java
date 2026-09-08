package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.blueprint.*;
import dev.nickyllo.betterbuild.core.compile.*;
import dev.nickyllo.betterbuild.core.geom.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintCompilerTest {

    private final BlueprintCompiler compiler = new BlueprintCompiler();

    @Test
    void perimeterWallIsHollow() {
        var bp = Blueprint.named("box").size(5, 4, 5)
                .add(new Element.PerimeterWall(Box.atOrigin(5, 4, 5), PaletteSlot.WALL, 1))
                .build();
        var s = compiler.compile(bp);

        assertTrue(s.isSolidAt(new Vec3i(0, 1, 0)), "corner should be wall");
        assertTrue(s.isSolidAt(new Vec3i(2, 1, 0)), "edge should be wall");
        assertFalse(s.isSolidAt(new Vec3i(2, 1, 2)), "centre must stay hollow");
    }

    @Test
    void thickerWallEatsIntoTheInterior() {
        var bp = Blueprint.named("thick").size(7, 3, 7)
                .add(new Element.PerimeterWall(Box.atOrigin(7, 3, 7), PaletteSlot.WALL, 2))
                .build();
        var s = compiler.compile(bp);

        assertTrue(s.isSolidAt(new Vec3i(1, 0, 1)), "second ring is part of a 2-thick wall");
        assertFalse(s.isSolidAt(new Vec3i(3, 0, 3)), "centre still hollow");
    }

    @Test
    void openingCutsThroughAWallOfAnyThickness() {
        Box building = Box.atOrigin(9, 5, 9);
        var bp = Blueprint.named("door").size(9, 5, 9)
                .add(new Element.PerimeterWall(building, PaletteSlot.WALL, 3))
                .add(new Element.Opening(building, Direction.NORTH, 4, 1, 2, 0,
                        Element.OpeningKind.VOID))
                .build();
        var s = compiler.compile(bp);

        // The opening discovers the wall depth from what is already placed.
        for (int depth = 0; depth < 3; depth++) {
            assertFalse(s.isSolidAt(new Vec3i(4, 0, depth)),
                    "wall should be pierced at depth " + depth);
        }
    }

    @Test
    void laterElementsOverwriteEarlierOnes() {
        var bp = Blueprint.named("overwrite").size(3, 3, 3)
                .add(new Element.SolidFill(Box.atOrigin(3, 3, 3), PaletteSlot.WALL))
                .add(new Element.Marker(new Vec3i(1, 1, 1), PaletteSlot.LIGHT))
                .build();
        var s = compiler.compile(bp);

        assertTrue(s.at(new Vec3i(1, 1, 1)).id().contains("lantern"));
    }

    @Test
    void gableRoofRisesTowardTheRidgeAndHasNoGaps() {
        Box footprint = new Box(new Vec3i(0, 5, 0), new Vec3i(6, 5, 6));
        var bp = Blueprint.named("roof").size(7, 12, 7)
                .add(new Element.GableRoof(footprint, Element.Axis.X, 2, 0, PaletteSlot.ROOF))
                .build();
        var s = compiler.compile(bp);

        // Ridge (z=3) sits higher than the eaves (z=0).
        int eaveTop = topSolidY(s, 0, 0);
        int ridgeTop = topSolidY(s, 0, 3);
        assertTrue(ridgeTop > eaveTop, "ridge must be above the eaves");

        // A pitch of 2 steps up two blocks at a time. Each column must reach down to
        // meet its neighbour, or you could see the sky through the side of the roof.
        for (int z = 1; z <= 3; z++) {
            int below = topSolidY(s, 0, z - 1);
            int top = topSolidY(s, 0, z);
            for (int y = below + 1; y <= top; y++) {
                assertTrue(s.isSolidAt(new Vec3i(0, y, z)),
                        "gap in the roof at z=" + z + ", y=" + y);
            }
        }
    }

    @Test
    void blockIdsGetTheDefaultNamespace() {
        assertEquals("minecraft:stone", BlockRef.of("stone").id());
    }

    @Test
    void fallbackChainKeepsOrder() {
        var ref = BlockRef.of("minecraft:tuff_bricks", "minecraft:stone_bricks", "minecraft:cobblestone");
        assertEquals(
                java.util.List.of("minecraft:tuff_bricks", "minecraft:stone_bricks", "minecraft:cobblestone"),
                ref.resolutionChain());
    }

    private int topSolidY(CompiledStructure s, int x, int z) {
        int top = Integer.MIN_VALUE;
        for (var e : s.blocks().entrySet()) {
            var p = e.getKey();
            if (p.x() == x && p.z() == z && !e.getValue().isAir()) {
                top = Math.max(top, p.y());
            }
        }
        return top;
    }
}
