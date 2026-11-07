package dev.savewatch.monitor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

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

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        WatchdogMonitor.onRenderFramePre();
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        WatchdogMonitor.onRenderFramePost();
    }
}
