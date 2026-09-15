package dev.nickyllo.betterbuild.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.build.BuildPlan;
import dev.nickyllo.betterbuild.core.build.BuildPlanner;
import dev.nickyllo.betterbuild.core.compile.BlueprintCompiler;
import dev.nickyllo.betterbuild.core.compile.CompiledStructure;
import dev.nickyllo.betterbuild.core.design.DesignProvider;
import dev.nickyllo.betterbuild.core.design.DesignRequest;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.learn.StyleLearner;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.platform.WorldView;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fabric entry point for Minecraft 26.2.
 *
 * <p>Wiring only. Every decision — what to build, whether it stands up, in what order
 * to place it — happens in the core, which does not import a single Minecraft class
 * and is tested without launching the game.
 */
public final class BetterBuildFabric implements ModInitializer {

    public static final String ID = "betterbuild";
    private static final Logger LOG = LoggerFactory.getLogger(ID);

    /** How far in front of the player the plot is laid out. */
    private static final int PLOT_DISTANCE = 6;
    private static final int DEFAULT_SIZE = 13;
    private static final int MAX_SIZE = 24;

    private static final DesignProvider LOCAL = new ProceduralDesignProvider();
    private static final BlueprintCompiler COMPILER = new BlueprintCompiler();
    private static final StructureValidator VALIDATOR = new StructureValidator();
    private static final BuildPlanner PLANNER = new BuildPlanner();

    /** How far around the player /bb aprender looks for a building to study. */
    private static final int LEARN_RADIUS = 20;
    private static final int LEARN_BELOW = 6;
    private static final int LEARN_ABOVE = 24;

    private static final StyleLearner LEARNER = new StyleLearner();
    private static StyleStore styles;

