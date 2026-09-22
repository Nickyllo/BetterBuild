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
import dev.nickyllo.betterbuild.core.blueprint.Element;
import dev.nickyllo.betterbuild.core.learn.StyleLearner;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.schematic.Schematic;
import dev.nickyllo.betterbuild.core.schematic.SchematicCapture;
import dev.nickyllo.betterbuild.core.schematic.SchematicFiles;
import dev.nickyllo.betterbuild.core.schematic.SchematicLibrary;
import dev.nickyllo.betterbuild.core.schematic.SchematicWorldView;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import dev.nickyllo.betterbuild.core.platform.WorldView;
import dev.nickyllo.betterbuild.core.validate.StructureValidator;
import dev.nickyllo.betterbuild.core.validate.ValidationIssue;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Beyond this the Architect declines to raise a schematic by hand: that is Litematica's job. */
    private static final long MAX_PLACE_VOLUME = 200_000;
    /** Largest area /bb exportar will copy. */
    private static final long MAX_EXPORT_VOLUME = 1_000_000;

    /**
     * Litematica's own folder. Sharing it is the integration: whatever you save or
     * download for Litematica he can study and build, and whatever he exports shows up
     * in Litematica's load menu.
     */
    private static Path schematicsDir;
    private static SchematicLibrary library;

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
                                                StringArgumentType.getString(ctx, "nombre")))))
                        .then(Commands.literal("schematics").executes(ctx -> listSchematics(ctx.getSource())))
                        .then(Commands.literal("estudiar")
                                .then(Commands.argument("archivo", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(library().names(), b))
                                        .executes(ctx -> study(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "archivo")))))
                        .then(Commands.literal("colocar")
                                .then(Commands.argument("archivo", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(library().names(), b))
                                        .executes(ctx -> place(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "archivo")))))
                        .then(Commands.literal("exportar")
                                .then(Commands.argument("nombre", StringArgumentType.word())
                                        .executes(ctx -> exportLastBuild(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nombre")))
                                        .then(Commands.argument("desde", BlockPosArgument.blockPos())
                                                .then(Commands.argument("hasta", BlockPosArgument.blockPos())
                                                        .executes(ctx -> exportArea(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "nombre"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "desde"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "hasta")))))))));

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
        schematicsDir = FabricLoader.getInstance().getGameDir().resolve("schematics");

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

    // ------------------------------------------------------------------ schematics

    /** Loaded on first use, so a big folder never slows the game's start. */
    private static SchematicLibrary library() {
        if (library == null) {
            library = SchematicLibrary.load(schematicsDir);
        }
        return library;
    }

    /** By name; a file saved a moment ago in Litematica is picked up with one reload. */
    private static Optional<Schematic> findSchematic(String name) {
        Optional<Schematic> found = library().get(name.trim());
        if (found.isEmpty()) {
            library = SchematicLibrary.load(schematicsDir);
            found = library.get(name.trim());
        }
        return found;
    }

    private static int listSchematics(CommandSourceStack source) {
        library = SchematicLibrary.load(schematicsDir);
        if (library.isEmpty() && library.failures().isEmpty()) {
            source.sendSystemMessage(Component.literal("No hay schematics en " + schematicsDir
                    + ". Guarda alguno con Litematica o WorldEdit, o descargalo ahi."));
            return 0;
        }
        source.sendSystemMessage(Component.literal(library.all().size() + " schematics en la carpeta de Litematica:"));
        library.all().stream().limit(15).forEach(sc -> source.sendSystemMessage(Component.literal(
                "  " + sc.name() + " — " + sc.sizeX() + "x" + sc.sizeY() + "x" + sc.sizeZ()
                        + ", " + sc.blockCount() + " bloques")));
        if (library.all().size() > 15) {
            source.sendSystemMessage(Component.literal("  ...y " + (library.all().size() - 15) + " mas."));
        }
        if (!library.failures().isEmpty()) {
            source.sendSystemMessage(Component.literal(library.failures().size() + " no se pudieron leer:"));
            library.failures().stream().limit(3).forEach(f -> source.sendSystemMessage(
                    Component.literal("  " + f.file() + ": " + f.reason())));
        }
        return 1;
    }

    /**
     * Learns a style from a schematic file, exactly as /bb aprender does from the world.
     * Download a medieval village for Litematica and he builds medieval.
     */
    private static int study(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        Optional<Schematic> schematic = findSchematic(name);
        if (schematic.isEmpty()) {
            source.sendFailure(Component.literal("No encuentro ese schematic. /bb schematics para verlos."));
            return 0;
        }
        Schematic s = schematic.get();
        Optional<StyleProfile> learned = LEARNER.learn(new SchematicWorldView(s), s.box(), s.name());
        if (learned.isEmpty()) {
            source.sendFailure(Component.literal("En ese schematic no veo un edificio del que aprender."));
            return 0;
        }
        StyleProfile profile = learned.get();
        styles.put(profile);
        ACTIVE_STYLE.put(player.getUUID(), profile.source().toLowerCase());
        player.sendSystemMessage(Component.literal("Estudiado " + s.name() + ": " + profile.describe() + "."));
        player.sendSystemMessage(Component.literal("A partir de ahora construyo asi. /bb natural para volver a mi estilo."));
        return 1;
    }

    /** The Architect raises a schematic block by block in front of you, undoable like any build. */
    private static int place(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        if (RUNNING.containsKey(player.getUUID())) {
            source.sendFailure(Component.literal("Ya estoy construyendo. Usa /bb parar primero."));
            return 0;
        }
        Optional<Schematic> schematic = findSchematic(name);
        if (schematic.isEmpty()) {
            source.sendFailure(Component.literal("No encuentro ese schematic. /bb schematics para verlos."));
            return 0;
        }
        Schematic s = schematic.get();
        long volume = (long) s.sizeX() * s.sizeY() * s.sizeZ();
        if (volume > MAX_PLACE_VOLUME) {
            source.sendFailure(Component.literal("Es demasiado grande para levantarla a mano ("
                    + volume + " bloques de volumen). Colocala con Litematica."));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        LevelWorldView world = new LevelWorldView(level);
        Vec3i feet = new Vec3i(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        Vec3i ahead = forwardOf(player, feet, PLOT_DISTANCE + Math.max(s.sizeX(), s.sizeZ()) / 2);
        // On top of the ground: schematics carry their own foundations.
        int groundY = world.surfaceY(ahead.x(), ahead.z()) + 1;
        Vec3i corner = new Vec3i(ahead.x() - s.sizeX() / 2, groundY, ahead.z() - s.sizeZ() / 2);

        Blueprint blueprint = Blueprint.named(s.name()).size(s.sizeX(), s.sizeY(), s.sizeZ())
                .add(new Element.Module(s, Vec3i.ZERO, 0)).build();
        BuildPlan plan = PLANNER.plan(COMPILER.compile(blueprint));
        Box region = new Box(corner, corner.add(s.sizeX() - 1, s.sizeY() - 1, s.sizeZ() - 1));
        WorldView.Snapshot undo = world.snapshot(region);

        player.sendSystemMessage(Component.literal("Levanto " + s.name() + ": " + plan.blocksRequired()
                + " bloques. /bb parar o /bb deshacer cuando quieras."));
        RUNNING.put(player.getUUID(), new BuildJob(plan, world, level, player, corner, undo));
        LAST_BUILD.put(player.getUUID(), undo);
        return 1;
    }

    /** Saves the last thing he built for you as a .litematic, ready in Litematica's load menu. */
    private static int exportLastBuild(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        WorldView.Snapshot last = LAST_BUILD.get(player.getUUID());
        if (last == null) {
            source.sendFailure(Component.literal("No he construido nada que exportar. "
                    + "Para exportar otra zona: /bb exportar <nombre> <desde> <hasta>."));
            return 0;
        }
        return export(source, player, name, last.region());
    }

    /** Saves any area, coordinates written like /fill (with ~ for relative). */
    private static int exportArea(CommandSourceStack source, String name, BlockPos from, BlockPos to) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        Box region = Box.between(new Vec3i(from.getX(), from.getY(), from.getZ()),
                new Vec3i(to.getX(), to.getY(), to.getZ()));
        if (region.volume() > MAX_EXPORT_VOLUME) {
            source.sendFailure(Component.literal("La zona es demasiado grande (" + region.volume()
                    + " bloques). Maximo " + MAX_EXPORT_VOLUME + "."));
            return 0;
        }
        return export(source, player, name, region);
    }

    private static int export(CommandSourceStack source, ServerPlayer player, String name, Box region) {
        // The name comes from chat and becomes a file name: letters, digits, - and _ only.
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank() || safe.length() > 64) {
            source.sendFailure(Component.literal("Nombre no valido. Usa letras, numeros, - y _."));
            return 0;
        }
        Path file = schematicsDir.resolve(safe + ".litematic");
        if (Files.exists(file)) {
            source.sendFailure(Component.literal("Ya existe " + file.getFileName() + ". Elige otro nombre."));
            return 0;
        }
        LevelWorldView world = new LevelWorldView((ServerLevel) player.level());
        Optional<Schematic> captured = SchematicCapture.fromWorld(world, region, safe);
        if (captured.isEmpty()) {
            source.sendFailure(Component.literal("En esa zona no hay nada que guardar."));
            return 0;
        }
        try {
            int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
            SchematicFiles.writeLitematic(captured.get(), file, player.getName().getString(),
                    "Exportado con BetterBuild", dataVersion);
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("No pude guardarlo: " + e.getMessage()));
            return 0;
        }
        library = null; // Pick the new file up next time.
        Schematic s = captured.get();
        player.sendSystemMessage(Component.literal("Guardado en schematics/" + file.getFileName() + " ("
                + s.sizeX() + "x" + s.sizeY() + "x" + s.sizeZ() + ", " + s.blockCount()
                + " bloques). Abrelo en Litematica desde \"Cargar schematics\"."));
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
