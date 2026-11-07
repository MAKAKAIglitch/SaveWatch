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
        Map<String, ModSummary> modCandidates = new LinkedHashMap<>();
        for (Path snapshot : snapshots) {
            Map<String, String> values = readHeader(snapshot);
            for (ModEvidence evidence : readModEvidence(snapshot)) {
                if (evidence.triggerFrames <= 0) continue;
                ModSummary summary = modCandidates.computeIfAbsent(evidence.id,
                        ignored -> new ModSummary(evidence.id, evidence.displayName, evidence.version));
                summary.snapshots++;
                summary.triggerFrames += evidence.triggerFrames;
                summary.topFrames += evidence.topTriggerFrames;
                summary.otherFrames += evidence.otherThreadFrames;
            }
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
        out.append("\n## Моды-кандидаты по стеку зависания\n");
        if (modCandidates.isEmpty()) {
            out.append("- В дампах нет сопоставленных классов модов в потоке-триггере. Это не исключает модовую причину.\n");
        } else {
            modCandidates.values().stream()
                    .sorted(Comparator.comparingInt(ModSummary::score).reversed())
                    .limit(10)
                    .forEach(candidate -> out.append("- **").append(candidate.displayName)
                            .append(" (").append(candidate.id).append(")** v").append(candidate.version)
                            .append(" — ").append(candidate.confidence())
                            .append("; дампов: ").append(candidate.snapshots)
                            .append(", кадров потока-триггера: ").append(candidate.triggerFrames)
                            .append(", верхних кадров: ").append(candidate.topFrames)
                            .append(", других потоков: ").append(candidate.otherFrames).append("\n"));
        }
        out.append("\n> Рейтинг — эвристика по стеку вызовов, а не доказательство виновности. Сверяйте первые кадры и повторяемость с полными дампами/JFR.\n");
        out.append("\n## Последний дамп — ключевые поля\n");
        if (!snapshots.isEmpty()) {
            Map<String, String> latest = readHeader(snapshots.get(0));
            for (String key : List.of("trigger", "tickDurationMs", "lastCompletedServerTickMs",
                    "lastCompletedClientTickMs", "lastCompletedRenderFrameMs", "serverTps", "serverMsptAvg",
                    "serverMsptP95", "serverMsptMax", "clientFpsAvg", "clientFps1Low",
                    "heapUsedMiB", "heapMaxMiB", "deadlockDetected", "deadlockedThreadCount", "sparkDetected")) {
                if (latest.containsKey(key)) out.append("- ").append(key).append(": **")
                        .append(latest.get(key)).append("**\n");
            }
            out.append("\nПолный файл: [").append(snapshots.get(0).getFileName()).append("](")
                    .append(snapshots.get(0).getFileName()).append(")\n");
            List<String> frames = triggerFrames(snapshots.get(0), latest.get("triggerThread"));
            if (!frames.isEmpty()) {
                out.append("\n## Первые кадры потока-триггера\n");
                for (String frame : frames) out.append("- `").append(frame).append("`\n");
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

    private static List<ModEvidence> readModEvidence(Path path) {
        List<ModEvidence> result = new ArrayList<>();
        boolean inSection = false;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[") && line.endsWith("]")) {
                    inSection = line.equals("[MOD_ATTRIBUTION]");
                    continue;
                }
                if (!inSection || line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length < 8) continue;
                result.add(new ModEvidence(fields[0], fields[1], fields[2],
                        parseInt(fields[3]), parseInt(fields[4]), parseInt(fields[5])));
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; }
    }

    private record ModEvidence(String id, String displayName, String version,
                               int triggerFrames, int otherThreadFrames, int topTriggerFrames) {}

    private static final class ModSummary {
        final String id;
        final String displayName;
        final String version;
        int snapshots;
        int triggerFrames;
        int topFrames;
        int otherFrames;

        ModSummary(String id, String displayName, String version) {
            this.id = id;
            this.displayName = displayName;
            this.version = version;
        }

        int score() { return snapshots * 10 + topFrames * 3 + triggerFrames * 2 + Math.min(otherFrames, 20); }

        String confidence() {
            if (snapshots >= 2 && topFrames >= 2) return "сильный сигнал";
            if (snapshots >= 2 || triggerFrames >= 3 || topFrames > 0) return "возможная связь";
            return "слабый сигнал";
        }
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
