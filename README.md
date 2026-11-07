# SaveWatch

**SaveWatch** is a diagnostic NeoForge mod for Minecraft 1.21.1. It watches logical-server ticks, client ticks, and client render frames, then captures JVM thread snapshots while a stall is still in progress.

## Automatic Windows builder

1. Run `build.bat`.
2. It finds a valid JDK 21 or downloads Eclipse Temurin 21 locally and verifies its SHA-256 checksum.
3. The Gradle Wrapper downloads the pinned Gradle distribution and required build dependencies.
4. The compiled mod appears in `build/libs/`.

The builder reuses Gradle's daemon and build cache for faster repeat builds. No administrator rights or system-wide Java configuration changes are required. See [BUILDING.md](BUILDING.md).

## Compatibility

- Minecraft: 1.21.1
- NeoForge: 21.1.256+
- Java: 21
- Mod ID: `savewatch`

## Features in 0.3.0

- Watches server ticks, client ticks, and render frames independently.
- A low-priority daemon watchdog checks progress every 200 ms.
- Automatically captures a snapshot after a server tick, client tick, or render frame crosses the configured threshold.
- Captures repeated snapshots during a continuing stall, making it easier to distinguish a stable deadlock from changing work.
- Prioritizes the stalled thread, then captures all live JVM threads.
- Collects thread stacks without the more expensive full monitor/synchronizer enumeration.
- Captures JVM heap, non-heap, garbage-collection counters, thread states, and basic lock-owner information.
- Writes diagnostic snapshots to `logs/savewatch/`.
- Automatically generates a self-contained offline HTML report at `logs/savewatch/index.html` after a monitored server/client tick or render frame finishes.
- `/savewatch report` regenerates the HTML report on demand; report generation runs on a background daemon thread.
- Operator commands:
  - `/savewatch status` — tick progress and heap summary.
  - `/savewatch dump` — queues a manual JVM/thread snapshot.
  - `/savewatch report` — generates the HTML report from existing snapshots.

Defaults: 2-second stall threshold, 2-second interval between snapshots, maximum 20 snapshots per continuous stall, 32 stack frames per thread.

## Configuration

After first launch, edit `config/savewatch.properties`:

| Key | Default | Allowed range | Meaning |
|---|---:|---:|---|
| `stallThresholdMs` | 2000 | 250–60000 | Tick duration before a snapshot |
| `repeatIntervalMs` | 2000 | 500–60000 | Minimum delay between snapshots |
| `maxDumpsPerStall` | 20 | 1–100 | Maximum snapshots during one stall |
| `stackDepth` | 32 | 8–128 | Maximum stack frames per thread |

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

The JAR is written to `build/libs/`.

## Developer tools

The built-in HTML report is generated entirely in Java by the mod; Python is not required. The optional Python tools remain available for offline analysis outside Minecraft:

- `tools/analyze_reports.py` summarizes snapshots, groups repeated frames from the thread that triggered each snapshot, and reports tick-duration statistics.
- It uses only the Python standard library; Python is not required to install or run the Minecraft mod.
- On Windows, run `analyze.bat logs\\savewatch` for the Markdown summary.
- The optional `report.bat` / `tools/report_html.py` can still generate a report outside Minecraft when needed.
- Run its tests with `py -3 -m unittest discover -s tests -v`.

## Diagnostic limitations

- A stalled client tick indicates that the client tick loop stopped progressing; it does not by itself prove that rendering, a mod, or the GPU driver is the root cause.
- A long server tick does not by itself prove slow disk I/O or a save-system bug.
- A thread dump is a snapshot. Repeated snapshots help distinguish a stable blocking call from changing work.
- GC counters are cumulative totals, not an exact per-pause timeline.
- SaveWatch does not modify chunks, entities, block entities, SavedData, player data, or world files.
- Reports contain Java class names, mod package names, thread names, JVM version, and memory counters. Review reports before publishing them.

## License

MIT. See [LICENSE](LICENSE).
