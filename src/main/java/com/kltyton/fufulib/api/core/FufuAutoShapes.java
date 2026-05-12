package com.kltyton.fufulib.api.core;

import com.kltyton.fufulib.api.AutoModelShapeProvider;
import com.kltyton.fufulib.shape.cache.FufuModelShapeCache;
import com.kltyton.fufulib.shape.cache.FufuModelShapeCache.IntBounds;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FufuAutoShapes {
    @SuppressWarnings("all")
    public static ResourceLocation defaultBlockModelId(ResourceLocation blockId) {
        return new ResourceLocation(blockId.getNamespace(), "block/" + blockId.getPath());
    }

    public static VoxelShape shape(BlockState state) {
        if (!(state.getBlock() instanceof AutoModelShapeProvider provider)) {
            return Shapes.block();
        }
        return shape(provider, state);
    }

    public static VoxelShape shape(AutoModelShapeProvider provider, BlockState state) {
        return FufuModelShapeCache.getOrCreateLocalShape(
                provider.fufu$modelId(state),
                provider.fufu$facing(state),
                0,
                0,
                0,
                provider.fufu$shapeMode(state));
    }

    public static VoxelShape localShape(AutoModelShapeProvider provider, BlockState state, int offsetX, int offsetY, int offsetZ) {
        return FufuModelShapeCache.getOrCreateLocalShape(
                provider.fufu$modelId(state),
                provider.fufu$facing(state),
                offsetX,
                offsetY,
                offsetZ,
                provider.fufu$shapeMode(state));
    }

    public static boolean hasLocalShape(AutoModelShapeProvider provider, BlockState state, int offsetX, int offsetY, int offsetZ) {
        return FufuModelShapeCache.hasLocalShape(
                provider.fufu$modelId(state),
                provider.fufu$facing(state),
                offsetX,
                offsetY,
                offsetZ,
                provider.fufu$shapeMode(state));
    }

    public static boolean isMultiBlock(AutoModelShapeProvider provider, BlockState state) {
        return FufuModelShapeCache.isMultiBlock(provider.fufu$modelId(state), provider.fufu$facing(state));
    }

    public static IntBounds bounds(AutoModelShapeProvider provider, BlockState state) {
        return FufuModelShapeCache.getOrCreateIntBounds(provider.fufu$modelId(state), provider.fufu$facing(state), provider.fufu$shapeMode(state));
    }

    public static void forEachOccupiedCell(AutoModelShapeProvider provider, BlockState state, OccupiedCellConsumer consumer) {
        IntBounds bounds = bounds(provider, state);
        if (bounds.isEmpty()) {
            return;
        }
        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
            for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                    if (hasLocalShape(provider, state, dx, dy, dz)) {
                        consumer.accept(dx, dy, dz);
                    }
                }
            }
        }
    }

    public static Direction horizontalFacingOrNorth(BlockState state, net.minecraft.world.level.block.state.properties.DirectionProperty property) {
        return state.hasProperty(property) ? state.getValue(property) : Direction.NORTH;
    }

    @FunctionalInterface
    public interface OccupiedCellConsumer {
        void accept(int offsetX, int offsetY, int offsetZ);
    }
}

