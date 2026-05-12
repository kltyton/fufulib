package com.kltyton.fufulib.mixin;

import javax.annotation.Nullable;

import com.kltyton.fufulib.config.FufuLibConfig;
import com.kltyton.fufulib.shape.ShapeProfiler;
import com.kltyton.fufulib.shape.VoxelShapeClipCache;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VoxelShape.class)
public abstract class VoxelShapeOptimizationMixin {
    @Unique
    private static final double FUFU_EPS = 1.0E-7;

    @Unique
    @Nullable
    private VoxelShapeClipCache fufuLib$clipCache;

    @Unique
    @Nullable
    private double[] fufuLib$cachedEdges;

    @Final
    @Shadow
    protected DiscreteVoxelShape shape;

    @Shadow
    protected abstract DoubleList getCoords(Direction.Axis axis);

    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    private void fufuLib$clipWithCachedBoxes(
            Vec3 from,
            Vec3 to,
            BlockPos pos,
            CallbackInfoReturnable<BlockHitResult> cir) {
        if (!FufuLibConfig.OPTIMIZE_VOXEL_SHAPE_CLIP.get()) {
            return;
        }
        long startNanos = ShapeProfiler.enabled() ? System.nanoTime() : 0L;
        VoxelShape shape = (VoxelShape) (Object) this;
        int shapeId = System.identityHashCode(shape);
        if (shape.isEmpty()) {
            if (ShapeProfiler.enabled()) {
                ShapeProfiler.recordClip(shapeId, 0, false, System.nanoTime() - startNanos, 0, 0, 0, false);
            }
            cir.setReturnValue(null);
            return;
        }

        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < FUFU_EPS) {
            if (ShapeProfiler.enabled()) {
                ShapeProfiler.recordClip(shapeId, 0, false, System.nanoTime() - startNanos, 0, 0, 0, false);
            }
            cir.setReturnValue(null);
            return;
        }

        VoxelShapeClipCache cache = fufuLib$clipCache;
        if (cache == null) {
            long buildStartNanos = ShapeProfiler.enabled() ? System.nanoTime() : 0L;
            cache = new VoxelShapeClipCache(shapeId, shape.toAabbs());
            fufuLib$clipCache = cache;
            if (ShapeProfiler.enabled()) {
                ShapeProfiler.recordClipBuild(shapeId, cache.boxCount(), cache.useGrid(), System.nanoTime() - buildStartNanos);
            }
        }
        if (cache.isEmpty()) {
            if (ShapeProfiler.enabled()) {
                ShapeProfiler.recordClip(shapeId, cache.boxCount(), cache.useGrid(), System.nanoTime() - startNanos, 0, 0, 0, false);
            }
            cir.setReturnValue(null);
            return;
        }

        double fromX = from.x - pos.getX();
        double fromY = from.y - pos.getY();
        double fromZ = from.z - pos.getZ();
        double toX = to.x - pos.getX();
        double toY = to.y - pos.getY();
        double toZ = to.z - pos.getZ();

        Vec3 nudgedFrom = from.add(delta.scale(0.001));
        double nudgedX = nudgedFrom.x - pos.getX();
        double nudgedY = nudgedFrom.y - pos.getY();
        double nudgedZ = nudgedFrom.z - pos.getZ();
        if (cache.contains(nudgedX, nudgedY, nudgedZ)) {
            if (ShapeProfiler.enabled()) {
                ShapeProfiler.recordClip(shapeId, cache.boxCount(), cache.useGrid(), System.nanoTime() - startNanos,
                        cache.lastVisitedCells(), cache.lastBucketCandidates(), cache.lastTestedBoxes(), true);
            }
            cir.setReturnValue(new BlockHitResult(
                    nudgedFrom,
                    Direction.getNearest(delta.x, delta.y, delta.z).getOpposite(),
                    pos,
                    true));
            return;
        }

        BlockHitResult result = cache.clip(from, pos, fromX, fromY, fromZ, toX, toY, toZ);
        if (ShapeProfiler.enabled()) {
            ShapeProfiler.recordClip(shapeId, cache.boxCount(), cache.useGrid(), System.nanoTime() - startNanos,
                    cache.lastVisitedCells(), cache.lastBucketCandidates(), cache.lastTestedBoxes(), result != null);
        }
        cir.setReturnValue(result);
    }

    @Inject(method = "forAllEdges", at = @At("HEAD"), cancellable = true)
    private void fufuLib$profileForAllEdgesStart(Shapes.DoubleLineConsumer consumer, CallbackInfo ci) {
        if (!FufuLibConfig.OPTIMIZE_VOXEL_SHAPE_EDGES.get()) {
            return;
        }
        long startNanos = ShapeProfiler.enabled() ? System.nanoTime() : 0L;
        double[] edges = fufuLib$cachedEdges;
        if (edges == null) {
            edges = fufuLib$buildEdgeCache();
            fufuLib$cachedEdges = edges;
        }
        for (int i = 0; i < edges.length; i += 6) {
            consumer.consume(edges[i], edges[i + 1], edges[i + 2], edges[i + 3], edges[i + 4], edges[i + 5]);
        }
        if (ShapeProfiler.enabled()) {
            ShapeProfiler.recordEdges(System.identityHashCode(this), System.nanoTime() - startNanos);
        }
        ci.cancel();
    }

    @Unique
    private double[] fufuLib$buildEdgeCache() {
        DoubleList xCoords = getCoords(Direction.Axis.X);
        DoubleList yCoords = getCoords(Direction.Axis.Y);
        DoubleList zCoords = getCoords(Direction.Axis.Z);
        final double[][] values = {new double[256 * 6]};
        final int[] size = {0};
        this.shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            int required = size[0] + 6;
            if (required > values[0].length) {
                double[] grown = new double[Math.max(required, values[0].length * 2)];
                System.arraycopy(values[0], 0, grown, 0, size[0]);
                values[0] = grown;
            }
            values[0][size[0]++] = xCoords.getDouble(x1);
            values[0][size[0]++] = yCoords.getDouble(y1);
            values[0][size[0]++] = zCoords.getDouble(z1);
            values[0][size[0]++] = xCoords.getDouble(x2);
            values[0][size[0]++] = yCoords.getDouble(y2);
            values[0][size[0]++] = zCoords.getDouble(z2);
        }, true);
        double[] out = new double[size[0]];
        System.arraycopy(values[0], 0, out, 0, size[0]);
        return out;
    }
}




