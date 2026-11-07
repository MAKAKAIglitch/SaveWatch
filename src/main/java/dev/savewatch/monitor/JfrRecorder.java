package dev.savewatch.monitor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a bounded rolling Java Flight Recorder recording and exports it when
 * SaveWatch captures a stall. JFR is part of the JDK; no external agent is used.
 */
public final class JfrRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("SaveWatch");
    private static Recording recording;
    private static long lastDumpNanos;

    private JfrRecorder() {}

    public static synchronized void start(boolean enabled, long maxAgeSeconds, long maxSizeMiB) {
        if (!enabled) {
            LOGGER.info("JFR recording is disabled in config/savewatch.properties.");
            return;
        }
        if (recording != null && recording.getState() == RecordingState.RUNNING) return;

        try {
            Recording next = new Recording(Configuration.getConfiguration("profile"));
            next.setName("SaveWatch rolling diagnostics");
            next.setToDisk(true);
            next.setMaxAge(Duration.ofSeconds(maxAgeSeconds));
            next.setMaxSize(maxSizeMiB * 1024L * 1024L);
            next.start();
            recording = next;
            LOGGER.info("JFR rolling recording started (maxAge={}s, maxSize={}MiB).",
                    maxAgeSeconds, maxSizeMiB);
        } catch (IOException | RuntimeException exception) {
            recording = null;
            LOGGER.warn("Could not start JFR recording; thread snapshots will remain available.", exception);
        }
    }

    /**
     * Dumps the rolling recording no more often than minIntervalMs.
     * Returns a short status string suitable for inclusion in a text snapshot.
     */
    public static synchronized String dumpIfDue(Path destination, long minIntervalMs) {
        if (recording == null || recording.getState() != RecordingState.RUNNING) return "unavailable";
        long now = System.nanoTime();
        if (lastDumpNanos != 0 && now - lastDumpNanos < minIntervalMs * 1_000_000L) {
            return "cooldown";
        }

        try {
            // Copy the live rolling recording so exporting a diagnostic never stops
            // the recorder and never creates a blind interval after the first stall.
            try (Recording snapshot = recording.copy(true)) {
                snapshot.dump(destination);
            }
            lastDumpNanos = now;
            return destination.getFileName().toString();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not export JFR recording to {}.", destination, exception);
            return "export-failed";
        }
    }
}
