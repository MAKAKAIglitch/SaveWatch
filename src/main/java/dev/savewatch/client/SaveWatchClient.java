package dev.savewatch.client;

import dev.savewatch.SaveWatch;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entrypoint for the standard NeoForge configuration screen. */
@Mod(value = SaveWatch.MOD_ID, dist = Dist.CLIENT)
public final class SaveWatchClient {
    public SaveWatchClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
