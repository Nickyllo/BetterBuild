package dev.nickyllo.betterbuild.fabric;

import dev.nickyllo.betterbuild.core.build.BuildPlan;
import dev.nickyllo.betterbuild.core.build.BuildStep;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A build in progress, advanced a few blocks per server tick.
 *
 * <p>Placing the whole structure in one tick would work and would also be the wrong
 * mod: the point is that the building goes up in front of you. Spreading the plan
 * across ticks is also what makes pausing and cancelling trivial — the job is just
 * an index into a list.
 */
public final class BuildJob {

    /** Blocks per tick. 20 ticks a second, so this is roughly 240 blocks/second. */
    private static final int BLOCKS_PER_TICK = 12;

    /** How often to report progress, in placed blocks. */
    private static final int REPORT_EVERY = 150;

    private final BuildPlan plan;
    private final WorldView world;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final Vec3i origin;
    private final WorldView.Snapshot undo;

    private int index;
    private int sinceReport;
    private boolean cancelled;

    public BuildJob(BuildPlan plan, WorldView world, ServerLevel level, ServerPlayer player,
                    Vec3i origin, WorldView.Snapshot undo) {
        this.plan = plan;
        this.world = world;
        this.level = level;
        this.player = player;
        this.origin = origin;
        this.undo = undo;
    }

    public boolean isDone() {
        return cancelled || index >= plan.size();
    }

    public void cancel() {
        cancelled = true;
    }

    public WorldView.Snapshot undoSnapshot() {
        return undo;
    }

    public double progress() {
        return plan.progress(index);
    }

    /** Places the next handful of blocks. Returns false when there is nothing left. */
    public boolean tick() {
        if (isDone()) {
            return false;
        }
        int placed = 0;
        Vec3i last = null;

        while (placed < BLOCKS_PER_TICK && index < plan.size()) {
            BuildStep step = plan.steps().get(index++);
            Vec3i pos = step.pos().add(origin);
            world.setBlock(pos, step.block());
            if (!step.isClearing()) {
                last = pos;
                placed++;
            }
        }

        // One sound per tick, not per block: twelve at once is noise, not feedback.
        if (last != null) {
            level.playSound(null, new BlockPos(last.x(), last.y(), last.z()),
                    SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6f, 1.0f);
        }

        sinceReport += placed;
        if (sinceReport >= REPORT_EVERY) {
            sinceReport = 0;
            player.sendSystemMessage(Component.literal(
                    "  " + Math.round(progress() * 100) + "%"));
        }

        if (index >= plan.size()) {
            player.sendSystemMessage(Component.literal(
                    "El Arquitecto ha terminado: " + plan.name() + " (" + plan.blocksRequired()
                            + " bloques). /bb deshacer para revertirlo."));
            return false;
        }
        return true;
    }
}
