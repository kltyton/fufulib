package com.kltyton.fufulib.block.base;

import com.kltyton.fufulib.api.core.FufuAutoShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AutoSimpleMultiBlock extends AutoModelShapeBlock {
    public static final int ORIGIN_OFFSET_BIAS = 4;
    public static final int MIN_ORIGIN_OFFSET = -ORIGIN_OFFSET_BIAS;
    public static final int MAX_ORIGIN_OFFSET = ORIGIN_OFFSET_BIAS;
    public static final IntegerProperty ORIGIN_X = IntegerProperty.create("origin_x", 0, ORIGIN_OFFSET_BIAS * 2);
    public static final IntegerProperty ORIGIN_Y = IntegerProperty.create("origin_y", 0, ORIGIN_OFFSET_BIAS * 2);
    public static final IntegerProperty ORIGIN_Z = IntegerProperty.create("origin_z", 0, ORIGIN_OFFSET_BIAS * 2);

    private static final ThreadLocal<Boolean> REMOVING_PARTS = ThreadLocal.withInitial(() -> false);

    public AutoSimpleMultiBlock(ResourceLocation blockId, Block.Properties properties) {
        this(blockId, properties, true);
    }

    public AutoSimpleMultiBlock(ResourceLocation id, Block.Properties properties, boolean idIsBlockId) {
        super(id, properties, idIsBlockId);
        registerDefaultState(defaultBlockState()
                .setValue(ORIGIN_X, encodeOriginOffset(0))
                .setValue(ORIGIN_Y, encodeOriginOffset(0))
                .setValue(ORIGIN_Z, encodeOriginOffset(0)));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        state = state
                .setValue(ORIGIN_X, encodeOriginOffset(0))
                .setValue(ORIGIN_Y, encodeOriginOffset(0))
                .setValue(ORIGIN_Z, encodeOriginOffset(0));
        return canPlaceWholeShape(context, state) ? state : null;
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state, @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && isOriginPart(state)) {
            placeLinkedParts(level, pos, state);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && isOriginPart(state) && !oldState.is(this)) {
            placeLinkedParts(level, pos, state);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        if (isOriginPart(state)) {
            return super.canSurvive(state, level, pos);
        }
        Origin origin = origin(level, state, pos);
        return origin != null && isOriginPart(origin.state());
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull BlockState updateShape(
            @NotNull BlockState state,
            @NotNull Direction direction,
            @NotNull BlockState neighborState,
            @NotNull LevelAccessor level,
            @NotNull BlockPos currentPos,
            @NotNull BlockPos neighborPos) {
        if (isOriginPart(state)) {
            return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
        }
        return canSurvive(state, level, currentPos) ? state : Blocks.AIR.defaultBlockState();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && !REMOVING_PARTS.get()) {
            Origin origin = originOrSelf(level, state, pos);
            if (origin != null) {
                if (origin.pos().equals(pos)) {
                    removeLinkedParts(level, origin.pos(), origin.state());
                } else {
                    level.destroyBlock(origin.pos(), true);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void playerWillDestroy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull Player player) {
        if (!level.isClientSide && !isOriginPart(state)) {
            Origin origin = origin(level, state, pos);
            if (origin != null) {
                level.destroyBlock(origin.pos(), !player.isCreative(), player);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void playerDestroy(
            @NotNull Level level,
            @NotNull Player player,
            @NotNull BlockPos pos,
            @NotNull BlockState state,
            @Nullable BlockEntity blockEntity,
            @NotNull ItemStack tool) {
        if (isOriginPart(state)) {
            super.playerDestroy(level, player, pos, state, blockEntity, tool);
        }
    }

    @Override
    public @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        if (isOriginPart(state)) {
            return super.getShape(state, level, pos, context);
        }
        Origin origin = origin(level, state, pos);
        return origin == null ? Shapes.empty() : FufuAutoShapes.localShape(this, origin.state(), pos.getX() - origin.pos().getX(), pos.getY() - origin.pos().getY(), pos.getZ() - origin.pos().getZ());
    }

    @Override
    public @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    public @NotNull VoxelShape getInteractionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull InteractionResult use(
            @NotNull BlockState state,
            @NotNull Level level,
            @NotNull BlockPos pos,
            @NotNull Player player,
            @NotNull InteractionHand hand,
            @NotNull BlockHitResult hit) {
        if (isOriginPart(state)) {
            return super.use(state, level, pos, player, hand, hit);
        }
        Origin origin = origin(level, state, pos);
        if (origin == null) {
            return InteractionResult.PASS;
        }
        BlockHitResult originHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), origin.pos(), hit.isInside());
        return origin.state().use(level, player, hand, originHit);
    }

    @Override
    public @NotNull ItemStack getCloneItemStack(@NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull BlockState state) {
        Origin origin = originOrSelf(level, state, pos);
        return origin == null ? ItemStack.EMPTY : new ItemStack(origin.block());
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return isOriginPart(state) ? super.getRenderShape(state) : RenderShape.INVISIBLE;
    }

    protected boolean canPlaceWholeShape(BlockPlaceContext context, BlockState state) {
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        final boolean[] canPlace = {true};
        FufuAutoShapes.forEachOccupiedCell(this, state, (dx, dy, dz) -> {
            if (!canPlace[0] || isOriginCell(dx, dy, dz)) {
                return;
            }
            BlockPos partPos = origin.offset(dx, dy, dz);
            if (!level.getWorldBorder().isWithinBounds(partPos) || !level.getBlockState(partPos).canBeReplaced(context)) {
                canPlace[0] = false;
            }
        });
        return canPlace[0];
    }

    protected void placeLinkedParts(Level level, BlockPos origin, BlockState originState) {
        FufuAutoShapes.forEachOccupiedCell(this, originState, (dx, dy, dz) -> {
            if (isOriginCell(dx, dy, dz)) {
                return;
            }
            BlockPos partPos = origin.offset(dx, dy, dz);
            BlockState partState = originState
                    .setValue(ORIGIN_X, encodeOriginOffset(-dx))
                    .setValue(ORIGIN_Y, encodeOriginOffset(-dy))
                    .setValue(ORIGIN_Z, encodeOriginOffset(-dz));
            level.setBlock(partPos, partState, Block.UPDATE_ALL);
        });
    }

    protected void removeLinkedParts(Level level, BlockPos origin, BlockState originState) {
        REMOVING_PARTS.set(true);
        try {
            FufuAutoShapes.forEachOccupiedCell(this, originState, (dx, dy, dz) -> {
                if (isOriginCell(dx, dy, dz)) {
                    return;
                }
                BlockPos partPos = origin.offset(dx, dy, dz);
                BlockState partState = level.getBlockState(partPos);
                if (partState.is(this) && fufu$originPos(partState, partPos).equals(origin)) {
                    level.setBlock(partPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            });
        } finally {
            REMOVING_PARTS.set(false);
        }
    }

    public BlockPos fufu$originPos(BlockState state, BlockPos pos) {
        return pos.offset(
                decodeOriginOffset(state.getValue(ORIGIN_X)),
                decodeOriginOffset(state.getValue(ORIGIN_Y)),
                decodeOriginOffset(state.getValue(ORIGIN_Z)));
    }

    public boolean fufu$isOriginPart(BlockState state) {
        return isOriginPart(state);
    }

    protected static boolean isOriginCell(int dx, int dy, int dz) {
        return dx == 0 && dy == 0 && dz == 0;
    }

    protected static boolean isOriginPart(BlockState state) {
        return decodeOriginOffset(state.getValue(ORIGIN_X)) == 0
                && decodeOriginOffset(state.getValue(ORIGIN_Y)) == 0
                && decodeOriginOffset(state.getValue(ORIGIN_Z)) == 0;
    }

    protected static int encodeOriginOffset(int offset) {
        if (offset < MIN_ORIGIN_OFFSET || offset > MAX_ORIGIN_OFFSET) {
            throw new IllegalArgumentException("AutoSimpleMultiBlock part offset " + offset + " is outside supported range [" + MIN_ORIGIN_OFFSET + ", " + MAX_ORIGIN_OFFSET + "]");
        }
        return offset + ORIGIN_OFFSET_BIAS;
    }

    protected static int decodeOriginOffset(int stored) {
        return stored - ORIGIN_OFFSET_BIAS;
    }

    @Nullable
    private Origin originOrSelf(BlockGetter level, BlockState state, BlockPos pos) {
        return isOriginPart(state) ? new Origin(this, state, pos) : origin(level, state, pos);
    }

    @Nullable
    private Origin origin(BlockGetter level, BlockState state, BlockPos pos) {
        BlockPos originPos = fufu$originPos(state, pos);
        BlockState originState = level.getBlockState(originPos);
        if (originState.getBlock() == this && isOriginPart(originState)) {
            return new Origin(this, originState, originPos);
        }
        return null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
    }

    private record Origin(AutoSimpleMultiBlock block, BlockState state, BlockPos pos) {
    }
}
