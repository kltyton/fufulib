package com.kltyton.fufulib.block.base;

import com.kltyton.fufulib.api.AutoModelShapeProvider;
import com.kltyton.fufulib.api.core.FufuAutoShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

public class AutoModelShapeBlock extends HorizontalDirectionalBlock implements AutoModelShapeProvider {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private final ResourceLocation modelId;

    public AutoModelShapeBlock(ResourceLocation blockId, Properties properties) {
        this(blockId, properties, true);
    }

    public AutoModelShapeBlock(ResourceLocation id, Properties properties, boolean idIsBlockId) {
        super(properties);
        this.modelId = idIsBlockId ? FufuAutoShapes.defaultBlockModelId(id) : id;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public ResourceLocation fufu$modelId(BlockState state) {
        return modelId;
    }

    @Override
    public Direction fufu$facing(BlockState state) {
        return FufuAutoShapes.horizontalFacingOrNorth(state, FACING);
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return FufuAutoShapes.shape(this, state);
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return FufuAutoShapes.shape(this, state);
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull VoxelShape getInteractionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos) {
        return FufuAutoShapes.shape(this, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}

