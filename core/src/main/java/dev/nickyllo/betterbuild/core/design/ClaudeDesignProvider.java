package dev.nickyllo.betterbuild.core.design;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.schematic.Schematic;
import dev.nickyllo.betterbuild.core.schematic.SchematicDigest;
import dev.nickyllo.betterbuild.core.schematic.SchematicLibrary;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Designs buildings with Claude.
 *
 * <p>The model never emits blocks — only the primitives in {@link BlueprintDto}, whose
 * JSON schema is derived from the record and enforced by the API. That is what makes
 * the output trustworthy enough to compile: the model works at the level it is good at
 * (proportion, style, what a tavern needs) and the compiler handles the voxel geometry
 * it is bad at.
 */
public final class ClaudeDesignProvider implements DesignProvider {

    private static final String MODEL = "claude-opus-5";
    private static final long MAX_TOKENS = 16_000L;
    /** Reference buildings shown per request; each costs roughly a thousand tokens. */
    private static final int REFERENCES = 3;
    /** Modules listed by name; beyond this the list is noise the model has to wade through. */
    private static final int MODULES_LISTED = 30;

    private final AnthropicClient client;
    private final SchematicLibrary library;
    private final BlueprintMapper mapper;

    public ClaudeDesignProvider(AnthropicClient client) {
        this(client, SchematicLibrary.empty());
    }

    /**
     * With a library, the model sees human-built references for each request and can
     * place any schematic in it as a MODULE.
     */
    public ClaudeDesignProvider(AnthropicClient client, SchematicLibrary library) {
        this.client = client;
        this.library = library;
        this.mapper = new BlueprintMapper(library);
    }

    /**
     * Builds a provider from the environment, or empty when no credentials are
     * configured — the mod then runs on the local repertoire instead of failing.
     *
     * @param timeout how long the player waits before the Architect gives up. Kept
     *                short on purpose: he is walking to the site while this runs.
     */
    public static Optional<ClaudeDesignProvider> fromEnvironment(Duration timeout) {
        return fromEnvironment(timeout, SchematicLibrary.empty());
    }

    public static Optional<ClaudeDesignProvider> fromEnvironment(Duration timeout, SchematicLibrary library) {
        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .fromEnv()
                    .timeout(timeout)
                    .build();
            return Optional.of(new ClaudeDesignProvider(client, library));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "Claude";
    }

    @Override
    public Blueprint design(DesignRequest request) throws DesignException {
        SiteSurvey survey = request.survey();

        StructuredMessageCreateParams<BlueprintDto> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt())
                .outputConfig(BlueprintDto.class)
                .addUserMessage(userPrompt(request, library))
                .build();

        try {
            BlueprintDto dto = client.messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new DesignException("the model returned no design"));

