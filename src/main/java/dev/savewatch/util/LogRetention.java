package dev.savewatch.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Enforces a hard aggregate size limit for SaveWatch diagnostic files. */
public final class LogRetention {
    private LogRetention() {}

    public static void enforce(Path directory, long maxMiB) {
        if (!Files.isDirectory(directory)) return;
        long limit = Math.max(1, maxMiB) * 1024L * 1024L;
        try (var stream = Files.list(directory)) {
            var files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return !name.endsWith(".tmp") && !name.equals("index.html") && !name.equals("ai-summary.md");
                    })
                    .sorted(Comparator.comparingLong(LogRetention::modified))
                    .toList();
            long total = 0;
            for (Path file : files) total += size(file);
            if (total <= limit) return;
            for (Path file : files) {
                if (total <= limit) break;
                long bytes = size(file);
                try {
                    if (Files.deleteIfExists(file)) total -= bytes;
                } catch (IOException ignored) {
                    // A file may be temporarily locked by an external viewer.
                }
            }
        } catch (IOException ignored) {
            // Retention must never interrupt the watchdog or snapshot writer.
        }
    }

    private static long size(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0; }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return Long.MAX_VALUE; }
    }
}
