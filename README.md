# FufuLib

English | [简体中文](README.zh_cn.md)

FufuLib is a Forge 1.20.1 library mod for model-driven collision shapes and runtime `VoxelShape` optimization.

It provides two main features:

- Automatic `VoxelShape` generation from vanilla block model JSON files.
- Runtime acceleration for complex `VoxelShape.clip(...)` and `VoxelShape.forAllEdges(...)` calls.
- A simple automatic multi-block base block driven by the generated model shape.

## Requirements

- Minecraft `1.20.1`
- Forge `47.x`
- Java `17`

## Build

```powershell
.\gradlew.bat build
```

The reobfuscated mod jar is generated under `build/libs`.

## Package Layout

```text
com.kltyton.fufulib
  api          Public API for automatic model shapes
  block.base   Base blocks for automatic shapes and simple multi-blocks
  config       Forge common config
  shape        Shape cache, model parser, and profiler internals
  mixin        Runtime VoxelShape optimization mixins
```

The mod id is:

```text
fufulib
```

## Automatic Model Shapes

For a simple horizontal block, extend `AutoModelShapeBlock`:

```java
public static final RegistryObject<Block> MY_BLOCK = BLOCKS.register("my_block",
        () -> new AutoModelShapeBlock(
                new ResourceLocation(MODID, "my_block"),
                BlockBehaviour.Properties.copy(Blocks.STONE)));
```

When the constructor receives a block id, FufuLib resolves the model path as:

```text
assets/<namespace>/models/block/<path>.json
```

If you already have a custom block superclass, implement `AutoModelShapeProvider` and delegate shape methods:

```java
public class MyDecorBlock extends HorizontalDirectionalBlock implements AutoModelShapeProvider {
    private final ResourceLocation modelId;

    public MyDecorBlock(ResourceLocation blockId, Properties properties) {
        super(properties);
        this.modelId = FufuAutoShapes.defaultBlockModelId(blockId);
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
}
```

You can also override `getCollisionShape(...)` and `getInteractionShape(...)` with the same delegate when needed.

## Simple Automatic Multi-Blocks

`AutoSimpleMultiBlock` is a base block for door-like or bed-like structures where the whole object should behave as one block, but its occupied cells are derived from the model shape automatically.

Register it like a normal block:

```java
public static final RegistryObject<Block> MY_BLOCK = BLOCKS.register("my_block",
        () -> new AutoSimpleMultiBlock(
                new ResourceLocation(MODID, "my_block"),
                BlockBehaviour.Properties.copy(Blocks.STONE).noOcclusion()));
```

The block uses the generated shape to decide which extra cells are occupied. On placement it checks every occupied cell and places linked parts of the same block, similar to how vanilla doors use one block type for both halves. Breaking any linked part destroys the origin part and drops the block once. Removing the origin part removes all linked parts without extra drops.

## Multi-Block Shapes

For advanced multi-blocks, FufuLib still exposes low-level helpers. Placement, removal, drops, synchronization, and ownership rules can remain fully mod-specific.

Use the provided helpers to integrate with your own proxy block system:

```java
if (FufuAutoShapes.isMultiBlock(this, state)) {
    FufuAutoShapes.forEachOccupiedCell(this, state, (dx, dy, dz) -> {
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        // Check or place your own proxy block here.
    });
}
```

To query one local cell:

```java
VoxelShape localShape = FufuAutoShapes.localShape(provider, originState, dx, dy, dz);
boolean occupied = FufuAutoShapes.hasLocalShape(provider, originState, dx, dy, dz);
```

## Configuration

Forge creates the common config at:

```text
config/fufulib-common.toml
```

Important options:

- `shapeMode`: `full`, `bounds`, or `block`.
- `rotatedElementVoxelStep16`: voxelization precision for rotated model elements.
- `multiblockBoundsSnap16`: snaps tiny model overhangs to block-cell edges.
- `persistentModelShapeCache`: saves generated AABB data to disk.
- `optimizeVoxelShapeClip`: enables cached ray-picking acceleration.
- `optimizeVoxelShapeEdges`: enables cached selection-outline edge traversal.
- `shapeProfiler`: enables low-frequency performance logs.

Temporary profiler JVM flags:

```text
-Dfufulib.shapeProfiler=true
-Dfufulib.shapeProfilerIntervalMs=3000
```

## Notes

- Automatic model shapes are based on vanilla Java block model JSON `elements`.
- OBJ, glTF, and other mesh formats are not parsed.
- The `VoxelShape` optimization is global because it targets vanilla `VoxelShape`.
- Other mods that also modify `VoxelShape` may require compatibility testing.
