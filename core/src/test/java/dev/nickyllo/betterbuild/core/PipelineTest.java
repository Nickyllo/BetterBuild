package dev.nickyllo.betterbuild.core;

import dev.nickyllo.betterbuild.core.agent.ArchitectBrain;
import dev.nickyllo.betterbuild.core.agent.ArchitectState;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.ChatSink;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: every plot size the procedural provider accepts must produce a sound build. */
class PipelineTest {

    private final ProceduralDesignProvider provider = new ProceduralDesignProvider();

    @Test
    void everyReasonablePlotProducesAValidBuilding() throws Exception {
        var world = new FakeWorld(64, "minecraft:plains");

        for (int w = 7; w <= 20; w += 3) {
            for (int d = 7; d <= 20; d += 3) {
                Box plot = Box.between(new Vec3i(0, 64, 0), new Vec3i(w - 1, 90, d - 1));
                var survey = SiteSurvey.of(world, plot);
                var bp = provider.design(DesignRequest.fresh("a house", survey));
                var s = new BlueprintCompiler().compile(bp);

                List<ValidationIssue> errors = new StructureValidator().validate(s, bp.size())
                        .stream()
                        .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                        .toList();

                assertTrue(errors.isEmpty(),
                        "plot " + w + "x" + d + " produced: " + errors);
                assertTrue(s.solidCount() > 0, "plot " + w + "x" + d + " built nothing");
            }
        }
    }

    @Test
    void tooSmallAPlotIsRefusedWithAnExplanation() {
        var world = new FakeWorld(64, "minecraft:plains");
        var survey = SiteSurvey.of(world, Box.between(new Vec3i(0, 64, 0), new Vec3i(2, 70, 2)));

        var ex = assertThrows(Exception.class,
                () -> provider.design(DesignRequest.fresh("a house", survey)));
        assertTrue(ex.getMessage().contains("too small"), "message should say why: " + ex.getMessage());
    }

    @Test
    void brainWalksTheWholeCycleAndBuildsEveryBlock() {
        var chat = new RecordingChat();
        var world = new FakeWorld(64, "minecraft:plains");
        var brain = new ArchitectBrain(provider, provider, chat);

        Box plot = Box.between(new Vec3i(0, 64, 0), new Vec3i(12, 84, 12));
        assertEquals(ArchitectState.IDLE, brain.state());

        brain.onPlotMarked(plot);
        assertEquals(ArchitectState.LISTENING, brain.state());

        brain.onCommission("a tavern");
        assertEquals(ArchitectState.WALKING_TO_SITE, brain.state(),
                "he should set off before the design exists");

        brain.onArrivedAtSite(SiteSurvey.of(world, plot));
        assertEquals(ArchitectState.SURVEYING, brain.state());

        brain.onSurveyComplete();
        assertEquals(ArchitectState.PROPOSING, brain.state());
        assertTrue(brain.blueprint().isPresent());

        var plan = brain.plan().orElseThrow();
        brain.onApproved(plan.materials());
        assertEquals(ArchitectState.BUILDING, brain.state());

        int placed = 0;
        for (BuildStep step = brain.nextStep().orElse(null);
             step != null;
             step = brain.nextStep().orElse(null)) {
            world.setBlock(step.pos(), step.block());
            placed++;
        }

        assertEquals(plan.size(), placed, "every planned step should have been executed");
        assertEquals(ArchitectState.DONE, brain.state());
    }

    @Test
    void missingMaterialsPauseTheBuildInsteadOfFailing() {
        var chat = new RecordingChat();
        var world = new FakeWorld(64, "minecraft:plains");
        var brain = new ArchitectBrain(provider, provider, chat);
        Box plot = Box.between(new Vec3i(0, 64, 0), new Vec3i(12, 84, 12));

        brain.onPlotMarked(plot);
        brain.onCommission("a tavern");
        brain.onArrivedAtSite(SiteSurvey.of(world, plot));
        brain.onSurveyComplete();

        brain.onApproved(Map.of());
        assertEquals(ArchitectState.GATHERING, brain.state());
        assertTrue(brain.nextStep().isEmpty(), "he must not build while short of material");
        assertTrue(chat.said.stream().anyMatch(m -> m.contains("short of")),
                "he should say what he needs: " + chat.said);

        brain.onMaterialsReady();
        assertEquals(ArchitectState.BUILDING, brain.state());
        assertTrue(brain.nextStep().isPresent());
    }

    @Test
    void interruptingMidBuildStopsHimAndKeepsProgress() {
        var chat = new RecordingChat();
        var world = new FakeWorld(64, "minecraft:plains");
        var brain = new ArchitectBrain(provider, provider, chat);
        Box plot = Box.between(new Vec3i(0, 64, 0), new Vec3i(12, 84, 12));

        brain.onPlotMarked(plot);
        brain.onCommission("a tavern");
        brain.onArrivedAtSite(SiteSurvey.of(world, plot));
        brain.onSurveyComplete();
        brain.onApproved(brain.plan().orElseThrow().materials());

        for (int i = 0; i < 50; i++) {
            brain.nextStep();
        }
        double before = brain.progress();
        assertTrue(before > 0 && before < 1);

        brain.onInterrupted();
        assertEquals(ArchitectState.LISTENING, brain.state());
        assertTrue(brain.nextStep().isEmpty(), "a stopped builder places nothing");
        assertEquals(before, brain.progress(), "progress is kept while he listens");
    }

    private static final class RecordingChat implements ChatSink {
        final List<String> said = new ArrayList<>();

        @Override public void say(String message) { said.add(message); }
        @Override public void warn(String message) { said.add("WARN " + message); }
    }
}
