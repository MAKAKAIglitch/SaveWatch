package dev.savewatch.client;

import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Local-only file shortcuts: OPEN_FILE click events must never be sent by a server. */
@EventBusSubscriber(modid = "savewatch", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SaveWatchClientCommands {
    private SaveWatchClientCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("savewatch")
                .then(Commands.literal("links").executes(context -> {
                    Path directory = Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("logs").resolve("savewatch").toAbsolutePath();
                    addLink("Открыть HTML-отчёт", directory.resolve("index.html"));
                    addLink("Открыть AI-сводку", directory.resolve("ai-summary.md"));
                    Path latest = latestSnapshot(directory);
                    if (latest != null) addLink("Открыть последний дамп", latest);
                    return 1;
                })));
    }

    private static void addLink(String label, Path file) {
        if (!Files.isRegularFile(file)) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal(label + ": файл пока не создан"));
            return;
        }
        Component link = Component.literal("[" + label + "]")
                .withStyle(Style.EMPTY.withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toString())));
        Minecraft.getInstance().gui.getChat().addMessage(link);
    }

    private static Path latestSnapshot(Path directory) {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .max(java.util.Comparator.comparingLong(path -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (IOException ignored) { return 0L; }
                    })).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }
}
