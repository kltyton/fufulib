package com.kltyton.fufulib.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class FufuLibConfig {
    public static final ForgeConfigSpec COMMON_SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> SHAPE_MODE;
    public static final ForgeConfigSpec.DoubleValue ROTATED_ELEMENT_VOXEL_STEP_16;
    public static final ForgeConfigSpec.DoubleValue MULTIBLOCK_BOUNDS_SNAP_16;
    public static final ForgeConfigSpec.BooleanValue PERSISTENT_MODEL_SHAPE_CACHE;
    public static final ForgeConfigSpec.BooleanValue OPTIMIZE_VOXEL_SHAPE_CLIP;
    public static final ForgeConfigSpec.BooleanValue OPTIMIZE_VOXEL_SHAPE_EDGES;
    public static final ForgeConfigSpec.BooleanValue SHAPE_PROFILER;
    public static final ForgeConfigSpec.IntValue SHAPE_PROFILER_INTERVAL_MS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("automatic_model_shapes");

        SHAPE_MODE = builder
                .comment("EN: Model collision mode for automatic model shapes: full, bounds, or block.")
                .comment("EN: full = parse model elements and voxelize rotated elements; most accurate and recommended.")
                .comment("EN: bounds = use one bounding box around the model; faster but less accurate.")
                .comment("EN: block = always use the vanilla full-block shape.")
                .define("shapeMode", "full");

        ROTATED_ELEMENT_VOXEL_STEP_16 = builder
                .comment("EN: Voxel size, in model pixel units, used when approximating rotated model elements.")
                .comment("EN: Lower values are more accurate but generate more boxes and increase first-time cost.")
                .defineInRange("rotatedElementVoxelStep16", 1.0, 0.25, 4.0);

        MULTIBLOCK_BOUNDS_SNAP_16 = builder
                .comment("EN: Snaps multiblock bounds to block-cell edges when overhang is within this many model pixels.")
                .comment("EN: This avoids tiny accidental model offsets creating extra proxy collision blocks.")
                .defineInRange("multiblockBoundsSnap16", 4.0, 0.0, 16.0);

        PERSISTENT_MODEL_SHAPE_CACHE = builder
                .comment("EN: Saves generated model-shape AABB data to disk and reloads it on the next launch.")
                .comment("EN: VoxelShape objects are still built lazily when blocks are placed or queried.")
                .define("persistentModelShapeCache", true);

        builder.pop();
        builder.push("voxel_shape_optimizations");

        OPTIMIZE_VOXEL_SHAPE_CLIP = builder
                .comment("EN: Optimizes VoxelShape.clip by caching AABBs and using a small spatial grid for complex shapes.")
                .comment("EN: Keeps precise ray picking while reducing per-frame block targeting cost.")
                .define("optimizeVoxelShapeClip", true);

        OPTIMIZE_VOXEL_SHAPE_EDGES = builder
                .comment("EN: Optimizes VoxelShape.forAllEdges by caching computed edge coordinates.")
                .comment("EN: This is the key fix for FPS drops when Minecraft renders the black selection outline.")
                .define("optimizeVoxelShapeEdges", true);

        SHAPE_PROFILER = builder
                .comment("EN: Enables low-frequency shape performance logs for debugging.")
                .comment("EN: Useful fields: avgClip, maxClip, avgEdges, maxEdges, avgTested.")
                .define("shapeProfiler", false);

        SHAPE_PROFILER_INTERVAL_MS = builder
                .comment("EN: Profiler report interval in milliseconds.")
                .defineInRange("shapeProfilerIntervalMs", 5000, 1000, 60000);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    private FufuLibConfig() {
    }
}

