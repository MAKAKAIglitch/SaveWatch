package dev.savewatch.monitor;

import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Best-effort attribution of stack frames to the mod JAR that contains their
 * classes. This is evidence for investigation, not proof of causation.
 */
public final class ModAttribution {
    private static final Pattern TRANSFORMER = Pattern.compile(
            "(?:TRANSFORMER|LAYER SERVICE)/([A-Za-z0-9_.-]+)@[^/]+/");
    private static final int MAX_INDEXED_CLASSES = 1_000_000;
    private static volatile Index cachedIndex;

    private ModAttribution() {}

    public static List<Evidence> analyze(ThreadInfo[] threads, long triggerThreadId) {
        Index index = index();
        Map<String, MutableEvidence> hits = new LinkedHashMap<>();
        if (threads == null || index == null) return List.of();

        for (ThreadInfo thread : threads) {
            if (thread == null) continue;
            boolean trigger = thread.getThreadId() == triggerThreadId;
            StackTraceElement[] stack = thread.getStackTrace();
            for (int depth = 0; depth < stack.length; depth++) {
                StackTraceElement frame = stack[depth];
                String modId = resolve(frame, index);
                if (modId == null || modId.equals("savewatch")) continue;
                MutableEvidence hit = hits.computeIfAbsent(modId, id -> {
                    ModMetadata metadata = index.metadata.get(id);
                    return new MutableEvidence(id, metadata == null ? id : metadata.name,
                            metadata == null ? "unknown" : metadata.version);
                });
                if (trigger) {
                    hit.triggerFrames++;
                    if (depth < 5) hit.topTriggerFrames++;
                    if (hit.firstTriggerFrame == null) hit.firstTriggerFrame = frame.toString();
                    if (hit.firstThreadName == null) hit.firstThreadName = thread.getThreadName();
                } else {
                    hit.otherThreadFrames++;
                }
            }
        }

        return hits.values().stream()
                .sorted(Comparator.comparingInt((MutableEvidence hit) -> hit.triggerFrames).reversed()
                        .thenComparing(Comparator.comparingInt((MutableEvidence hit) -> hit.topTriggerFrames).reversed())
                        .thenComparing(hit -> hit.modId))
                .limit(40)
                .map(MutableEvidence::freeze)
                .toList();
    }

    private static String resolve(StackTraceElement frame, Index index) {
        String rawClass = frame.getClassName();
        Matcher marker = TRANSFORMER.matcher(rawClass);
        if (marker.find()) {
            String id = marker.group(1);
            if (index.metadata.containsKey(id)) return id;
            int slash = rawClass.lastIndexOf('/');
            if (slash >= 0) rawClass = rawClass.substring(slash + 1);
        }

        String exact = index.classOwners.get(rawClass);
        if (exact != null) return exact;
        if (index.ambiguousClasses.contains(rawClass)) return null;

        int dot = rawClass.lastIndexOf('.');
        while (dot > 0) {
            String packageName = rawClass.substring(0, dot);
            String owner = index.packageOwners.get(packageName);
            if (owner != null) return owner;
            if (index.ambiguousPackages.contains(packageName)) return null;
            dot = packageName.lastIndexOf('.');
        }
        return null;
    }

    private static Index index() {
        Index current = cachedIndex;
        if (current != null) return current;
        synchronized (ModAttribution.class) {
            current = cachedIndex;
            if (current != null) return current;
            current = buildIndex();
            cachedIndex = current;
            return current;
        }
    }

