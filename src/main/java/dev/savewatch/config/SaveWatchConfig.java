package dev.savewatch.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Experimental heap-growth detector settings exposed through the NeoForge config screen. */
public final class SaveWatchConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue MEMORY_LEAK_ANALYZER;
    public static final ModConfigSpec.BooleanValue MEMORY_LEAK_CHAT_ALERTS;
    public static final ModConfigSpec.IntValue MEMORY_LEAK_SAMPLE_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue MEMORY_LEAK_MIN_SAMPLES;
    public static final ModConfigSpec.IntValue MEMORY_LEAK_MIN_GROWTH_MIB;
    public static final ModConfigSpec.DoubleValue MEMORY_LEAK_GROWTH_PERCENT;
    public static final ModConfigSpec.DoubleValue MEMORY_LEAK_MIN_HEAP_PERCENT;
    public static final ModConfigSpec.IntValue MEMORY_LEAK_ALERT_COOLDOWN_MINUTES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("memoryLeakAnalyzer");

        MEMORY_LEAK_ANALYZER = builder
                .comment("Enable experimental post-GC heap growth analysis. This is a heuristic, not proof of a memory leak.")
                .translation("config.savewatch.memoryLeakAnalyzer.enabled")
                .define("enabled", true);
        MEMORY_LEAK_CHAT_ALERTS = builder
                .comment("Send a warning to in-game chat when sustained heap growth is detected.")
                .translation("config.savewatch.memoryLeakAnalyzer.chatAlerts")
                .define("chatAlerts", true);
        MEMORY_LEAK_SAMPLE_INTERVAL_SECONDS = builder
                .comment("Minimum interval between heap samples. Samples are recorded after garbage collection activity.")
                .translation("config.savewatch.memoryLeakAnalyzer.sampleIntervalSeconds")
                .defineInRange("sampleIntervalSeconds", 30, 10, 300);
        MEMORY_LEAK_MIN_SAMPLES = builder
                .comment("Minimum number of post-GC samples required before evaluating a trend.")
                .translation("config.savewatch.memoryLeakAnalyzer.minSamples")
                .defineInRange("minSamples", 6, 4, 24);
        MEMORY_LEAK_MIN_GROWTH_MIB = builder
                .comment("Minimum retained-heap growth in MiB required to raise a warning.")
                .translation("config.savewatch.memoryLeakAnalyzer.minGrowthMiB")
                .defineInRange("minGrowthMiB", 48, 16, 2048);
        MEMORY_LEAK_GROWTH_PERCENT = builder
                .comment("The retained-heap increase must also exceed this percentage of the maximum JVM heap.")
                .translation("config.savewatch.memoryLeakAnalyzer.growthPercent")
                .defineInRange("growthPercent", 3.0, 1.0, 50.0);
        MEMORY_LEAK_MIN_HEAP_PERCENT = builder
                .comment("Only warn when the retained heap is at least this percentage of the maximum JVM heap.")
                .translation("config.savewatch.memoryLeakAnalyzer.minHeapPercent")
                .defineInRange("minHeapPercent", 60.0, 20.0, 95.0);
        MEMORY_LEAK_ALERT_COOLDOWN_MINUTES = builder
                .comment("Minimum time between repeated warnings for the same continuing growth trend.")
                .translation("config.savewatch.memoryLeakAnalyzer.alertCooldownMinutes")
                .defineInRange("alertCooldownMinutes", 10, 1, 120);

        builder.pop();
        SPEC = builder.build();
    }

    private SaveWatchConfig() {}
}
