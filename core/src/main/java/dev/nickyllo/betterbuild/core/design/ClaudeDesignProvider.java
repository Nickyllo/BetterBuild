package dev.nickyllo.betterbuild.core.design;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;

import java.time.Duration;
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

    private final AnthropicClient client;
    private final BlueprintMapper mapper = new BlueprintMapper();

    public ClaudeDesignProvider(AnthropicClient client) {
        this.client = client;
    }

    /**
     * Builds a provider from the environment, or empty when no credentials are
     * configured — the mod then runs on the local repertoire instead of failing.
     *
     * @param timeout how long the player waits before the Architect gives up. Kept
     *                short on purpose: he is walking to the site while this runs.
     */
    public static Optional<ClaudeDesignProvider> fromEnvironment(Duration timeout) {
        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .fromEnv()
                    .timeout(timeout)
                    .build();
            return Optional.of(new ClaudeDesignProvider(client));
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
                .addUserMessage(userPrompt(request, survey))
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
            """;
    }

    private String userPrompt(DesignRequest request, SiteSurvey survey) {
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
}
