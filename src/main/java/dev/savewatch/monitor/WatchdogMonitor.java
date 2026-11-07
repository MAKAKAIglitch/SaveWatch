package dev.savewatch.monitor;

import com.mojang.brigadier.CommandDispatcher;
import dev.savewatch.report.HtmlReportGenerator;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * Watches server and client tick progress from a separate daemon thread.
 * Thread snapshots are captured while a stall is still happening.
 */
public final class WatchdogMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger("SaveWatch");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final AtomicLong SERVER_TICK_STARTED_NANOS = new AtomicLong();
    private static final AtomicLong CLIENT_TICK_STARTED_NANOS = new AtomicLong();
    private static final AtomicLong RENDER_FRAME_STARTED_NANOS = new AtomicLong();
    private static final AtomicLong LAST_SERVER_TICK_NANOS = new AtomicLong();
    private static final AtomicLong LAST_CLIENT_TICK_NANOS = new AtomicLong();
    private static final AtomicLong LAST_RENDER_FRAME_NANOS = new AtomicLong();
    private static final AtomicLong TICK_COUNT = new AtomicLong();
    private static final AtomicLong SNAPSHOT_SEQUENCE = new AtomicLong();

    // A bounded serial queue prevents simultaneous server/client/render stalls from
    // silently discarding snapshots while keeping all filesystem work off game threads.
    private static final ThreadPoolExecutor SNAPSHOT_WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "SaveWatch Snapshot Writer");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static final AtomicLong LAST_SERVER_DUMP_NANOS = new AtomicLong();
    private static final AtomicLong LAST_CLIENT_DUMP_NANOS = new AtomicLong();
    private static final AtomicLong LAST_RENDER_DUMP_NANOS = new AtomicLong();
    private static final AtomicLong SERVER_DUMPS_THIS_STALL = new AtomicLong();
    private static final AtomicLong CLIENT_DUMPS_THIS_STALL = new AtomicLong();
    private static final AtomicLong RENDER_DUMPS_THIS_STALL = new AtomicLong();

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean REPORT_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean REPORT_WRITING = new AtomicBoolean();
    private static final AtomicBoolean REPORT_SCHEDULER_RUNNING = new AtomicBoolean();

    private static volatile Thread serverThread;
    private static volatile Thread clientThread;
    private static volatile Thread renderThread;
    private static volatile long stallThresholdMs = 2_000;
    private static volatile long repeatIntervalMs = 2_000;
    private static volatile int maxDumpsPerStall = 20;
    private static volatile int stackDepth = 32;
    private static volatile boolean jfrEnabled = true;
    private static volatile long jfrMaxAgeSeconds = 60;
    private static volatile long jfrMaxSizeMiB = 64;
    private static volatile int jfrMaxExports = 4;

    private WatchdogMonitor() {}

    public static void start() {
        loadConfig();
        if (!STARTED.compareAndSet(false, true)) return;
        JfrRecorder.start(jfrEnabled, jfrMaxAgeSeconds, jfrMaxSizeMiB);

        Thread watchdog = new Thread(WatchdogMonitor::watchLoop, "SaveWatch Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.NORM_PRIORITY - 1);
        watchdog.start();

        LOGGER.info("SaveWatch 0.3.0 started. Server/client stall threshold={}ms, repeat={}ms.",
                stallThresholdMs, repeatIntervalMs);
    }

    public static void onServerTickPre(ServerTickEvent.Pre event) {
        serverThread = Thread.currentThread();
        TICK_COUNT.incrementAndGet();
        SERVER_TICK_STARTED_NANOS.set(System.nanoTime());
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        long started = SERVER_TICK_STARTED_NANOS.getAndSet(0);
        if (started != 0) LAST_SERVER_TICK_NANOS.set(System.nanoTime() - started);
        LAST_SERVER_DUMP_NANOS.set(0);
        if (SERVER_DUMPS_THIS_STALL.getAndSet(0) > 0) queueHtmlReport();
    }

    /** Called by the client-only event subscriber. */
    public static void onClientTickPre() {
        clientThread = Thread.currentThread();
        CLIENT_TICK_STARTED_NANOS.set(System.nanoTime());
    }

    /** Called by the client-only event subscriber. */
    public static void onClientTickPost() {
        long started = CLIENT_TICK_STARTED_NANOS.getAndSet(0);
        if (started != 0) LAST_CLIENT_TICK_NANOS.set(System.nanoTime() - started);
        LAST_CLIENT_DUMP_NANOS.set(0);
        if (CLIENT_DUMPS_THIS_STALL.getAndSet(0) > 0) queueHtmlReport();
    }

    /** Called at the start/end of a GameRenderer frame on the physical client. */
    public static void onRenderFramePre() {
        renderThread = Thread.currentThread();
        RENDER_FRAME_STARTED_NANOS.set(System.nanoTime());
    }

    public static void onRenderFramePost() {
        long started = RENDER_FRAME_STARTED_NANOS.getAndSet(0);
        if (started != 0) LAST_RENDER_FRAME_NANOS.set(System.nanoTime() - started);
        LAST_RENDER_DUMP_NANOS.set(0);
        if (RENDER_DUMPS_THIS_STALL.getAndSet(0) > 0) queueHtmlReport();
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
                    boolean queued = queueSnapshot("MANUAL_COMMAND", currentTickDurationMs(), "command",
                            serverThread != null ? serverThread : (clientThread != null ? clientThread : renderThread));
                    if (queued) {
                        context.getSource().sendSuccess(
                                () -> Component.literal("SaveWatch: diagnostic snapshot queued. Check logs/savewatch/."),
                                false);
                        return 1;
                    }
                    context.getSource().sendFailure(Component.literal(
                            "SaveWatch: snapshot queue is full; try again in a moment."));
                    return 0;
                }))
                .then(Commands.literal("report").executes(context -> {
                    queueHtmlReport();
                    context.getSource().sendSuccess(
                            () -> Component.literal("SaveWatch: HTML report queued. It will be saved to logs/savewatch/index.html."),
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

            checkStall("LONG_SERVER_TICK", SERVER_TICK_STARTED_NANOS.get(),
                    LAST_SERVER_DUMP_NANOS, SERVER_DUMPS_THIS_STALL, serverThread);
            checkStall("LONG_CLIENT_TICK", CLIENT_TICK_STARTED_NANOS.get(),
                    LAST_CLIENT_DUMP_NANOS, CLIENT_DUMPS_THIS_STALL, clientThread);
            checkStall("LONG_RENDER_FRAME", RENDER_FRAME_STARTED_NANOS.get(),
                    LAST_RENDER_DUMP_NANOS, RENDER_DUMPS_THIS_STALL, renderThread);
        }
    }

    private static void checkStall(String reason, long started, AtomicLong lastDump,
                                   AtomicLong dumpCount, Thread targetThread) {
        if (started == 0) return;

        long now = System.nanoTime();
        long elapsedMs = (now - started) / 1_000_000L;
        if (elapsedMs < stallThresholdMs || dumpCount.get() >= maxDumpsPerStall) return;

        long previousDump = lastDump.get();
        if (previousDump != 0 && now - previousDump < repeatIntervalMs * 1_000_000L) return;
        if (!lastDump.compareAndSet(previousDump, now)) return;

        dumpCount.incrementAndGet();
        String trigger = switch (reason) {
            case "LONG_SERVER_TICK" -> "server";
            case "LONG_CLIENT_TICK" -> "client";
            default -> "render";
        };
        if (!queueSnapshot(reason, elapsedMs, trigger, targetThread)) {
            dumpCount.decrementAndGet();
            lastDump.compareAndSet(now, previousDump);
        }
    }

    private static long currentTickDurationMs() {
        long serverStarted = SERVER_TICK_STARTED_NANOS.get();
        if (serverStarted != 0) return (System.nanoTime() - serverStarted) / 1_000_000L;
        long clientStarted = CLIENT_TICK_STARTED_NANOS.get();
        if (clientStarted != 0) return (System.nanoTime() - clientStarted) / 1_000_000L;
        long renderStarted = RENDER_FRAME_STARTED_NANOS.get();
        if (renderStarted != 0) return (System.nanoTime() - renderStarted) / 1_000_000L;
        return Math.max(Math.max(LAST_SERVER_TICK_NANOS.get(), LAST_CLIENT_TICK_NANOS.get()),
                LAST_RENDER_FRAME_NANOS.get()) / 1_000_000L;
    }

    private static long activeDurationMs(long startedNanos, long nowNanos) {
        return startedNanos == 0 ? 0 : Math.max(0, (nowNanos - startedNanos) / 1_000_000L);
    }

    private static String statusText() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        boolean inServerTick = SERVER_TICK_STARTED_NANOS.get() != 0;
        boolean inClientTick = CLIENT_TICK_STARTED_NANOS.get() != 0;
        long now = System.nanoTime();
        long currentServerMs = activeDurationMs(SERVER_TICK_STARTED_NANOS.get(), now);
        long currentClientMs = activeDurationMs(CLIENT_TICK_STARTED_NANOS.get(), now);
        long currentRenderMs = activeDurationMs(RENDER_FRAME_STARTED_NANOS.get(), now);
        return "SaveWatch 0.4.0 | observed server ticks=" + TICK_COUNT.get()
                + " | last server tick=" + LAST_SERVER_TICK_NANOS.get() / 1_000_000L + "ms"
                + " | last client tick=" + LAST_CLIENT_TICK_NANOS.get() / 1_000_000L + "ms"
                + " | last render frame=" + LAST_RENDER_FRAME_NANOS.get() / 1_000_000L + "ms"
                + (currentServerMs > 0 ? " | current server tick=" + currentServerMs + "ms" : "")
                + (currentClientMs > 0 ? " | current client tick=" + currentClientMs + "ms" : "")
                + (currentRenderMs > 0 ? " | current render frame=" + currentRenderMs + "ms" : "")
                + " | snapshot queue=" + SNAPSHOT_WRITER.getQueue().size()
                + " | heap used=" + formatMiB(used) + " MiB"
                + " | heap max=" + formatMiB(runtime.maxMemory()) + " MiB";
    }

    private static boolean queueSnapshot(String reason, long tickDurationMs, String trigger,
                                         Thread targetThread) {
        try {
            SNAPSHOT_WRITER.execute(() -> {
                try {
                    writeSnapshot(reason, tickDurationMs, trigger, targetThread);
                } finally {
                    if ("MANUAL_COMMAND".equals(reason)) queueHtmlReport();
                    else if (REPORT_REQUESTED.get()) scheduleReportWorker();
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("Snapshot queue is full; diagnostic event was not queued (reason={}).", reason);
            return false;
        }
    }

    private static void writeSnapshot(String reason, long tickDurationMs, String trigger,
                                      Thread targetThread) {
        try {
            long captureStarted = System.nanoTime();
            Path directory = Path.of("logs", "savewatch");
            Files.createDirectories(directory);
            long sequence = SNAPSHOT_SEQUENCE.incrementAndGet();
            String suffix = FILE_TIME.format(LocalDateTime.now()) + "-" + sequence;
            String jfrFile = JfrRecorder.dumpIfDue(directory.resolve("savewatch-" + suffix + ".jfr"), 30_000, jfrMaxExports);
            String report = buildReport(reason, tickDurationMs, trigger, targetThread, captureStarted, jfrFile);
            String filename = "savewatch-" + suffix + ".txt";
            Path output = directory.resolve(filename);
            Files.writeString(output, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            LOGGER.warn("Diagnostic snapshot written: {} (reason={}, tick={}ms)",
                    output.toString().replace('\\', '/'), reason, tickDurationMs);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not write diagnostic snapshot.", exception);
        }
    }

    private static String buildReport(String reason, long tickDurationMs, String trigger,
                                      Thread targetThread, long captureStarted, String jfrFile) {
        StringBuilder out = new StringBuilder(32_768);
        out.append("SaveWatch diagnostic report\n")
                .append("version=0.3.0\n")
                .append("reason=").append(reason).append('\n')
                .append("trigger=").append(trigger).append('\n')
                .append("timestamp=").append(LocalDateTime.now()).append('\n')
                .append("tickDurationMs=").append(tickDurationMs).append('\n')
                .append("observedTicks=").append(TICK_COUNT.get()).append('\n')
                .append("lastCompletedServerTickMs=").append(LAST_SERVER_TICK_NANOS.get() / 1_000_000L).append('\n')
                .append("lastCompletedClientTickMs=").append(LAST_CLIENT_TICK_NANOS.get() / 1_000_000L).append('\n')
                .append("lastCompletedRenderFrameMs=").append(LAST_RENDER_FRAME_NANOS.get() / 1_000_000L).append('\n')
                .append("serverThread=").append(threadName(serverThread)).append('\n')
                .append("clientThread=").append(threadName(clientThread)).append('\n')
                .append("renderThread=").append(threadName(renderThread)).append('\n')
                .append("triggerThread=").append(threadName(targetThread)).append('\n')
                .append("triggerThreadId=").append(targetThread == null ? -1 : targetThread.getId()).append('\n')
                .append("thresholdMs=").append(stallThresholdMs).append('\n')
                .append("jfrFile=").append(jfrFile).append('\n');

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

        out.append("\n[DIAGNOSTICS]\n");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = null;
        try {
            deadlockedIds = threadBean.findDeadlockedThreads();
        } catch (UnsupportedOperationException | SecurityException exception) {
            LOGGER.debug("JVM does not permit deadlock detection.", exception);
        }
        out.append("deadlockDetected=").append(deadlockedIds != null && deadlockedIds.length > 0).append('\n')
                .append("deadlockedThreadCount=").append(deadlockedIds == null ? 0 : deadlockedIds.length).append('\n');
        if (deadlockedIds != null && deadlockedIds.length > 0) {
            out.append("\n[DEADLOCKS]\n");
            ThreadInfo[] deadlocked = threadBean.getThreadInfo(deadlockedIds, true, true);
            for (ThreadInfo info : deadlocked) {
                if (info == null) continue;
                out.append("thread=").append(info.getThreadName()).append(" id=").append(info.getThreadId())
                        .append(" state=").append(info.getThreadState())
                        .append(" waitingOn=").append(info.getLockName())
                        .append(" lockOwner=").append(info.getLockOwnerName()).append('\n');
                for (StackTraceElement frame : info.getStackTrace()) out.append("    at ").append(frame).append('\n');
            }
        }

        out.append("\n[THREADS]\n");
        long[] allIds = threadBean.getAllThreadIds();
        long targetId = targetThread == null ? -1L : targetThread.getId();

        // Put the stalled thread first, then collect every live JVM thread.
        long[] orderedIds = new long[allIds.length + (targetId > 0 ? 1 : 0)];
        int selected = 0;
        if (targetId > 0) orderedIds[selected++] = targetId;
        for (long id : allIds) {
            if (id != targetId) orderedIds[selected++] = id;
        }
        if (selected != orderedIds.length) {
            long[] trimmed = new long[selected];
            System.arraycopy(orderedIds, 0, trimmed, 0, selected);
            orderedIds = trimmed;
        }

        // This overload captures stacks and basic lock-owner information without
        // asking the JVM to enumerate every locked monitor/synchronizer.
        ThreadInfo[] infos = threadBean.getThreadInfo(orderedIds, stackDepth);
        int capturedThreads = 0;
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            capturedThreads++;
            long cpuNanos = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled()
                    ? threadBean.getThreadCpuTime(info.getThreadId()) : -1L;
            out.append("\n--- ").append(info.getThreadName())
                    .append(" | id=").append(info.getThreadId())
                    .append(" | state=").append(info.getThreadState())
                    .append(" | cpuTimeMs=").append(cpuNanos < 0 ? "unavailable" : cpuNanos / 1_000_000L);
            if (info.getLockName() != null) out.append(" | waitingOn=").append(info.getLockName());
            if (info.getLockOwnerName() != null) out.append(" | lockOwner=").append(info.getLockOwnerName());
            out.append(" ---\n");
            for (StackTraceElement frame : info.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
        }

        out.append("\nthreadCount=").append(capturedThreads).append('\n')
                .append("stackDepth=").append(stackDepth).append('\n')
                .append("collectionDurationMs=").append((System.nanoTime() - captureStarted) / 1_000_000L).append('\n')
                .append("\n[END]\n");
        return out.toString();
    }

    /** Queues an offline HTML report without doing filesystem work on a game thread. */
    private static void queueHtmlReport() {
        REPORT_REQUESTED.set(true);
        scheduleReportWorker();
    }

    /**
     * Waits off-thread until the snapshot queue is fully drained before starting
     * the HTML generator. Calling it from a snapshot task's finally block would
     * still see that task as active and could otherwise leave the report pending.
     */
    private static void scheduleReportWorker() {
        if (!REPORT_SCHEDULER_RUNNING.compareAndSet(false, true)) return;
        Thread scheduler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (SNAPSHOT_WRITER.getActiveCount() == 0 && SNAPSHOT_WRITER.getQueue().isEmpty()) {
                        startReportWorker();
                        return;
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                REPORT_SCHEDULER_RUNNING.set(false);
                if (REPORT_REQUESTED.get() && SNAPSHOT_WRITER.getActiveCount() == 0
                        && SNAPSHOT_WRITER.getQueue().isEmpty()) startReportWorker();
            }
        }, "SaveWatch Report Scheduler");
        scheduler.setDaemon(true);
        scheduler.setPriority(Thread.NORM_PRIORITY - 1);
        scheduler.start();
    }

    private static void startReportWorker() {
        if (SNAPSHOT_WRITER.getActiveCount() > 0 || !SNAPSHOT_WRITER.getQueue().isEmpty()
                || !REPORT_WRITING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                while (REPORT_REQUESTED.getAndSet(false)) {
                    try {
                        Path output = HtmlReportGenerator.generate(Path.of("logs", "savewatch"));
                        LOGGER.info("HTML diagnostic report generated: {}", output.toString().replace('\\', '/'));
                    } catch (IOException | RuntimeException exception) {
                        LOGGER.warn("Could not generate SaveWatch HTML report.", exception);
                    }
                }
            } finally {
                REPORT_WRITING.set(false);
                if (REPORT_REQUESTED.get() && SNAPSHOT_WRITER.getActiveCount() == 0
                        && SNAPSHOT_WRITER.getQueue().isEmpty()) startReportWorker();
            }
        }, "SaveWatch HTML Report Writer");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        try {
            worker.start();
        } catch (RuntimeException exception) {
            REPORT_WRITING.set(false);
            LOGGER.error("Could not start SaveWatch HTML report worker.", exception);
        }
    }

    private static String threadName(Thread thread) {
        return thread == null ? "not observed" : thread.getName();
    }

    private static void loadConfig() {
        Path config = Path.of("config", "savewatch.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(config.getParent());
            if (Files.notExists(config)) {
                properties.setProperty("stallThresholdMs", "2000");
                properties.setProperty("repeatIntervalMs", "2000");
                properties.setProperty("maxDumpsPerStall", "20");
                properties.setProperty("stackDepth", "32");
                properties.setProperty("jfrEnabled", "true");
                properties.setProperty("jfrMaxAgeSeconds", "60");
                properties.setProperty("jfrMaxSizeMiB", "64");
                properties.setProperty("jfrMaxExports", "4");
                properties.setProperty("configVersion", "3");
                try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    properties.store(writer, "SaveWatch 0.4.0 settings");
                }
            }
            try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            // Add new settings without overwriting values customized by the user.
            boolean configChanged = false;
            if (!properties.containsKey("configVersion")) {
                if ("5000".equals(properties.getProperty("repeatIntervalMs"))) {
                    properties.setProperty("repeatIntervalMs", "2000");
                }
                if ("48".equals(properties.getProperty("stackDepth"))) {
                    properties.setProperty("stackDepth", "32");
                }
                configChanged = true;
            }
            if (!properties.containsKey("jfrEnabled")) {
                properties.setProperty("jfrEnabled", "true");
                configChanged = true;
            }
            if (!properties.containsKey("jfrMaxAgeSeconds")) {
                properties.setProperty("jfrMaxAgeSeconds", "60");
                configChanged = true;
            }
            if (!properties.containsKey("jfrMaxSizeMiB")) {
                properties.setProperty("jfrMaxSizeMiB", "64");
                configChanged = true;
            }
            if (!properties.containsKey("jfrMaxExports")) {
                properties.setProperty("jfrMaxExports", "4");
                configChanged = true;
            }
            if (!"3".equals(properties.getProperty("configVersion"))) {
                properties.setProperty("configVersion", "3");
                configChanged = true;
            }
            if (configChanged) {
                try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    properties.store(writer, "SaveWatch 0.4.0 settings");
                }
            }

            stallThresholdMs = boundedLong(properties, "stallThresholdMs", 2_000, 250, 60_000);
            repeatIntervalMs = boundedLong(properties, "repeatIntervalMs", 2_000, 500, 60_000);
            maxDumpsPerStall = (int) boundedLong(properties, "maxDumpsPerStall", 20, 1, 100);
            stackDepth = (int) boundedLong(properties, "stackDepth", 32, 8, 128);
            jfrEnabled = Boolean.parseBoolean(properties.getProperty("jfrEnabled", "true"));
            jfrMaxAgeSeconds = boundedLong(properties, "jfrMaxAgeSeconds", 60, 10, 600);
            jfrMaxSizeMiB = boundedLong(properties, "jfrMaxSizeMiB", 64, 16, 512);
            jfrMaxExports = (int) boundedLong(properties, "jfrMaxExports", 4, 1, 20);
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
