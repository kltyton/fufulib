package com.kltyton.fufulib;

import com.kltyton.fufulib.config.FufuLibConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

@Mod(Fufulib.MODID)
public class Fufulib {
    public static final String MODID = "fufulib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Fufulib() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FufuLibConfig.COMMON_SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }


    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }
}
