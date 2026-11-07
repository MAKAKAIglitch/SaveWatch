#!/usr/bin/env python3
"""Summarize SaveWatch snapshots and identify repeated frames from the thread that triggered each snapshot.

Uses only the Python standard library. It reads reports without modifying them.
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

THREAD_HEADER = re.compile(
    r"^--- (?P<name>.*?) \| id=(?P<id>\d+) \| state=(?P<state>[A-Z_]+)(?: \| .*?)? ---$"
)
STACK_FRAME = re.compile(r"^\s+at (?P<frame>.+)$")
KEY_VALUE = re.compile(r"^(?P<key>[A-Za-z][A-Za-z0-9]*)=(?P<value>.*)$")


@dataclass
class ThreadSnapshot:
    name: str
    thread_id: int
    state: str
    frames: list[str] = field(default_factory=list)


@dataclass
class Report:
    path: Path
    values: dict[str, str] = field(default_factory=dict)
    threads: list[ThreadSnapshot] = field(default_factory=list)
    gc_lines: list[str] = field(default_factory=list)

    @property
    def server_thread(self) -> ThreadSnapshot | None:
        expected = self.values.get("serverThread", "")
        return next((thread for thread in self.threads if thread.name == expected), None)

    @property
    def focus_thread(self) -> ThreadSnapshot | None:
        expected = self.values.get("triggerThread") or self.values.get("serverThread", "")
        return next((thread for thread in self.threads if thread.name == expected), None)


def parse_report(path: Path) -> Report:
    report = Report(path=path)
    current_thread: ThreadSnapshot | None = None
    in_gc = False
    in_threads = False

    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip("\ufeff")
        if line == "[GC]":
            in_gc, in_threads = True, False
            continue
        if line == "[THREADS]":
            in_gc, in_threads = False, True
            continue
        if line.startswith("[") and line.endswith("]"):
            in_gc = in_threads = False
            continue

        if in_gc and line:
            report.gc_lines.append(line)
            continue

        if in_threads:
            header = THREAD_HEADER.match(line)
            if header:
                current_thread = ThreadSnapshot(
                    name=header.group("name"),
                    thread_id=int(header.group("id")),
                    state=header.group("state"),
                )
                report.threads.append(current_thread)
                continue
            frame = STACK_FRAME.match(line)
            if frame and current_thread is not None:
                current_thread.frames.append(frame.group("frame"))
            continue

        match = KEY_VALUE.match(line)
        if match:
            report.values[match.group("key")] = match.group("value")

    return report


def collect_reports(paths: list[Path]) -> list[Report]:
    reports: list[Report] = []
    for path in paths:
        if path.is_file() and path.suffix.lower() == ".txt":
            try:
                reports.append(parse_report(path))
            except OSError as error:
                print(f"[WARN] Cannot read {path}: {error}", file=sys.stderr)
    return sorted(reports, key=lambda item: (item.values.get("timestamp", ""), str(item.path)))


def render_summary(reports: list[Report], top: int) -> str:
    lines = [
        "# SaveWatch report analysis",
        "",
        f"- Reports read: **{len(reports)}**",
    ]
    if not reports:
        lines += ["", "No SaveWatch .txt reports were found."]
        return "\n".join(lines) + "\n"

    durations: list[tuple[int, Report]] = []
    states: Counter[str] = Counter()
    frame_counts: Counter[str] = Counter()
    missing_focus = 0

    for report in reports:
        try:
            durations.append((int(report.values.get("tickDurationMs", "0")), report))
        except ValueError:
            pass
        focus = report.focus_thread
        if focus is None:
            missing_focus += 1
            continue
        states[focus.state] += 1
        frame_counts.update(focus.frames)

    deadlock_reports = sum(
        report.values.get("deadlockDetected", "").lower() == "true" for report in reports
    )
    jfr_exports = sum(
        report.values.get("jfrFile", "").lower().endswith(".jfr") for report in reports
    )
    lines += [
        f"- Time range: **{reports[0].values.get('timestamp', 'unknown')}** to **{reports[-1].values.get('timestamp', 'unknown')}**",
        f"- Reports without a matching trigger-thread section: **{missing_focus}**",
        f"- Snapshots with detected JVM deadlocks: **{deadlock_reports}**",
        f"- Snapshots with a JFR export: **{jfr_exports}**",
    ]

    if durations:
        longest, longest_report = max(durations, key=lambda item: item[0])
        average = sum(value for value, _ in durations) / len(durations)
        lines += [
            f"- Longest captured stall: **{longest} ms** ({longest_report.path.name})",
            f"- Average captured stall duration: **{average:.1f} ms**",
        ]

    if states:
        lines += ["", "## Trigger-thread states", ""]
        lines += [f"- {state}: {count} snapshot(s)" for state, count in states.most_common()]

    if frame_counts:
        lines += ["", f"## Most repeated trigger-thread stack frames (top {top})", ""]
        lines += [
            f"{index}. {frame} — present in **{count}** captured stack(s)"
            for index, (frame, count) in enumerate(frame_counts.most_common(top), start=1)
        ]
        lines += [
            "",
            "Repeated frames are clues, not proof of a root cause. Compare their timestamps and surrounding frames before attributing a stall to a mod or subsystem.",
        ]

    lines += ["", "## Per-snapshot details", ""]
    for report in reports:
        focus = report.focus_thread
        duration = report.values.get("tickDurationMs", "unknown")
        focus_name = report.values.get("triggerThread") or report.values.get("serverThread", "unknown")
        state = focus.state if focus else "trigger thread not found"
        top_frame = focus.frames[0] if focus and focus.frames else "no stack frame"
        lines.append(
            f"- {report.path.name} — tick/frame **{duration} ms**, trigger **{focus_name}**, state **{state}**, top frame: {top_frame}"
        )

    lines += [
        "",
        "## JVM and GC notes",
        "",
        "Heap and GC values in each snapshot are point-in-time or cumulative JVM counters. They do not, by themselves, establish that garbage collection caused a pause.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze one SaveWatch report or a folder containing multiple reports."
    )
    parser.add_argument("paths", nargs="+", type=Path, help="Report .txt files or folders")
    parser.add_argument("--top", type=int, default=15, help="Number of repeated stack frames to show (default: 15)")
    parser.add_argument("--output", type=Path, help="Write Markdown summary to this file instead of stdout")
    args = parser.parse_args()

    if args.top < 1 or args.top > 100:
        parser.error("--top must be between 1 and 100")

    candidates: list[Path] = []
    for path in args.paths:
        if path.is_dir():
            candidates.extend(path.rglob("*.txt"))
        elif path.is_file():
            candidates.append(path)
        else:
            print(f"[WARN] Path does not exist: {path}", file=sys.stderr)

    reports = collect_reports(candidates)
    summary = render_summary(reports, args.top)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(summary, encoding="utf-8")
        print(f"Analysis written to: {args.output}")
    else:
        print(summary, end="")

    return 0 if reports else 2


if __name__ == "__main__":
    raise SystemExit(main())
