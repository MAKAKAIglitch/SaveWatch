package dev.savewatch.monitor;

import dev.savewatch.config.SaveWatchConfig;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental retained-heap trend detector.
 *
 * It samples the old-generation pool (or heap as a fallback) only after GC activity.
 * A sustained upward post-GC baseline is a useful leak signal, but cannot prove causality.
 */
public final class MemoryLeakAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SaveWatch");
    private static final int MAX_SAMPLES = 24;
    private static final long MIN_WINDOW_NANOS = 180_000_000_000L;

    private static final Deque<Sample> SAMPLES = new ArrayDeque<>();
    private static long lastGcCount = -1;
    private static long lastSampleNanos;
    private static long lastAlertNanos;

    private MemoryLeakAnalyzer() {}

    /** Returns a warning when a new alert is due; called from the logical server tick. */
    public static synchronized String tick(MinecraftServer server) {
        if (!SaveWatchConfig.MEMORY_LEAK_ANALYZER.get()) return null;

        long now = System.nanoTime();
        long interval = SaveWatchConfig.MEMORY_LEAK_SAMPLE_INTERVAL_SECONDS.get() * 1_000_000_000L;
        if (lastSampleNanos != 0 && now - lastSampleNanos < interval) return null;
        lastSampleNanos = now;

        long gcCount = totalGcCount();
        if (gcCount < 0 || gcCount == lastGcCount) return null;
        lastGcCount = gcCount;

        MemoryUsage usage = retainedHeapUsage();
        if (usage == null || usage.getUsed() < 0) return null;

        SAMPLES.addLast(new Sample(now, usage.getUsed()));
        while (SAMPLES.size() > MAX_SAMPLES) SAMPLES.removeFirst();

        int required = SaveWatchConfig.MEMORY_LEAK_MIN_SAMPLES.get();
        if (SAMPLES.size() < required) return null;

        Sample first = SAMPLES.getFirst();
        Sample latest = SAMPLES.getLast();
        long elapsed = latest.timeNanos - first.timeNanos;
        if (elapsed < MIN_WINDOW_NANOS) return null;

        long growthBytes = latest.usedBytes - first.usedBytes;
        long maxHeap = Runtime.getRuntime().maxMemory();
        long minimumGrowth = SaveWatchConfig.MEMORY_LEAK_MIN_GROWTH_MIB.get() * 1024L * 1024L;
        long percentageGrowth = (long) (maxHeap * (SaveWatchConfig.MEMORY_LEAK_GROWTH_PERCENT.get() / 100.0));
        long requiredGrowth = Math.max(minimumGrowth, percentageGrowth);
        double heapPercent = maxHeap <= 0 ? 0.0 : latest.usedBytes * 100.0 / maxHeap;
        double minimumHeapPercent = SaveWatchConfig.MEMORY_LEAK_MIN_HEAP_PERCENT.get();

        if (growthBytes < requiredGrowth || heapPercent < minimumHeapPercent) return null;
        double growthMiBPerMinute = growthBytes / (1024.0 * 1024.0)
                / (elapsed / 60_000_000_000.0);
        if (growthMiBPerMinute < 0.5) return null;

        long cooldown = SaveWatchConfig.MEMORY_LEAK_ALERT_COOLDOWN_MINUTES.get() * 60_000_000_000L;
        if (lastAlertNanos != 0 && now - lastAlertNanos < cooldown) return null;
        lastAlertNanos = now;

        String message = String.format(Locale.ROOT,
                "SaveWatch: обнаружен устойчивый рост памяти после GC — +%.1f МиБ за %.1f мин (%.2f МиБ/мин), занято %.1f%% heap. Это экспериментальное подозрение на утечку, не доказательство. Подробности: logs/savewatch/index.html",
                growthBytes / (1024.0 * 1024.0), elapsed / 60_000_000_000.0,
                growthMiBPerMinute, heapPercent);
        LOGGER.warn(message);
        if (SaveWatchConfig.MEMORY_LEAK_CHAT_ALERTS.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        }
        return message;
    }

    private static long totalGcCount() {
        long total = 0;
        boolean found = false;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count >= 0) {
                total += count;
                found = true;
            }
        }
        return found ? total : -1;
    }

    private static MemoryUsage retainedHeapUsage() {
        MemoryPoolMXBean selected = null;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if (name.contains("old") || name.contains("tenured")) {
                selected = pool;
                break;
            }
        }
        if (selected != null) {
            MemoryUsage usage = selected.getUsage();
            if (usage != null && usage.getUsed() >= 0) return usage;
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    }

    private record Sample(long timeNanos, long usedBytes) {}
}
