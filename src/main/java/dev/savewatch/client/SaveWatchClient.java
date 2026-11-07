package dev.savewatch.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Registers the standard NeoForge config screen used by the vanilla and Better ModList menus. */
public final class SaveWatchClient {
    private SaveWatchClient() {}

    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new ConfigurationScreen(container, parent));
    }
}
