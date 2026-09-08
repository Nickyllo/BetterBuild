package dev.nickyllo.betterbuild.core.agent;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.build.BuildPlan;
import dev.nickyllo.betterbuild.core.build.BuildPlanner;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.design.DesignProvider;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.platform.ChatSink;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Architect's decision-making, with no Minecraft in sight.
 *
 * <p>The entity class in each platform module owns the body — pathfinding, animation,
 * ticking — and calls into this for every decision. That split is why the same
 * behaviour runs identically on Fabric 1.21 and Forge 1.20 without being written twice,
 * and why it can be tested in milliseconds instead of by launching a game.
 */
public final class ArchitectBrain {

    private final DesignProvider primary;
    private final DesignProvider fallback;
    private final ChatSink chat;
    private final BlueprintCompiler compiler = new BlueprintCompiler();
    private final StructureValidator validator = new StructureValidator();
    private final BuildPlanner planner = new BuildPlanner();

    private ArchitectState state = ArchitectState.IDLE;
    private Box plot;
    private SiteSurvey survey;
    private Blueprint blueprint;
    private CompiledStructure structure;
    private BuildPlan plan;
    private int stepIndex;
    private String pendingPrompt;

    public ArchitectBrain(DesignProvider primary, DesignProvider fallback, ChatSink chat) {
        this.primary = primary;
        this.fallback = fallback;
        this.chat = chat;
    }

    public ArchitectState state() {
        return state;
    }

    public Optional<Blueprint> blueprint() {
        return Optional.ofNullable(blueprint);
    }

    public Optional<BuildPlan> plan() {
        return Optional.ofNullable(plan);
    }

    public double progress() {
        return plan == null ? 0.0 : plan.progress(stepIndex);
    }

    /** The player marked out a plot with the wand. */
    public void onPlotMarked(Box plot) {
        this.plot = plot;
        chat.say("A " + plot.sizeX() + " by " + plot.sizeZ()
                + " plot. Tell me what you want there.");
        state = ArchitectState.LISTENING;
    }

    /**
     * The player asked for something. The Architect starts walking immediately —
     * the design is produced while he is on his way, so the model's latency is spent
     * on an animation the player is already watching rather than on a loading bar.
     */
    public void onCommission(String prompt) {
        if (plot == null) {
            chat.say("Show me where first — mark it out with the wand.");
            return;
        }
        this.pendingPrompt = prompt;
        chat.say("On my way. Give me a moment to look at it properly.");
        state = ArchitectState.WALKING_TO_SITE;
    }

    /** The body reports it has reached the plot. */
    public void onArrivedAtSite(SiteSurvey survey) {
        if (state != ArchitectState.WALKING_TO_SITE) {
            return;
        }
        this.survey = survey;
        state = ArchitectState.SURVEYING;
        chat.say(survey.describe() + ".");
        if (survey.isSteep()) {
            chat.say("That is a real slope. I will put the low side on stilts.");
        }
    }

    /** Survey animation finished; produce the design. */
    public void onSurveyComplete() {
        if (state != ArchitectState.SURVEYING) {
            return;
        }
        state = ArchitectState.DESIGNING;
        DesignRequest request = blueprint == null
                ? DesignRequest.fresh(pendingPrompt, survey)
                : DesignRequest.refine(pendingPrompt, survey, blueprint);

        Optional<Blueprint> designed = tryDesign(request);
        if (designed.isEmpty()) {
            state = ArchitectState.BLOCKED;
            return;
        }
        acceptDesign(designed.get());
    }

    /**
     * Runs the design through the model, falling back to the local repertoire when
     * that is not possible. A failure here never leaves the Architect inert — the
     * worst case is that he can only build what he already knows.
     */
    private Optional<Blueprint> tryDesign(DesignRequest request) {
        if (primary.isAvailable()) {
            try {
                return Optional.of(primary.design(request));
            } catch (DesignProvider.DesignException e) {
                chat.warn("I could not work that out (" + e.getMessage() + "). "
                        + "Falling back to something I already know.");
            }
        } else {
            chat.say("I cannot dream up anything new right now. I will build from what I know.");
        }
        try {
            return Optional.of(fallback.design(request));
        } catch (DesignProvider.DesignException e) {
            chat.warn(e.getMessage());
            return Optional.empty();
        }
    }

    /** Compiles, validates and plans a design; on success the proposal goes up. */
    private void acceptDesign(Blueprint bp) {
        CompiledStructure compiled = compiler.compile(bp);
        List<ValidationIssue> issues = validator.validate(compiled, bp.size());
        List<ValidationIssue> errors = issues.stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            // The design does not stand up. Never put it in the world.
            chat.warn("That design does not hold together (" + errors.get(0).kind()
                    + "). Let me rework it.");
            state = ArchitectState.BLOCKED;
            return;
        }

        this.blueprint = bp;
        this.structure = compiled;
        this.plan = planner.plan(compiled);
        this.stepIndex = 0;
        state = ArchitectState.PROPOSING;
        chat.say("Here is what I have in mind: " + bp.name() + ", "
                + compiled.solidCount() + " blocks. Say the word and I will start.");
    }

    /** The player said yes. */
    public void onApproved(Map<String, Integer> carriedMaterials) {
        if (state != ArchitectState.PROPOSING) {
            return;
        }
        Map<String, Integer> missing = BuildPlanner.shortfall(plan, carriedMaterials);
        if (!missing.isEmpty()) {
            state = ArchitectState.GATHERING;
            chat.say("I am short of " + describeShortfall(missing)
                    + ". Shall I fetch it, or will you?");
            return;
        }
        state = ArchitectState.BUILDING;
        chat.say("Starting now.");
    }

    /** Material has arrived (given by the player, or gathered). */
    public void onMaterialsReady() {
        if (state == ArchitectState.GATHERING) {
            state = ArchitectState.BUILDING;
            chat.say("That will do. Back to it.");
        }
    }

    /**
     * The next block to place, or empty when the build is finished. The body calls
     * this on its own schedule, which is what makes build speed a setting rather
     * than a property of the plan.
     */
    public Optional<BuildStep> nextStep() {
        if (state != ArchitectState.BUILDING || plan == null) {
            return Optional.empty();
        }
        if (stepIndex >= plan.size()) {
            state = ArchitectState.DONE;
            chat.say("Finished. Come and have a look.");
            return Optional.empty();
        }
        return Optional.of(plan.steps().get(stepIndex++));
    }

    /** The player spoke while the Architect was working: stop and listen. */
    public void onInterrupted() {
        if (state.isBusy()) {
            chat.say("I will stop.");
        }
        state = ArchitectState.LISTENING;
    }

    /**
     * A correction after an interruption or on the proposal. Everything built so far
     * stays; only the design changes, and the build resumes from where it paused.
     */
    public void onRefinement(String prompt) {
        if (blueprint == null) {
            onCommission(prompt);
            return;
        }
        this.pendingPrompt = prompt;
        state = ArchitectState.DESIGNING;
        tryDesign(DesignRequest.refine(prompt, survey, blueprint)).ifPresentOrElse(
                this::acceptDesign,
                () -> state = ArchitectState.BLOCKED);
    }

    public void onBlockedResolved() {
        if (state == ArchitectState.BLOCKED) {
            state = ArchitectState.LISTENING;
        }
    }

    private String describeShortfall(Map<String, Integer> missing) {
        return missing.entrySet().stream()
                .limit(3)
                .map(e -> e.getValue() + " " + e.getKey().replace("minecraft:", ""))
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
    }
}