    private static final Map<UUID, BuildJob> RUNNING = new HashMap<>();
    private static final Map<UUID, WorldView.Snapshot> LAST_BUILD = new HashMap<>();
    /** Style each player is currently building in; absent means "read the biome". */
    private static final Map<UUID, String> ACTIVE_STYLE = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(Commands.literal("bb")
                        .then(Commands.literal("construir")
                                .executes(ctx -> build(ctx.getSource(), DEFAULT_SIZE, DEFAULT_SIZE, "una casa"))
                                .then(Commands.argument("ancho", IntegerArgumentType.integer(7, MAX_SIZE))
                                        .then(Commands.argument("fondo", IntegerArgumentType.integer(7, MAX_SIZE))
                                                .executes(ctx -> build(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "ancho"),
                                                        IntegerArgumentType.getInteger(ctx, "fondo"),
                                                        "una casa"))
                                                .then(Commands.argument("descripcion", StringArgumentType.greedyString())
                                                        .executes(ctx -> build(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "ancho"),
                                                                IntegerArgumentType.getInteger(ctx, "fondo"),
                                                                StringArgumentType.getString(ctx, "descripcion")))))))
                        .then(Commands.literal("parar").executes(ctx -> stop(ctx.getSource())))
                        .then(Commands.literal("deshacer").executes(ctx -> undo(ctx.getSource())))
                        .then(Commands.literal("aprender")
                                .then(Commands.argument("nombre", StringArgumentType.greedyString())
                                        .executes(ctx -> learn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nombre")))))
                        .then(Commands.literal("estilos").executes(ctx -> listStyles(ctx.getSource())))
                        .then(Commands.literal("usar")
                                .then(Commands.argument("nombre", StringArgumentType.greedyString())
                                        .executes(ctx -> useStyle(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nombre")))))
                        .then(Commands.literal("natural").executes(ctx -> clearStyle(ctx.getSource())))
                        .then(Commands.literal("olvidar")
                                .then(Commands.argument("nombre", StringArgumentType.greedyString())
                                        .executes(ctx -> forget(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nombre")))))));

        // Advances every running build a few blocks per tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (RUNNING.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<UUID, BuildJob>> it = RUNNING.entrySet().iterator();
            while (it.hasNext()) {
                if (!it.next().getValue().tick()) {
                    it.remove();
                }
            }
        });

        styles = new StyleStore(
                FabricLoader.getInstance().getConfigDir().resolve("betterbuild-estilos.json"));

        LOG.info("[{}] Listo. /bb construir para levantar algo, /bb aprender para ensenarle. "
                + "Estilos aprendidos: {}", ID, styles.names().size());
    }

    private static int build(CommandSourceStack source, int width, int depth, String prompt) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Este comando lo tiene que usar un jugador."));
            return 0;
        }
        if (RUNNING.containsKey(player.getUUID())) {
            source.sendFailure(Component.literal("Ya estoy construyendo. Usa /bb parar primero."));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        LevelWorldView world = new LevelWorldView(level);

        // Lay the plot out in front of the player, snapped to the ground.
        Vec3i feet = new Vec3i(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        Vec3i ahead = forwardOf(player, feet, PLOT_DISTANCE);
        int groundY = world.surfaceY(ahead.x(), ahead.z());
        Vec3i corner = new Vec3i(ahead.x() - width / 2, groundY, ahead.z() - depth / 2);
        Box plot = new Box(corner, corner.add(width - 1, 40, depth - 1));

        SiteSurvey survey = SiteSurvey.of(world, plot);
        player.sendSystemMessage(Component.literal(
                "El Arquitecto mira el terreno: " + survey.describe() + "."));

        Optional<StyleProfile> style = styleFor(player);
        DesignRequest request = style
                .map(sp -> DesignRequest.inStyle(prompt, survey, sp))
                .orElseGet(() -> DesignRequest.fresh(prompt, survey));

        Blueprint blueprint;
        try {
            blueprint = LOCAL.design(request);
        } catch (DesignProvider.DesignException e) {
            source.sendFailure(Component.literal("No puedo construir ahi: " + e.getMessage()));
            return 0;
        }

        CompiledStructure structure = COMPILER.compile(blueprint);

        // Nothing reaches the world without passing validation first.
        List<ValidationIssue> errors = new ArrayList<>();
        for (ValidationIssue issue : VALIDATOR.validate(structure, blueprint.size())) {
            if (issue.severity() == ValidationIssue.Severity.ERROR) {
                errors.add(issue);
            }
        }
        if (!errors.isEmpty()) {
            source.sendFailure(Component.literal(
                    "Ese diseno no se sostiene (" + errors.get(0).kind() + "). No lo construyo."));
            return 0;
        }

        BuildPlan plan = PLANNER.plan(structure);
        WorldView.Snapshot undo = world.snapshot(plot);

        player.sendSystemMessage(Component.literal(
                "Empiezo: " + blueprint.name() + " — " + structure.solidCount() + " bloques"
                        + style.map(sp -> ", al estilo de " + sp.source()).orElse("") + "."));

        BuildJob job = new BuildJob(plan, world, level, player, corner, undo);
        RUNNING.put(player.getUUID(), job);
        LAST_BUILD.put(player.getUUID(), undo);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        BuildJob job = RUNNING.remove(player.getUUID());
        if (job == null) {
            source.sendFailure(Component.literal("No estoy construyendo nada."));
            return 0;
        }
        job.cancel();
        player.sendSystemMessage(Component.literal(
                "Paro. Llevaba el " + Math.round(job.progress() * 100)
                        + "%. /bb deshacer lo revierte."));
        return 1;
    }

    private static int undo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        RUNNING.remove(player.getUUID());
        WorldView.Snapshot snapshot = LAST_BUILD.remove(player.getUUID());
        if (snapshot == null) {
            source.sendFailure(Component.literal("No hay nada que deshacer."));
            return 0;
        }
        snapshot.restore();
        player.sendSystemMessage(Component.literal("Revertido. El terreno vuelve a como estaba."));
        return 1;
    }

    /**
     * Studies whatever is standing around the player and keeps the lesson.
     *
     * <p>This is the teaching the mod is built on: you put something up by hand, he
     * looks at it, and from then on what he builds carries your materials, your
     * storey heights and your roof.
     */
    private static int learn(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        LevelWorldView world = new LevelWorldView(level);

        Vec3i centre = new Vec3i(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        Box region = new Box(
                centre.add(-LEARN_RADIUS, -LEARN_BELOW, -LEARN_RADIUS),
                centre.add(LEARN_RADIUS, LEARN_ABOVE, LEARN_RADIUS));

        Optional<StyleProfile> learned = LEARNER.learn(world, region, name.trim());
        if (learned.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No veo aqui nada de lo que aprender. Ponte junto a la construccion."));
            return 0;
        }

        StyleProfile profile = learned.get();
        styles.put(profile);
        ACTIVE_STYLE.put(player.getUUID(), profile.source().toLowerCase());

        player.sendSystemMessage(Component.literal("Entendido: " + profile.describe() + "."));
        player.sendSystemMessage(Component.literal(
                "Guardado como \"" + profile.source() + "\". A partir de ahora construyo asi. "
                        + "/bb natural para volver a mi estilo."));
        return 1;
    }

    private static int listStyles(CommandSourceStack source) {
        if (styles.names().isEmpty()) {
            source.sendSystemMessage(Component.literal(
                    "Todavia no me has ensenado nada. Ponte junto a algo que hayas "
                            + "construido y usa /bb aprender <nombre>."));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Se construir al estilo de:"));
        for (String name : styles.names()) {
            styles.get(name).ifPresent(sp ->
                    source.sendSystemMessage(Component.literal("  " + sp.source() + " — " + sp.describe())));
        }
        return 1;
    }

    private static int useStyle(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        Optional<StyleProfile> style = styles.get(name.trim());
        if (style.isEmpty()) {
            source.sendFailure(Component.literal("No conozco ese estilo. /bb estilos para verlos."));
            return 0;
        }
        ACTIVE_STYLE.put(player.getUUID(), name.trim().toLowerCase());
        player.sendSystemMessage(Component.literal(
                "De acuerdo, construyo al estilo de " + style.get().source() + "."));
        return 1;
    }

    private static int clearStyle(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ACTIVE_STYLE.remove(player.getUUID());
        player.sendSystemMessage(Component.literal(
                "Vuelvo a mi criterio: elegire los materiales segun el bioma."));
        return 1;
    }

    private static int forget(CommandSourceStack source, String name) {
        if (!styles.forget(name.trim())) {
            source.sendFailure(Component.literal("No conozco ese estilo."));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Olvidado: " + name.trim()));
        return 1;
    }

    /** The style this player is building in, if any is active and still known. */
    private static Optional<StyleProfile> styleFor(ServerPlayer player) {
        String active = ACTIVE_STYLE.get(player.getUUID());
        return active == null ? Optional.empty() : styles.get(active);
    }

    /** The block position a few steps ahead of where the player is looking. */
    private static Vec3i forwardOf(ServerPlayer player, Vec3i from, int distance) {
        double yaw = Math.toRadians(player.getYRot());
        int dx = (int) Math.round(-Math.sin(yaw) * distance);
        int dz = (int) Math.round(Math.cos(yaw) * distance);
        return from.add(dx, 0, dz);
    }
}