            return mapper.toBlueprint(dto, survey.plot());
        } catch (DesignException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DesignException("could not reach the design service", e);
        }
    }

    private String systemPrompt() {
        return """
            You are the architect in a Minecraft world. You design buildings that a \
            builder entity then places block by block.

            You do not place blocks. You describe a building using the primitives in the \
            required output schema, and a deterministic compiler turns them into blocks. \
            Think in walls, roofs, storeys and openings, never in individual coordinates.

            Coordinates are relative to the plot's minimum corner: x runs east, y up, \
            z south. Everything you emit must fit inside the plot size you are given.

            Rules that keep a design buildable:
            - Leave a one block margin inside the plot on all four sides for the roof \
              overhang, so the finished building including its eaves fits the plot.
            - Put a SOLID_FILL plinth at y=0 before anything else; walls start at y=1.
            - Emit walls before the openings that pierce them: later elements overwrite \
              earlier ones.
            - Every building needs at least one OPENING of kind DOOR reaching the outside, \
              with sill 1 and height 2.
            - A GABLE_ROOF footprint is flat: give it the same y for both corners, one \
              above the top of the walls.
            - Never leave a block with nothing touching it on any side.
            - Choose blocks that exist in vanilla Minecraft and suit the biome.

            Aim for buildings a player would be pleased to find: right proportions, \
            visible structure, windows where a room would want light.

            You may be shown reference buildings that people built by hand, drawn as a \
            front elevation and a ground plan. They are the standard to aim for. Notice \
            what makes them good — proportions, where timber shows, how the roof \
            overhangs, the rhythm of the windows — and bring that into your own design. \
            Do not copy them.

            You may also be offered modules: hand-built pieces such as porches or tower \
            tops. Place one with kind MODULE, its exact name, its minimum corner in area \
            x1,y1,z1 and a rotation of 0 to 3 quarter turns clockwise. Use them for \
            detail the primitives cannot express, attached to the building, never \
            floating. If no modules are offered, do not use MODULE.
            """;
    }

    /** Package-private so tests can check what the model is actually told. */
    static String userPrompt(DesignRequest request, SchematicLibrary library) {
        SiteSurvey survey = request.survey();
        StringBuilder sb = new StringBuilder();
        sb.append("Plot: ").append(survey.plot().sizeX()).append(" wide, ")
                .append(survey.plot().sizeY()).append(" tall, ")
                .append(survey.plot().sizeZ()).append(" deep.\n");
        sb.append("Biome: ").append(survey.biome()).append('\n');
        sb.append("Ground falls ").append(survey.slope()).append(" blocks across the plot")
                .append(survey.isSteep() ? " — steep, consider stilts or terracing.\n" : ".\n");
        if (survey.hasWater()) {
            sb.append("Part of the plot is water.\n");
        }

        request.style().ifPresent(style -> appendStyle(sb, style));

        List<Schematic> references = library.relevantTo(request.prompt(), REFERENCES);
        if (!references.isEmpty()) {
            sb.append("\nReference buildings made by people. Legend: ")
                    .append(SchematicDigest.LEGEND).append("\n\n");
            for (Schematic ref : references) {
                sb.append(SchematicDigest.of(ref)).append('\n');
            }
        }

        if (!library.isEmpty()) {
            sb.append("\nModules you can place with MODULE, by exact name:\n");
            library.all().stream().limit(MODULES_LISTED).forEach(m -> sb.append("- ").append(m.name())
                    .append(": ").append(m.sizeX()).append(" wide, ").append(m.sizeY()).append(" tall, ")
                    .append(m.sizeZ()).append(" deep\n"));
        }

        if (request.isRefinement()) {
            sb.append("\nYou already designed \"")
                    .append(request.previous().orElseThrow().name())
                    .append("\" here. Change only what is asked and keep the rest.\n");
            sb.append("The change requested: ").append(request.prompt());
        } else {
            sb.append("\nThe player asked for: ").append(request.prompt());
        }
        return sb.toString();
    }

    /** What the player taught him, stated as instructions rather than left implicit. */
    private static void appendStyle(StringBuilder sb, StyleProfile style) {
        sb.append("\nThe player taught you a style from a building they made (\"")
                .append(style.source()).append("\"). Follow it:\n");
        sb.append("- materials: ");
        for (PaletteSlot slot : PaletteSlot.values()) {
            sb.append(slot.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                    .append(style.palette().get(slot).id()).append(' ');
        }
        sb.append("\n- walls ").append(style.wallHeight()).append(" high, ")
                .append(style.storeys()).append(style.storeys() == 1 ? " storey" : " storeys").append('\n');
        sb.append("- roof: ").append(style.hasGableRoof()
                ? "gable, rising " + style.roofPitch() + " per step, eaves out " + style.overhang()
                : "flat").append('\n');
        if (style.windowRatio() > 0) {
            sb.append("- glazing: about ").append(Math.max(1, Math.round(style.windowRatio() * 10)))
                    .append(" glass blocks per 10 blocks of wall\n");
        }
    }
}
