# SaveWatch

**SaveWatch** is a diagnostic NeoForge mod for Minecraft 1.21.1. It watches logical-server ticks, client ticks, and client render frames, then captures JVM thread snapshots while a stall is still in progress.

## Compatibility

- Minecraft: 1.21.1
- NeoForge: 21.1.256+
- Java: 21
- Mod ID: `savewatch`

## Features in 0.4.0

### Stall watchdog
- Watches server ticks, client ticks, and render frames independently.
- A low-priority daemon watchdog checks progress every 200 ms.
- Captures repeated snapshots while a stall is still happening.
- Uses a bounded background queue so simultaneous server/client/render events do not silently overwrite one another or block game threads.
- Uses collision-resistant snapshot names and reports when the queue is full.

### JVM and thread diagnostics
- Captures every live JVM thread, prioritizing the stalled thread.
- Captures thread state, stack frames, lock owner, and lock name.
- Detects JVM monitor/synchronizer deadlocks and records the involved threads and their stacks.
- Records cumulative per-thread CPU time when the JVM exposes the ThreadMXBean counter.
- Captures heap, committed heap, maximum heap, non-heap usage, and cumulative GC counters.

### Rolling Java Flight Recorder
- Starts a bounded rolling JFR recording using the JDK 21 built-in `jdk.jfr` API; no external Java agent or extra dependency is needed.
- Uses the JDK `profile` configuration and retains a configurable recent window.
- Exports a point-in-time JFR copy when a diagnostic snapshot is captured, without stopping the live recording.
- Limits exports to one every 30 seconds to avoid writing duplicate recordings for every repeated snapshot.
- If JFR is unavailable or disabled, ordinary text snapshots and HTML reports continue to work.

### Reports and commands
- Writes snapshots to `logs/savewatch/`.
- Automatically generates a self-contained offline HTML report at `logs/savewatch/index.html` after a monitored stall ends.
- The HTML report highlights detected deadlocks, JFR export status, heap/GC history, repeated stack frames, and potentially involved mods.
- `/savewatch status` — current server/client/render timing, snapshot queue depth, and heap summary.
- `/savewatch dump` — queue a manual JVM/thread snapshot.
- `/savewatch report` — regenerate the HTML report from existing snapshots.

Defaults: 2-second stall threshold, 2-second interval between snapshots, maximum 20 snapshots per continuous stall, 32 stack frames per thread, JFR enabled with a 60-second rolling window and a 64 MiB recording cap.

## Configuration

After first launch, edit `config/savewatch.properties`:

| Key | Default | Allowed range | Meaning |
|---|---:|---:|---|
| `stallThresholdMs` | 2000 | 250–60000 | Tick/frame duration before a snapshot |
| `repeatIntervalMs` | 2000 | 500–60000 | Minimum delay between snapshots |
| `maxDumpsPerStall` | 20 | 1–100 | Maximum snapshots during one stall |
| `stackDepth` | 32 | 8–128 | Maximum stack frames per thread |
| `jfrEnabled` | true | true/false | Enable rolling Java Flight Recorder |
| `jfrMaxAgeSeconds` | 60 | 10–600 | Maximum age of retained JFR events |
| `jfrMaxSizeMiB` | 64 | 16–512 | Maximum rolling JFR repository size |

Existing configuration files are migrated by adding new keys while preserving customized values.

## Build

Requires JDK 21.

Windows:
```bat
gradlew.bat build
```

Linux/macOS:
```sh
./gradlew build
```

The JAR is written to `build/libs/`. The repository also includes `build.bat` for a guided Windows build; see [BUILDING.md](BUILDING.md).

## Developer tools

The built-in HTML report is generated in Java by the mod; Python is not required to install or run it. Optional offline tools remain available:

- `tools/analyze_reports.py` summarizes snapshots and repeated trigger-thread frames.
- `tools/report_html.py` generates an HTML report from existing text snapshots.
- Both tools use only the Python standard library.
- On Windows, run `analyze.bat logs\\savewatch` for a Markdown summary.
- Run tests with `py -3 -m unittest discover -s tests -v`.

## Diagnostic limitations

- A long client tick indicates that the client tick loop stopped progressing; it does not by itself prove that rendering, a mod, or the GPU driver is the root cause.
- A long server tick does not by itself prove slow disk I/O or a save-system bug.
- Thread dumps are snapshots. Repeated snapshots help distinguish a stable blocking call from changing work.
- Thread CPU time is cumulative for the lifetime of a thread, not CPU usage over the exact stall interval.
- GC counters are cumulative totals, not an exact per-pause timeline.
- JFR is bounded and low-overhead relative to full tracing, but it still adds some CPU and disk activity. It can be disabled with `jfrEnabled=false`.
- A detected JVM deadlock is strong evidence of a lock cycle; an absent deadlock report does not rule out all forms of waiting or livelock.
- SaveWatch does not modify chunks, entities, block entities, SavedData, player data, or world files.
- Reports contain Java class names, mod package names, thread names, JVM version, and memory counters. Review reports before publishing them.

## License

MIT. See [LICENSE](LICENSE).
