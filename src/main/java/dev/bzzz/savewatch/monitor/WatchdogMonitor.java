package dev.bzzz.savewatch.monitor;

import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes the logical server thread from a separate daemon thread.
 * Snapshots are taken while a long tick is still running.
 */
public final class WatchdogMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger("SaveWatch");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final AtomicLong TICK_STARTED_NANOS = new AtomicLong();
    private static final AtomicLong LAST_TICK_NANOS = new AtomicLong();
    private static final AtomicLong TICK_COUNT = new AtomicLong();
    private static final AtomicLong LAST_DUMP_NANOS = new AtomicLong();
    private static final AtomicLong DUMPS_THIS_STALL = new AtomicLong();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean WRITING = new AtomicBoolean();

    private static volatile Thread serverThread;
    private static volatile long stallThresholdMs = 2_000;
    private static volatile long repeatIntervalMs = 5_000;
    private static volatile int maxDumpsPerStall = 20;
    private static volatile int stackDepth = 48;

    private WatchdogMonitor() {}

    public static void start() {
        loadConfig();
        if (!STARTED.compareAndSet(false, true)) return;

        Thread watchdog = new Thread(WatchdogMonitor::watchLoop, "SaveWatch Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.NORM_PRIORITY - 1);
        watchdog.start();

        LOGGER.info("SaveWatch 0.1.0 started. Long-tick threshold={}ms, repeat={}ms.",
                stallThresholdMs, repeatIntervalMs);
    }

    public static void onServerTickPre(ServerTickEvent.Pre event) {
        serverThread = Thread.currentThread();
        TICK_COUNT.incrementAndGet();
        TICK_STARTED_NANOS.set(System.nanoTime());
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        long started = TICK_STARTED_NANOS.getAndSet(0);
        if (started != 0) LAST_TICK_NANOS.set(System.nanoTime() - started);
        LAST_DUMP_NANOS.set(0);
        DUMPS_THIS_STALL.set(0);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("savewatch")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(statusText()), false);
                    return 1;
                }))
                .then(Commands.literal("dump").executes(context -> {
                    requestManualDump();
                    context.getSource().sendSuccess(
                            () -> Component.literal("SaveWatch: diagnostic snapshot queued. Check logs/savewatch/."),
                            false);
                    return 1;
                })));
    }

    private static void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }

            long started = TICK_STARTED_NANOS.get();
            if (started == 0) continue;

            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (elapsedMs < stallThresholdMs) continue;

            long now = System.nanoTime();
            long previousDump = LAST_DUMP_NANOS.get();
            if (previousDump != 0 && now - previousDump < repeatIntervalMs * 1_000_000L) continue;
            if (DUMPS_THIS_STALL.get() >= maxDumpsPerStall) continue;
            if (!LAST_DUMP_NANOS.compareAndSet(previousDump, now)) continue;

            DUMPS_THIS_STALL.incrementAndGet();
            writeSnapshot("LONG_SERVER_TICK", elapsedMs);
        }
    }

    private static void requestManualDump() {
        Thread writer = new Thread(
                () -> writeSnapshot("MANUAL_COMMAND", currentTickDurationMs()),
                "SaveWatch Manual Snapshot");
        writer.setDaemon(true);
        writer.start();
    }

    private static long currentTickDurationMs() {
        long started = TICK_STARTED_NANOS.get();
        return started == 0 ? LAST_TICK_NANOS.get() / 1_000_000L
                : (System.nanoTime() - started) / 1_000_000L;
    }

    private static String statusText() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        boolean inTick = TICK_STARTED_NANOS.get() != 0;
        return "SaveWatch 0.1.0 | observed ticks=" + TICK_COUNT.get()
                + " | last tick=" + LAST_TICK_NANOS.get() / 1_000_000L + "ms"
                + (inTick ? " | current tick=" + currentTickDurationMs() + "ms" : "")
                + " | heap used=" + formatMiB(used) + " MiB"
                + " | heap max=" + formatMiB(runtime.maxMemory()) + " MiB";
    }

    private static void writeSnapshot(String reason, long tickDurationMs) {
        if (!WRITING.compareAndSet(false, true)) return;
        try {
            String report = buildReport(reason, tickDurationMs);
            Path directory = Path.of("logs", "savewatch");
            Files.createDirectories(directory);
            String filename = "savewatch-" + FILE_TIME.format(LocalDateTime.now()) + ".txt";
            Path output = directory.resolve(filename);
            Files.writeString(output, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            LOGGER.warn("Diagnostic snapshot written: {} (reason={}, tick={}ms)",
                    output.toString().replace('\\', '/'), reason, tickDurationMs);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not write diagnostic snapshot.", exception);
        } finally {
            WRITING.set(false);
        }
    }

    private static String buildReport(String reason, long tickDurationMs) {
        StringBuilder out = new StringBuilder(16_384);
        out.append("SaveWatch diagnostic report\n")
                .append("version=0.1.0\n")
                .append("reason=").append(reason).append('\n')
                .append("timestamp=").append(LocalDateTime.now()).append('\n')
                .append("tickDurationMs=").append(tickDurationMs).append('\n')
                .append("observedTicks=").append(TICK_COUNT.get()).append('\n')
                .append("lastCompletedTickMs=").append(LAST_TICK_NANOS.get() / 1_000_000L).append('\n')
                .append("serverThread=").append(serverThread == null ? "not observed" : serverThread.getName()).append('\n')
                .append("thresholdMs=").append(stallThresholdMs).append('\n');

        Runtime runtime = Runtime.getRuntime();
        out.append("\n[JVM]\n")
                .append("javaVersion=").append(System.getProperty("java.version")).append('\n')
                .append("availableProcessors=").append(runtime.availableProcessors()).append('\n')
                .append("heapUsedMiB=").append(formatMiB(runtime.totalMemory() - runtime.freeMemory())).append('\n')
                .append("heapCommittedMiB=").append(formatMiB(runtime.totalMemory())).append('\n')
                .append("heapMaxMiB=").append(formatMiB(runtime.maxMemory())).append('\n');

        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        out.append("nonHeapUsedMiB=").append(formatMiB(nonHeap.getUsed())).append('\n');

        out.append("\n[GC]\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.append(gc.getName()).append(" count=").append(gc.getCollectionCount())
                    .append(" totalMs=").append(gc.getCollectionTime()).append('\n');
        }

        out.append("\n[THREADS]\n");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] ids = threadBean.getAllThreadIds();
        long serverThreadId = serverThread == null ? -1L : serverThread.getId();
        long[] limitedIds = new long[Math.min(ids.length + (serverThreadId > 0 ? 1 : 0), 120)];
        int selected = 0;
        if (serverThreadId > 0) limitedIds[selected++] = serverThreadId;
        for (long id : ids) {
            if (selected >= limitedIds.length) break;
            if (id != serverThreadId) limitedIds[selected++] = id;
        }
        if (selected != limitedIds.length) {
            long[] trimmed = new long[selected];
            System.arraycopy(limitedIds, 0, trimmed, 0, selected);
            limitedIds = trimmed;
        }
        ThreadInfo[] infos = threadBean.getThreadInfo(limitedIds, true, true, stackDepth);

        for (ThreadInfo info : infos) {
            if (info == null) continue;
            out.append("\n--- ").append(info.getThreadName())
                    .append(" | id=").append(info.getThreadId())
                    .append(" | state=").append(info.getThreadState());
            if (info.getLockName() != null) out.append(" | waitingOn=").append(info.getLockName());
            if (info.getLockOwnerName() != null) out.append(" | lockOwner=").append(info.getLockOwnerName());
            out.append(" ---\n");
            for (StackTraceElement frame : info.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            if (info.getLockedMonitors().length > 0) {
                out.append("    lockedMonitors=").append(info.getLockedMonitors().length).append('\n');
            }
            if (info.getLockedSynchronizers().length > 0) {
                out.append("    lockedSynchronizers=").append(info.getLockedSynchronizers().length).append('\n');
            }
        }
        out.append("\n[END]\n");
        return out.toString();
    }

    private static void loadConfig() {
        Path config = Path.of("config", "savewatch.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(config.getParent());
            if (Files.notExists(config)) {
                properties.setProperty("stallThresholdMs", "2000");
                properties.setProperty("repeatIntervalMs", "5000");
                properties.setProperty("maxDumpsPerStall", "20");
                properties.setProperty("stackDepth", "48");
                try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    properties.store(writer, "SaveWatch 0.1.0 settings");
                }
            }
            try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            stallThresholdMs = boundedLong(properties, "stallThresholdMs", 2_000, 250, 60_000);
            repeatIntervalMs = boundedLong(properties, "repeatIntervalMs", 5_000, 1_000, 60_000);
            maxDumpsPerStall = (int) boundedLong(properties, "maxDumpsPerStall", 20, 1, 100);
            stackDepth = (int) boundedLong(properties, "stackDepth", 48, 8, 128);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load config/savewatch.properties; using safe defaults.", exception);
        }
    }

    private static long boundedLong(Properties properties, String key, long fallback, long min, long max) {
        try {
            long value = Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long formatMiB(long bytes) {
        return bytes < 0 ? -1 : bytes / (1024L * 1024L);
    }
}
