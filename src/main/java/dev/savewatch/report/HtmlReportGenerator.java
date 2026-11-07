package dev.savewatch.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a self-contained, offline HTML report from SaveWatch text snapshots. */
public final class HtmlReportGenerator {
    private static final Pattern HEADER = Pattern.compile(
            "^--- (.*?) \\| id=(\\d+) \\| state=([A-Z_]+)(?: \\| .*?)? ---$");
    private static final Pattern FRAME = Pattern.compile("^\\s+at (.+)$");
    private static final Pattern KEY_VALUE = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)=(.*)$");
    private static final Pattern MOD = Pattern.compile("(?:TRANSFORMER|LAYER SERVICE)/([A-Za-z0-9_.-]+)@");

    private HtmlReportGenerator() {}

    public static Path generate(Path directory) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException("No SaveWatch .txt snapshots found in " + directory);
        }

        List<Snapshot> snapshots = new ArrayList<>();
        for (Path file : files) snapshots.add(parse(file));

        String html = build(snapshots);
        Path output = directory.resolve("index.html");
        Path temporary = directory.resolve("index.html.tmp");
        Files.writeString(temporary, html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    private static Snapshot parse(Path path) throws IOException {
        Snapshot snapshot = new Snapshot(path);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        snapshot.totalLines = lines.size();
        String section = "";
        ThreadDump current = null;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0) line = line.replace("\uFEFF", "");
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                current = null;
                continue;
            }
            if ("GC".equals(section)) {
                if (!line.isBlank()) snapshot.gc.add(line);
                continue;
            }
            if ("MOD_ATTRIBUTION".equals(section)) {
                if (!line.isBlank()) {
                    String[] fields = line.split("\\t", -1);
                    if (fields.length >= 8) {
                        snapshot.mods.add(new ModEvidence(fields[0], fields[1], fields[2],
                                integer(fields[3]), integer(fields[4]), integer(fields[5]),
                                fields[6], fields[7]));
                    }
                }
                continue;
            }
            if ("THREADS".equals(section)) {
                Matcher header = HEADER.matcher(line);
                if (header.matches()) {
                    current = new ThreadDump(header.group(1), Long.parseLong(header.group(2)), header.group(3));
                    snapshot.threads.add(current);
                    continue;
                }
                Matcher frame = FRAME.matcher(line);
                if (frame.matches() && current != null) {
                    current.frames.add(new Frame(frame.group(1), index + 1));
                }
                continue;
            }
            Matcher pair = KEY_VALUE.matcher(line);
            if (pair.matches()) snapshot.values.put(pair.group(1), pair.group(2));
        }

        String expected = snapshot.values.getOrDefault("triggerThread",
                snapshot.values.getOrDefault("serverThread", ""));
        for (ThreadDump thread : snapshot.threads) {
            if (thread.name.equals(expected)) {
                snapshot.focus = thread;
                break;
            }
        }
        return snapshot;
    }

    private static String build(List<Snapshot> snapshots) {
        Map<String, ModHit> mods = new LinkedHashMap<>();
        List<Double> durations = new ArrayList<>();
        double longest = -1;
        Snapshot latest = snapshots.get(snapshots.size() - 1);

        for (int reportIndex = 0; reportIndex < snapshots.size(); reportIndex++) {
            Snapshot snapshot = snapshots.get(reportIndex);
            Double duration = number(snapshot.values.get("tickDurationMs"));
            durations.add(duration);
            if (duration != null) longest = Math.max(longest, duration);

            if (!snapshot.mods.isEmpty()) {
                for (ModEvidence evidence : snapshot.mods) {
                    ModHit hit = mods.computeIfAbsent(evidence.id, ignored ->
                            new ModHit(evidence.id, evidence.displayName, evidence.version));
                    hit.trigger += evidence.triggerFrames;
                    hit.total += evidence.triggerFrames + evidence.otherThreadFrames;
                    hit.other += evidence.otherThreadFrames;
                    hit.topTriggerFrames += evidence.topTriggerFrames;
                    if (evidence.triggerFrames > 0) hit.triggerSnapshots++;
                    if (hit.firstFrame == null && evidence.firstTriggerFrame != null
                            && !evidence.firstTriggerFrame.isBlank()) {
                        hit.firstFrame = evidence.firstTriggerFrame;
                        if (snapshot.focus != null) {
                            for (Frame frame : snapshot.focus.frames) {
                                if (frame.text.equals(evidence.firstTriggerFrame)) {
                                    hit.anchor = "dump-" + reportIndex + "-thread-" + snapshot.focus.id
                                            + "-line-" + frame.line;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                for (ThreadDump thread : snapshot.threads) {
                    for (Frame frame : thread.frames) {
                        Matcher matcher = MOD.matcher(frame.text);
                        while (matcher.find()) {
                            String id = matcher.group(1);
                            if (Set.of("minecraft", "neoforge", "fml_loader", "java").contains(id.toLowerCase())) continue;
                            ModHit hit = mods.computeIfAbsent(id, ignored -> new ModHit(id, id, "unknown"));
                            hit.total++;
                            if (thread == snapshot.focus) {
                                hit.trigger++;
                                hit.triggerSnapshots++;
                                if (hit.anchor == null) {
                                    hit.anchor = "dump-" + reportIndex + "-thread-" + thread.id + "-line-" + frame.line;
                                    hit.firstFrame = frame.text;
                                }
                            } else {
                                hit.other++;
                            }
                        }
                    }
                }
            }
        }

        long threshold = (long) (number(latest.values.get("thresholdMs")) == null
                ? 2000 : number(latest.values.get("thresholdMs")));
        double heapUsed = value(latest, "heapUsedMiB", -1);
        double heapMax = value(latest, "heapMaxMiB", -1);
        boolean deadlockDetected = snapshots.stream()
                .anyMatch(snapshot -> Boolean.parseBoolean(snapshot.values.getOrDefault("deadlockDetected", "false")));
        long deadlockedThreads = snapshots.stream()
                .mapToLong(snapshot -> (long) value(snapshot, "deadlockedThreadCount", 0)).max().orElse(0);
        String assessment;
        if (deadlockDetected) assessment = "Критично — обнаружен deadlock JVM";
        else if (longest >= threshold * 5.0) assessment = "Критично — зафиксировано очень долгое зависание";
        else if (longest >= threshold || (heapMax > 0 && heapUsed / heapMax >= .90))
            assessment = "Внимание — превышен порог зависания";
        else if (snapshots.stream().anyMatch(s -> s.values.getOrDefault("reason", "").startsWith("LONG_")))
            assessment = "Внимание — записано длительное событие";
        else assessment = "В этих дампах долгих зависаний нет";

        StringBuilder out = new StringBuilder(64_000);
        out.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>SaveWatch — диагностический отчёт</title><style>").append(CSS).append("</style></head><body><main>")
                .append("<header><div><h1>SaveWatch</h1><p>Локальный диагностический отчёт</p></div>")
                .append("<nav><a href=\"#timeline\">События</a><a href=\"#mods\">Моды</a><a href=\"#dumps\">Дампы потоков</a><a href=\"ai-summary.md\">AI-сводка</a></nav></header>")
                .append("<section class=\"cards\">")
                .append(card("Общая оценка", assessment))
                .append(card("Дампов", Integer.toString(snapshots.size())))
                .append(card("Самое долгое событие", longest < 0 ? "—" : format(longest) + " ms"))
                .append(card("Использовано памяти", heapUsed < 0 ? "—" : format(heapUsed) + " MiB"))
                .append(card("Последний триггер", latest.values.getOrDefault("reason", "—")))
                .append(card("Потоков в deadlock", Long.toString(deadlockedThreads)))
                .append(card("Запись JFR", latest.values.getOrDefault("jfrFile", "unavailable")))
                .append("</section>")
                .append("<h2>Память и сборщик мусора</h2><div class=\"grid\">")
                .append("<section class=\"panel\"><h3>Использование памяти</h3><canvas id=\"heap\" class=\"chart\"></canvas><p>Использовано / максимум heap, МиБ.</p></section>")
                .append("<section class=\"panel\"><h3>Суммарное время GC</h3><canvas id=\"gc\" class=\"chart\"></canvas><p>Накопительный счётчик JVM.</p></section>")
                .append("<section class=\"panel\"><h3>MSPT сервера</h3><canvas id=\"mspt\" class=\"chart\"></canvas><p>Среднее и максимум времени тика.</p></section>")
                .append("<section class=\"panel\"><h3>TPS сервера</h3><canvas id=\"tps\" class=\"chart\"></canvas><p>Расчётная частота тиков, максимум 20 TPS.</p></section>")
                .append("<section class=\"panel\"><h3>FPS клиента</h3><canvas id=\"fps\" class=\"chart\"></canvas><p>Средний FPS на момент дампа.</p></section></div>")
                .append("<h2 id=\"timeline\">Хронология зависаний</h2><div class=\"timeline\">");

        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot snapshot = snapshots.get(i);
            String trigger = snapshot.values.getOrDefault("triggerThread",
                    snapshot.values.getOrDefault("serverThread", "unknown"));
            String duration = snapshot.values.containsKey("tickDurationMs")
                    ? escape(snapshot.values.get("tickDurationMs")) + " ms" : "n/a";
            out.append("<div class=\"event\"><div><b>").append(escape(snapshot.values.getOrDefault("timestamp", "Unknown time")))
                    .append("</b><div class=\"muted\">").append(escape(snapshot.values.getOrDefault("reason", "UNKNOWN")))
                    .append("</div></div><div><a href=\"#dump-").append(i).append("\">")
                    .append(escape(snapshot.path.getFileName().toString())).append("</a><div class=\"muted\">")
                    .append(snapshot.threads.size()).append(" threads · ").append(snapshot.lineCount())
                    .append(" lines</div></div><div><b>").append(duration).append("</b><div class=\"muted\">")
                    .append(escape(trigger)).append("</div></div></div>");
        }
        out.append("</div><h2>Сравнение стеков</h2><section class=\"panel\">");
        if (snapshots.size() < 2) {
            out.append("<p>Для сравнения стеков нужны минимум два дампа.</p>");
        } else {
            for (int i = 1; i < snapshots.size(); i++) {
                Set<String> before = frameSet(snapshots.get(i - 1).focus);
                Set<String> after = frameSet(snapshots.get(i).focus);
                Set<String> union = new LinkedHashSet<>(before);
                union.addAll(after);
                Set<String> shared = new LinkedHashSet<>(before);
                shared.retainAll(after);
                int percent = union.isEmpty() ? 0 : (int) Math.round(shared.size() * 100.0 / union.size());
                out.append("<p>").append(escape(snapshots.get(i - 1).path.getFileName().toString()))
                        .append(" → ").append(escape(snapshots.get(i).path.getFileName().toString()))
                        .append(": <b>").append(percent).append("%</b> identical trigger-stack frames (")
                        .append(shared.size()).append(" shared).</p>");
            }
        }
        out.append("</section><h2 id=\"mods\">Кандидаты на проверку — моды</h2><section class=\"panel\">")
                .append("<p>Атрибуция строится по классам, найденным в JAR модов, и маркерам ModLauncher. Повторение в потоке зависания и верхних кадрах повышает приоритет проверки, но не доказывает причинность.</p>")
                .append("<table><thead><tr><th>Мод</th><th>Сигнал</th><th>Дампы с участием</th><th>Кадры триггера</th><th>Другие потоки</th><th>Первый кадр</th></tr></thead><tbody>");

        List<ModHit> sortedMods = mods.values().stream().filter(hit -> hit.trigger > 0)
                .sorted(Comparator.comparingInt(ModHit::score).reversed()
                        .thenComparing(hit -> hit.displayName, String.CASE_INSENSITIVE_ORDER))
                .limit(30).toList();
        if (sortedMods.isEmpty()) {
            out.append("<tr><td colspan=\"6\" class=\"muted\">В стеках триггерных потоков не удалось сопоставить классы с модами. Проверьте полные дампы и JFR.</td></tr>");
        } else {
            for (ModHit hit : sortedMods) {
                out.append("<tr><td><b>").append(escape(hit.displayName)).append("</b><br><code>")
                        .append(escape(hit.id)).append("</code><br><small>").append(escape(hit.version))
                        .append("</small></td><td><b>").append(escape(hit.confidence())).append("</b><br><small>score ")
                        .append(hit.score()).append("</small></td><td>").append(hit.triggerSnapshots)
                        .append("</td><td>").append(hit.trigger).append(" (верхних: ").append(hit.topTriggerFrames)
                        .append(")</td><td>").append(hit.other).append("</td><td>");
                if (hit.anchor != null) out.append("<a href=\"#").append(escape(hit.anchor)).append("\">");
                out.append("<code>").append(escape(hit.firstFrame == null ? "—" : hit.firstFrame)).append("</code>");
                if (hit.anchor != null) out.append("</a>");
                out.append("</td></tr>");
            }
        }

        for (int reportIndex = 0; reportIndex < snapshots.size(); reportIndex++) {
            Snapshot snapshot = snapshots.get(reportIndex);
            out.append("<section class=\"dump\" id=\"dump-").append(reportIndex).append("\"><h3>")
                    .append(escape(snapshot.path.getFileName().toString())).append("</h3><p class=\"muted\">")
                    .append(escape(snapshot.values.getOrDefault("timestamp", ""))).append(" · ")
                    .append(escape(snapshot.values.getOrDefault("reason", "UNKNOWN"))).append(" · ")
                    .append(snapshot.threads.size()).append(" threads</p><div class=\"meta\">");
            appendMeta(out, snapshot, "javaVersion");
            appendMeta(out, snapshot, "availableProcessors");
            appendMeta(out, snapshot, "heapUsedMiB");
            appendMeta(out, snapshot, "heapCommittedMiB");
            appendMeta(out, snapshot, "heapMaxMiB");
            appendMeta(out, snapshot, "nonHeapUsedMiB");
            appendMeta(out, snapshot, "deadlockDetected");
            appendMeta(out, snapshot, "deadlockedThreadCount");
            appendMeta(out, snapshot, "sparkDetected");
            appendMeta(out, snapshot, "serverTps");
            appendMeta(out, snapshot, "serverMsptAvg");
            appendMeta(out, snapshot, "serverMsptP95");
            appendMeta(out, snapshot, "serverMsptMax");
            appendMeta(out, snapshot, "clientFpsAvg");
            appendMeta(out, snapshot, "clientFps1Low");
            String jfrFile = snapshot.values.getOrDefault("jfrFile", "unavailable");
            if (jfrFile.endsWith(".jfr")) {
                out.append("<span>jfrFile: <b><a href=\"").append(escape(jfrFile)).append("\">")
                        .append(escape(jfrFile)).append("</a></b></span>");
            } else {
                appendMeta(out, snapshot, "jfrFile");
            }
            out.append("</div>");
            for (ThreadDump thread : snapshot.threads) {
                String threadId = "dump-" + reportIndex + "-thread-" + thread.id;
                out.append("<details class=\"thread\" id=\"").append(threadId).append("\"><summary><span>")
                        .append(escape(thread.name)).append("</span><span class=\"muted\">#")
                        .append(thread.id).append(" · ").append(escape(thread.state)).append(" · ")
                        .append(thread.frames.size()).append(" frames</span></summary><div class=\"frames\">");
                if (thread.frames.isEmpty()) out.append("<p class=\"muted\">No stack frames captured.</p>");
                for (Frame frame : thread.frames) {
                    String anchor = threadId + "-line-" + frame.line;
                    out.append("<div class=\"frame\" id=\"").append(anchor).append("\"><a class=\"ln\" href=\"#")
                            .append(anchor).append("\">").append(frame.line).append("</a><code>")
                            .append(escape(frame.text)).append("</code></div>");
                }
                out.append("</div></details>");
            }
            out.append("</section>");
        }

        out.append("<script>const data=[");
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) out.append(',');
            Snapshot snapshot = snapshots.get(i);
            out.append("{heap:").append(jsonNumber(snapshot.values.get("heapUsedMiB")))
                    .append(",heapMax:").append(jsonNumber(snapshot.values.get("heapMaxMiB")))
                    .append(",gc:").append(gcTotal(snapshot))
                    .append(",mspt:").append(jsonNumber(snapshot.values.get("serverMsptAvg")))
                    .append(",msptMax:").append(jsonNumber(snapshot.values.get("serverMsptMax")))
                    .append(",fps:").append(jsonNumber(snapshot.values.get("clientFpsAvg")))
                    .append(",tps:").append(jsonNumber(snapshot.values.get("serverTps"))).append('}');
        }
        out.append("];")
                .append("function draw(id,key,maxKey,suffix){const c=document.getElementById(id),x=c.getContext('2d'),w=c.clientWidth,h=c.clientHeight,d=devicePixelRatio||1;c.width=w*d;c.height=h*d;x.scale(d,d);x.strokeStyle='#34445f';x.fillStyle='#93a4bd';x.font='11px system-ui';x.beginPath();x.moveTo(36,10);x.lineTo(36,h-26);x.lineTo(w-8,h-26);x.stroke();const vals=data.map(v=>v[key]).filter(v=>v!==null);if(!vals.length){x.fillText('В дампах нет данных',46,40);return}const max=Math.max(1,...data.map(v=>v[maxKey]??v[key]??0));x.strokeStyle='#78a9ff';x.lineWidth=2;x.beginPath();let started=false;data.forEach((v,i)=>{if(v[key]===null){started=false;return}const px=42+(w-54)*(i/Math.max(1,data.length-1)),py=h-28-(h-44)*(v[key]/max);if(!started){x.moveTo(px,py);started=true}else x.lineTo(px,py)});x.stroke();x.fillStyle='#93a4bd';x.fillText('0',12,h-25);x.fillText(Math.round(max)+suffix,4,14)}")
                .append("draw('heap','heap','heapMax',' МиБ');draw('gc','gc','gc',' мс');draw('mspt','mspt','msptMax',' мс');draw('fps','fps','fps',' FPS');draw('tps','tps','tps',' TPS');")
                .append("function reveal(){const e=document.getElementById(decodeURIComponent(location.hash.slice(1)));if(e){e.closest('details')?.setAttribute('open','');e.scrollIntoView({block:'center'})}}")
                .append("addEventListener('hashchange',reveal);reveal();</script></main></body></html>");
        return out.toString();
    }

    private static void appendMeta(StringBuilder out, Snapshot snapshot, String key) {
        String value = snapshot.values.get(key);
        if (value != null) out.append("<span>").append(escape(key)).append(": <b>")
                .append(escape(value)).append("</b></span>");
    }

    private static String card(String title, String value) {
        return "<article><small>" + escape(title) + "</small><strong>" + escape(value) + "</strong></article>";
    }

    private static Set<String> frameSet(ThreadDump thread) {
        Set<String> result = new LinkedHashSet<>();
        if (thread != null) for (Frame frame : thread.frames) result.add(frame.text);
        return result;
    }

    private static long gcTotal(Snapshot snapshot) {
        long total = 0;
        for (String line : snapshot.gc) {
            Matcher matcher = Pattern.compile("totalMs=(\\d+)").matcher(line);
            if (matcher.find()) {
                try { total += Long.parseLong(matcher.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return total;
    }

    private static double value(Snapshot snapshot, String key, double fallback) {
        Double parsed = number(snapshot.values.get(key));
        return parsed == null ? fallback : parsed;
    }

    private static Double number(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Double.parseDouble(value.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private static String jsonNumber(String value) {
        Double parsed = number(value);
        return parsed == null || !Double.isFinite(parsed) ? "null" : parsed.toString();
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final class Snapshot {
        final Path path;
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> gc = new ArrayList<>();
        final List<ThreadDump> threads = new ArrayList<>();
        final List<ModEvidence> mods = new ArrayList<>();
        ThreadDump focus;
        int totalLines;
        Snapshot(Path path) { this.path = path; }
        int lineCount() { return totalLines; }
    }

    private static final class ThreadDump {
        final String name;
        final long id;
        final String state;
        final List<Frame> frames = new ArrayList<>();
        ThreadDump(String name, long id, String state) { this.name = name; this.id = id; this.state = state; }
    }

    private record Frame(String text, int line) {}
    private record ModEvidence(String id, String displayName, String version,
                               int triggerFrames, int otherThreadFrames, int topTriggerFrames,
                               String firstTriggerFrame, String firstThreadName) {}

    private static int integer(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; }
    }

    private static final class ModHit {
        final String id;
        final String displayName;
        final String version;
        int trigger;
        int total;
        int other;
        int topTriggerFrames;
        int triggerSnapshots;
        String anchor;
        String firstFrame;

        ModHit(String id, String displayName, String version) {
            this.id = id;
            this.displayName = displayName;
            this.version = version;
        }

        int score() {
            return triggerSnapshots * 10 + topTriggerFrames * 3 + trigger * 2 + Math.min(other, 20);
        }

        String confidence() {
            if (triggerSnapshots >= 2 && topTriggerFrames >= 2) return "Сильный сигнал";
            if (triggerSnapshots >= 2 || trigger >= 3 || topTriggerFrames > 0) return "Возможная связь";
            return "Слабый сигнал";
        }
    }

    private static final String CSS = """
            :root{color-scheme:dark;--bg:#0b1020;--panel:#121a2b;--line:#26334b;--text:#e8eef9;--muted:#93a4bd;--accent:#78a9ff}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.55 system-ui,Segoe UI,sans-serif}
            main{max-width:1180px;margin:auto;padding:32px 22px 70px}h1{font-size:30px;margin:0}h2{margin:34px 0 12px;font-size:20px}h3{font-size:16px}
            p,small,.muted{color:var(--muted)}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
            header{display:flex;justify-content:space-between;align-items:end;gap:20px;margin-bottom:22px}nav{display:flex;gap:12px;flex-wrap:wrap}nav a{border:1px solid var(--line);border-radius:99px;padding:7px 12px}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}article,.panel,.dump{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}
            article strong{display:block;font-size:21px;margin-top:5px;overflow-wrap:anywhere}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.chart{width:100%;height:230px;display:block}
            .timeline{display:grid;gap:9px}.event{display:grid;grid-template-columns:190px 1fr 140px;gap:12px;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--panel);align-items:center}
            table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:10px;border-bottom:1px solid var(--line)}code{font:12px ui-monospace,Consolas,monospace;overflow-wrap:anywhere}
            .dump{margin:12px 0}.thread{border-top:1px solid var(--line);padding:9px 0}.thread summary{display:flex;justify-content:space-between;gap:12px;cursor:pointer}
            .frames{padding:8px 0 4px}.frame{display:flex;gap:12px;padding:3px 6px;border-radius:5px}.frame:target{background:#3a2e1b}.ln{min-width:42px;text-align:right;color:var(--muted);font:11px ui-monospace,monospace}
            .meta{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.meta span{padding:5px 8px;background:#1b2940;border-radius:7px;color:var(--muted);font-size:12px}
            @media(max-width:760px){.grid{grid-template-columns:1fr}.event{grid-template-columns:1fr}header{align-items:start;flex-direction:column}}
            """;
}