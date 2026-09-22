package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.FakeWorld;
import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.schematic.Schematic;
import dev.nickyllo.betterbuild.core.schematic.SchematicLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What the model is told: the lesson only helps if it actually reaches the prompt. */
class ClaudePromptTest {

    private static Schematic tower() {
        Schematic.Builder b = Schematic.builder("torre_medieval", 5, 12, 5);
        for (int y = 0; y < 12; y++)
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 5; z++)
                    if (x == 0 || z == 0 || x == 4 || z == 4) b.set(x, y, z, BlockRef.of("minecraft:stone_bricks"));
        return b.build();
    }

    private static SiteSurvey survey() {
        return SiteSurvey.of(new FakeWorld(64, "minecraft:plains"),
                Box.between(new Vec3i(0, 64, 0), new Vec3i(19, 94, 19)));
    }

    @Test
    void referencesAndModulesReachTheModel() {
        String prompt = ClaudeDesignProvider.userPrompt(
                DesignRequest.fresh("una torre de vigilancia", survey()),
                SchematicLibrary.of(List.of(tower())));

        assertTrue(prompt.contains("Reference buildings made by people"), prompt);
        assertTrue(prompt.contains("### torre_medieval"), "the tower should be shown as a reference");
        assertTrue(prompt.contains("stone_bricks"), "with its materials");
        assertTrue(prompt.contains("Modules you can place with MODULE"), prompt);
        assertTrue(prompt.contains("- torre_medieval: 5 wide, 12 tall, 5 deep"), prompt);
    }

    @Test
    void aLearnedStyleIsSpelledOut() {
        Palette p = Palette.builder().set(PaletteSlot.WALL, BlockRef.of("minecraft:spruce_planks")).build();
        StyleProfile style = new StyleProfile("mi cabaña", p, 6, 1, 1, 1, 0.2, 11, 11, Map.of());
        String prompt = ClaudeDesignProvider.userPrompt(
                DesignRequest.inStyle("una casa", survey(), style), SchematicLibrary.empty());

        assertTrue(prompt.contains("taught you a style"), prompt);
        assertTrue(prompt.contains("wall=minecraft:spruce_planks"), prompt);
        assertTrue(prompt.contains("about 2 glass blocks per 10 blocks of wall"), prompt);
        assertFalse(prompt.contains("Modules you can place"), "no library, no module list");
    }
}
