package dev.savewatch;

import dev.savewatch.config.SaveWatchConfig;
import dev.savewatch.monitor.WatchdogMonitor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(SaveWatch.MOD_ID)
public final class SaveWatch {
    public static final String MOD_ID = "savewatch";

    public SaveWatch(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, SaveWatchConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            net.neoforged.fml.DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> () -> dev.savewatch.client.SaveWatchClient.registerConfigScreen(modContainer));
        }

        NeoForge.EVENT_BUS.addListener(WatchdogMonitor::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(WatchdogMonitor::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(WatchdogMonitor::onRegisterCommands);
        WatchdogMonitor.start();
    }
}
