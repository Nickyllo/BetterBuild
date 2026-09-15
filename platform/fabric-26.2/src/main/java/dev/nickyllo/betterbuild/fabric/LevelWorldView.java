package dev.nickyllo.betterbuild.fabric;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.platform.BlockResolver;
import dev.nickyllo.betterbuild.core.platform.WorldView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapts a Minecraft 26.2 level to the core's {@link WorldView}.
 *
 * <p>The only class in the mod that knows this Minecraft version exists. Note
 * {@code Identifier}: 26.2 renamed {@code ResourceLocation}, and this file is exactly
 * where a rename like that is supposed to hurt — and nowhere else.
 */
public final class LevelWorldView implements WorldView {

    private final ServerLevel level;
    private final Resolver resolver = new Resolver();

    public LevelWorldView(ServerLevel level) {
        this.level = level;
    }

    public Resolver resolver() {
        return resolver;
    }

    private static BlockPos at(Vec3i v) {
        return new BlockPos(v.x(), v.y(), v.z());
    }

    @Override
    public boolean isSolid(Vec3i p) {
        return level.getBlockState(at(p)).isSolidRender();
    }

    @Override
    public boolean isAir(Vec3i p) {
        return level.getBlockState(at(p)).isAir();
    }

    @Override
    public String blockIdAt(Vec3i p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(at(p)).getBlock()).toString();
    }

    @Override
    public boolean isLiquid(Vec3i p) {
        return !level.getFluidState(at(p)).isEmpty();
    }

    @Override
    public int surfaceY(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    @Override
    public boolean setBlock(Vec3i p, BlockRef ref) {
        BlockState state = resolver.stateFor(ref);
        return state != null && level.setBlock(at(p), state, Block.UPDATE_ALL);
    }

    @Override
    public String biomeAt(Vec3i p) {
        // 26.2: ResourceKey exposes identifier(), not location().
        return level.getBiome(at(p)).unwrapKey()
                .<String>map(key -> key.identifier().toString())
                .orElse("minecraft:plains");
    }

    /** Copies the region so a build can be undone exactly, block for block. */
    @Override
    public Snapshot snapshot(Box region) {
        Map<BlockPos, BlockState> before = new HashMap<>();
        region.forEach(v -> {
            BlockPos bp = at(v);
            before.put(bp, level.getBlockState(bp));
        });
        return new Snapshot() {
            @Override
            public void restore() {
                before.forEach((bp, st) -> level.setBlock(bp, st, Block.UPDATE_ALL));
            }

            @Override
            public Box region() {
                return region;
            }
        };
    }

    /** Walks a block's fallback chain against this version's registry. */
    public static final class Resolver implements BlockResolver {

        @Override
        public boolean exists(String blockId) {
            Identifier id = Identifier.tryParse(blockId);
            return id != null && BuiltInRegistries.BLOCK.containsKey(id);
        }

        public BlockState stateFor(BlockRef ref) {
            if (ref.isAir()) {
                return Blocks.AIR.defaultBlockState();
            }
            for (String candidate : ref.resolutionChain()) {
                Identifier id = Identifier.tryParse(candidate);
                if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                    return BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
                }
            }
            return null;
        }
    }
}
