package com.kltyton.fufulib.api;

import com.kltyton.fufulib.api.core.FufuShapeMode;
import com.kltyton.fufulib.config.FufuLibConfig;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public interface AutoModelShapeProvider {
    ResourceLocation fufu$modelId(BlockState state);

    default Direction fufu$facing(BlockState state) {
        return Direction.NORTH;
    }

    default FufuShapeMode fufu$shapeMode(BlockState state) {
        return FufuShapeMode.fromConfig(FufuLibConfig.SHAPE_MODE.get());
    }
}

