package dev.nickyllo.betterbuild.mc;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapts a Minecraft level to the core's {@link WorldView}.
 *
 * <p>Shared by every loader on this Minecraft version: it only touches vanilla
 * classes, and the loaders differ in registration and entry points, not in these
 * calls. Written against official Mojang mappings so Fabric and NeoForge can compile
 * the same file.
 */
public final class LevelWorldView implements WorldView {

    private final ServerLevel level;
    private final RegistryBlockResolver resolver;

    public LevelWorldView(ServerLevel level) {
        this.level = level;
        this.resolver = new RegistryBlockResolver();
    }

    public RegistryBlockResolver resolver() {
        return resolver;
    }

    private static BlockPos pos(Vec3i v) {
        return new BlockPos(v.x(), v.y(), v.z());
    }

    @Override
    public boolean isSolid(Vec3i p) {
        BlockPos bp = pos(p);
        return level.getBlockState(bp).isSolidRender(level, bp);
    }

    @Override
    public boolean isAir(Vec3i p) {
        return level.getBlockState(pos(p)).isAir();
    }

    @Override
    public boolean isLiquid(Vec3i p) {
        return !level.getFluidState(pos(p)).isEmpty();
    }

    @Override
    public int surfaceY(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    @Override
    public boolean setBlock(Vec3i p, BlockRef ref) {
        BlockState state = resolver.stateFor(ref);
        if (state == null) {
            return false;
        }
        BlockPos bp = pos(p);
        if (!level.isLoaded(bp)) {
            return false;
        }
        // Flag 3 = update neighbours and notify clients, exactly as a player placement does.
        return level.setBlock(bp, state, Block.UPDATE_ALL);
    }

    @Override
    public String biomeAt(Vec3i p) {
        return level.getBiome(pos(p)).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("minecraft:plains");
    }

    /**
     * Copies the region's current blocks so a build can be undone exactly.
     *
     * <p>Held in memory for now, which is fine for the plot sizes the wand allows;
     * persisting snapshots across restarts is on the roadmap.
     */
    @Override
    public Snapshot snapshot(Box region) {
        Map<BlockPos, BlockState> before = new HashMap<>();
        region.forEach(v -> {
            BlockPos bp = pos(v);
            before.put(bp, level.getBlockState(bp));
        });

        return new Snapshot() {
            @Override
            public void restore() {
                before.forEach((bp, state) -> level.setBlock(bp, state, Block.UPDATE_ALL));
            }

            @Override
            public Box region() {
                return region;
            }
        };
    }

    /** Resolves version-independent block ids against this version's registry. */
    public static final class RegistryBlockResolver
            implements dev.nickyllo.betterbuild.core.platform.BlockResolver {

        @Override
        public boolean exists(String blockId) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            return id != null && BuiltInRegistries.BLOCK.containsKey(id);
        }

        /** The block state to place, walking the fallback chain; null if nothing resolves. */
        public BlockState stateFor(BlockRef ref) {
            if (ref.isAir()) {
                return Blocks.AIR.defaultBlockState();
            }
            return resolve(ref)
                    .map(ResourceLocation::tryParse)
                    .map(BuiltInRegistries.BLOCK::get)
                    .map(Block::defaultBlockState)
                    .orElse(null);
        }
    }
}