    private static Index buildIndex() {
        Map<String, ModMetadata> metadata = new LinkedHashMap<>();
        Map<String, String> classes = new HashMap<>();
        Map<String, String> packages = new HashMap<>();
        Set<String> ambiguousClasses = new HashSet<>();
        Set<String> ambiguousPackages = new HashSet<>();
        int indexed = 0;

        ModList modList = ModList.get();
        if (modList == null) return new Index(metadata, classes, packages,
                ambiguousClasses, ambiguousPackages, 0);

        for (IModFileInfo fileInfo : modList.getModFiles()) {
            List<? extends IModInfo> mods = fileInfo.getMods();
            for (IModInfo mod : mods) {
                metadata.put(mod.getModId(), new ModMetadata(
                        mod.getDisplayName(), String.valueOf(mod.getVersion())));
            }
            // A single JAR can contain several mod IDs. Its classes cannot be
            // safely assigned to one of them, so leave those classes unattributed.
            if (mods.size() != 1) continue;
            String owner = mods.get(0).getModId();
            Path file = fileInfo.getFile().getFilePath();
            try {
                if (Files.isDirectory(file)) {
                    try (var paths = Files.walk(file)) {
                        for (Path path : paths.filter(Files::isRegularFile).toList()) {
                            String name = file.relativize(path).toString().replace('\\', '/');
                            if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
                                indexed += addClass(name, owner, classes, packages,
                                        ambiguousClasses, ambiguousPackages, indexed);
                            }
                        }
                    }
                } else if (Files.isRegularFile(file)) {
                    try (JarFile jar = new JarFile(file.toFile(), false)) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements() && indexed < MAX_INDEXED_CLASSES) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (!entry.isDirectory() && name.endsWith(".class")
                                    && !name.startsWith("META-INF/versions/")) {
                                indexed += addClass(name, owner, classes, packages,
                                        ambiguousClasses, ambiguousPackages, indexed);
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // One unreadable or unusual mod file must not disable diagnostics.
            }
            if (indexed >= MAX_INDEXED_CLASSES) break;
        }
        return new Index(metadata, classes, packages, ambiguousClasses, ambiguousPackages, indexed);
    }

    private static int addClass(String entry, String owner, Map<String, String> classes,
                                Map<String, String> packages, Set<String> ambiguousClasses,
                                Set<String> ambiguousPackages, int indexed) {
        if (indexed >= MAX_INDEXED_CLASSES || entry.equals("module-info.class")
                || entry.endsWith("/module-info.class") || entry.endsWith("/package-info.class")
                || entry.equals("package-info.class")) return 0;
        String className = entry.substring(0, entry.length() - 6).replace('/', '.');
        putUnique(classes, ambiguousClasses, className, owner);
        int split = className.lastIndexOf('.');
        if (split > 0) putUnique(packages, ambiguousPackages, className.substring(0, split), owner);
        return 1;
    }

    private static void putUnique(Map<String, String> values, Set<String> ambiguous,
                                 String key, String owner) {
        if (ambiguous.contains(key)) return;
        String previous = values.putIfAbsent(key, owner);
        if (previous != null && !previous.equals(owner)) {
            values.remove(key);
            ambiguous.add(key);
        }
    }

    public record Evidence(String modId, String displayName, String version,
                           int triggerFrames, int otherThreadFrames, int topTriggerFrames,
                           String firstTriggerFrame, String firstThreadName) {
        public String toTsv() {
            return clean(modId) + "\t" + clean(displayName) + "\t" + clean(version) + "\t"
                    + triggerFrames + "\t" + otherThreadFrames + "\t" + topTriggerFrames + "\t"
                    + clean(firstTriggerFrame) + "\t" + clean(firstThreadName);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private record ModMetadata(String name, String version) {}

    private record Index(Map<String, ModMetadata> metadata, Map<String, String> classOwners,
                         Map<String, String> packageOwners, Set<String> ambiguousClasses,
                         Set<String> ambiguousPackages, int indexedClasses) {}

    private static final class MutableEvidence {
        final String modId;
        final String displayName;
        final String version;
        int triggerFrames;
        int otherThreadFrames;
        int topTriggerFrames;
        String firstTriggerFrame;
        String firstThreadName;

        MutableEvidence(String modId, String displayName, String version) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
        }

        Evidence freeze() {
            return new Evidence(modId, displayName, version, triggerFrames, otherThreadFrames,
                    topTriggerFrames, firstTriggerFrame, firstThreadName);
        }
    }
}
