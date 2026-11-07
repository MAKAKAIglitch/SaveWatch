# SaveWatch

**SaveWatch** is a community-oriented diagnostic NeoForge mod for Minecraft 1.21.1. Its first release captures evidence when the logical server thread stops making progress for an unusually long time. The project is standalone and is not tied to a modpack or private organization.

> **Current scope:** SaveWatch observes server tick stalls. Version 0.1.0 does not identify the exact cause of a stall, does not instrument BetterAutoSave internals, and does not change Minecraft's save implementation.

## Automatic Windows builder

The repository includes a one-click Windows build helper:

1. Run `build.bat`.
2. It finds a valid JDK 21 or downloads Eclipse Temurin 21 locally and verifies its SHA-256 checksum.
3. The Gradle Wrapper downloads the pinned Gradle distribution and required build dependencies.
4. The compiled mod appears in `build/libs/`.

No administrator rights or system-wide Java configuration changes are required. See [BUILDING.md](BUILDING.md) for details and optional tasks.

## Compatibility

- Minecraft: 1.21.1
- NeoForge: 21.1.256+
- Java: 21
- Mod ID: `savewatch`

## Features in 0.1.0

- Observes server tick start and completion.
- A separate low-priority daemon watchdog checks whether a tick is still running.
- Captures thread stacks **while** a long tick is in progress.
- Captures JVM heap, non-heap, garbage-collection counters, thread states, lock owners, and stack traces.
- Writes reports to `logs/savewatch/`.
- Operator commands:
  - `/savewatch status` — current tick and heap summary.
  - `/savewatch dump` — queues a manual JVM/thread snapshot.

Defaults: 2-second stall threshold, 5-second interval between snapshots, maximum 20 snapshots for one continuous stall.

## Configuration

After first launch, edit `config/savewatch.properties`:

| Key | Default | Allowed range | Meaning |
|---|---:|---:|---|
| `stallThresholdMs` | 2000 | 250–60000 | Tick duration before a snapshot |
| `repeatIntervalMs` | 5000 | 1000–60000 | Minimum delay between snapshots |
| `maxDumpsPerStall` | 20 | 1–100 | Maximum snapshots during one stall |
| `stackDepth` | 48 | 8–128 | Maximum stack frames per thread |

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

Development runs:
```bat
gradlew.bat runClient
gradlew.bat runServer
```

## Developer tools

The mod itself is written in Java because it runs inside the NeoForge JVM. Python is used for offline developer tooling:

- `tools/analyze_reports.py` summarizes one or more SaveWatch snapshots, groups repeated server-thread stack frames, and reports tick-duration statistics.
- It uses only the Python 3 standard library; Python is not required to install or run the Minecraft mod.
- On Windows, run `analyze.bat logs\\savewatch`.
- Run its tests with `py -3 -m unittest discover -s tests -v`.

Rust is intentionally not a runtime dependency. A separate Rust utility may be justified later if profiling shows that large-scale report processing or another CPU-heavy task is actually constrained by Python. The current workload does not justify JNI or a native library in the mod.

## Diagnostic limitations

- A long server tick does not by itself prove slow disk I/O or a save-system bug.
- A thread dump is a snapshot. Repeated snapshots help distinguish a stable blocking call from changing work.
- GC counters are cumulative totals, not an exact per-pause timeline.
- Version 0.1.0 does not modify chunks, entities, block entities, SavedData, player data, or world files.
- Reports contain Java class names, mod package names, thread names, JVM version, and memory counters. Review reports before publishing them.

## Roadmap

1. Validate the watchdog on integrated and dedicated servers.
2. Correlate long ticks with world-save events and BetterAutoSave queue statistics when a safe, version-compatible integration is available.
3. Add evidence-based diagnostics for the actual blocking path.
4. Consider optional Rust tooling only if profiling demonstrates a measurable benefit. The in-game mod remains Java because NeoForge and Minecraft expose their runtime APIs through the JVM.

## License

MIT. See [LICENSE](LICENSE).
