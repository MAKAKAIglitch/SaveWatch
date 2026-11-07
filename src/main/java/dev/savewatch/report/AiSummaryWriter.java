package dev.savewatch.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a short, model-friendly summary with links to the full evidence. */
public final class AiSummaryWriter {
    private AiSummaryWriter() {}

    public static Path generate(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) Files.createDirectories(directory);
        List<Path> snapshots;
        try (var stream = Files.list(directory)) {
            snapshots = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(AiSummaryWriter::modified).reversed())
                    .limit(20).toList();
        }
        StringBuilder out = new StringBuilder(4096);
        out.append("# SaveWatch — краткая сводка для ИИ\n\n")
                .append("> Сводка автоматически собрана из последних ").append(snapshots.size())
                .append(" дампов. Это факты для анализа, не готовый диагноз.\n\n")
                .append("## Быстрые ссылки\n")
                .append("- [HTML-отчёт](index.html)\n");
        if (Files.isRegularFile(directory.resolve("ai-summary.md"))) out.append("- [Эта AI-сводка](ai-summary.md)\n");
        out.append("\n## События\n");
        if (snapshots.isEmpty()) out.append("- Дампов пока нет.\n");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        long maxDuration = 0;
        for (Path snapshot : snapshots) {
            Map<String, String> values = readHeader(snapshot);
            String reason = values.getOrDefault("reason", "UNKNOWN");
            reasons.merge(reason, 1, Integer::sum);
            long duration = parseLong(values.get("tickDurationMs"));
            maxDuration = Math.max(maxDuration, duration);
            String file = snapshot.getFileName().toString();
            out.append("- **").append(reason).append(" — ").append(duration).append(" мс** · ")
                    .append(values.getOrDefault("timestamp", "время неизвестно"))
                    .append(" · [дамп](").append(file).append(")");
            String jfr = values.get("jfrFile");
            if (jfr != null && jfr.endsWith(".jfr") && Files.isRegularFile(directory.resolve(jfr)))
                out.append(" · [JFR](").append(jfr).append(")");
            out.append("\n");
        }
        out.append("\n## Итоговые показатели\n")
                .append("- Максимальная длительность события: **").append(maxDuration).append(" мс**\n");
        if (!reasons.isEmpty()) {
            out.append("- Типы событий: ");
            boolean first = true;
            for (var entry : reasons.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(entry.getKey()).append(" × ").append(entry.getValue());
            }
            out.append("\n");
        }
        out.append("\n## Последний дамп — ключевые поля\n");
        if (!snapshots.isEmpty()) {
            Map<String, String> latest = readHeader(snapshots.get(0));
            for (String key : List.of("trigger", "tickDurationMs", "lastCompletedServerTickMs",
                    "lastCompletedClientTickMs", "lastCompletedRenderFrameMs", "heapUsedMiB",
                    "heapMaxMiB", "deadlockDetected", "deadlockedThreadCount", "sparkDetected")) {
                if (latest.containsKey(key)) out.append("- ").append(key).append(": **")
                        .append(latest.get(key)).append("**\n");
            }
            out.append("\nПолный файл: [").append(snapshots.get(0).getFileName()).append("](")
                    .append(snapshots.get(0).getFileName()).append(")\n");
            List<String> frames = triggerFrames(snapshots.get(0), latest.get("triggerThread"));
            if (!frames.isEmpty()) {
                out.append("\n## Первые кадры потока-триггера\n");
                for (String frame : frames) out.append("- `").append(frame).append("`\\n");
            }
        }
        out.append("\n## Как использовать\n")
                .append("Передайте эту сводку ИИ вместе с 1–3 наиболее релевантными полными дампами. ")
                .append("Если приложен JFR, приложите и его для анализа профиля. ")
                .append("Не публикуйте файлы без проверки: они содержат имена потоков, классы и сведения о модах.\n");
        Path output = directory.resolve("ai-summary.md");
        Files.writeString(output, out, StandardCharsets.UTF_8);
        return output;
    }

    private static List<String> triggerFrames(Path path, String triggerThread) {
        List<String> result = new ArrayList<>();
        if (triggerThread == null) return result;
        boolean inTrigger = false;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("--- ")) {
                    inTrigger = line.startsWith("--- " + triggerThread + " |");
                    continue;
                }
                if (inTrigger && line.startsWith("    at ")) {
                    result.add(line.substring(7).trim());
                    if (result.size() == 3) break;
                }
                if (inTrigger && line.isBlank()) break;
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static Map<String, String> readHeader(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && !line.isBlank() && !line.startsWith("[")) {
                int split = line.indexOf('=');
                if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
            }
        } catch (IOException ignored) {}
        return values;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); } catch (RuntimeException ignored) { return 0; }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; }
    }
}
