package dev.savewatch.monitor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ClientTickEvent;

/** Client-only tick hooks. Kept separate so dedicated servers never load client classes. */
@EventBusSubscriber(modid = "savewatch", value = Dist.CLIENT)
public final class ClientWatchdogEvents {
    private ClientWatchdogEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        WatchdogMonitor.onClientTickPre();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        WatchdogMonitor.onClientTickPost();
    }
}
