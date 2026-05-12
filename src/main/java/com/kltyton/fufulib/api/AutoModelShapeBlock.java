package com.kltyton.fufulib.api;

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

public class AutoModelShapeBlock extends HorizontalDirectionalBlock implements AutoModelShapeProvider {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private final ResourceLocation modelId;

    public AutoModelShapeBlock(ResourceLocation blockId, Block.Properties properties) {
        this(blockId, properties, true);
    }

    public AutoModelShapeBlock(ResourceLocation id, Block.Properties properties, boolean idIsBlockId) {
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FufuAutoShapes.shape(this, state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FufuAutoShapes.shape(this, state);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return FufuAutoShapes.shape(this, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}

