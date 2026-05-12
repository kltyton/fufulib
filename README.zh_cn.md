# FufuLib

[English](README.md) | 简体中文

FufuLib 是一个面向 Forge 1.20.1 的库 mod，用于从方块模型自动生成碰撞形状，并优化复杂 `VoxelShape` 的运行时性能。

它提供两个主要功能：

- 根据原版方块模型 JSON 自动生成 `VoxelShape`。
- 优化复杂 `VoxelShape.clip(...)` 和 `VoxelShape.forAllEdges(...)` 的运行时开销。

## 环境要求

- Minecraft `1.20.1`
- Forge `47.x`
- Java `17`

## 构建

```powershell
.\gradlew.bat build
```

重混淆后的 mod jar 会生成在 `build/libs` 目录。

## 代码结构

```text
com.kltyton.fufulib
  api      自动模型碰撞形状的公开 API
  config   Forge common 配置
  shape    形状缓存、模型解析和性能分析内部实现
  mixin    VoxelShape 运行时优化 mixin
```

mod id：

```text
fufulib
```

## 自动模型碰撞形状

如果你的方块是普通水平朝向方块，可以直接继承 `AutoModelShapeBlock`：

```java
public static final RegistryObject<Block> MY_BLOCK = BLOCKS.register("my_block",
        () -> new AutoModelShapeBlock(
                new ResourceLocation(MODID, "my_block"),
                BlockBehaviour.Properties.copy(Blocks.STONE)));
```

构造器收到方块 id 时，FufuLib 会自动解析模型路径：

```text
assets/<namespace>/models/block/<path>.json
```

如果你的方块已经继承了其他父类，可以实现 `AutoModelShapeProvider`，并把形状方法委托给 `FufuAutoShapes`：

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

需要时，也可以用同样方式重写 `getCollisionShape(...)` 和 `getInteractionShape(...)`。

## 多格碰撞

FufuLib 不会自动创建代理方块。放置、破坏、掉落、同步和归属规则通常都和具体 mod 逻辑绑定。

你可以使用这些辅助方法接入自己的代理方块系统：

```java
if (FufuAutoShapes.isMultiBlock(this, state)) {
    FufuAutoShapes.forEachOccupiedCell(this, state, (dx, dy, dz) -> {
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        // 在这里检查或放置你自己的代理方块。
    });
}
```

查询某个局部格子的形状：

```java
VoxelShape localShape = FufuAutoShapes.localShape(provider, originState, dx, dy, dz);
boolean occupied = FufuAutoShapes.hasLocalShape(provider, originState, dx, dy, dz);
```

## 配置

Forge 会生成 common 配置文件：

```text
config/fufulib-common.toml
```

主要选项：

- `shapeMode`：`full`、`bounds` 或 `block`。
- `rotatedElementVoxelStep16`：旋转模型元素的体素化精度。
- `multiblockBoundsSnap16`：把极小的模型越界吸附到方块格边界。
- `persistentModelShapeCache`：把生成后的 AABB 数据保存到磁盘。
- `optimizeVoxelShapeClip`：启用准星射线检测缓存优化。
- `optimizeVoxelShapeEdges`：启用选中描边边线遍历缓存。
- `shapeProfiler`：启用低频性能日志。

临时开启性能分析的 JVM 参数：

```text
-Dfufulib.shapeProfiler=true
-Dfufulib.shapeProfilerIntervalMs=3000
```

## 注意事项

- 自动模型碰撞只解析原版 Java 方块模型 JSON 的 `elements`。
- 不解析 OBJ、glTF 等网格模型格式。
- `VoxelShape` 优化是全局 mixin，因为目标是原版 `VoxelShape`。
- 如果其他 mod 也修改 `VoxelShape`，需要额外做兼容性测试。
