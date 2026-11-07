import tempfile
import unittest
from pathlib import Path

from tools.analyze_reports import parse_report, render_summary


SAMPLE_REPORT = """SaveWatch diagnostic report
version=0.1.0
reason=LONG_SERVER_TICK
timestamp=2026-11-07T12:00:00
tickDurationMs=12000
observedTicks=500
lastCompletedTickMs=51
serverThread=Server thread
thresholdMs=2000

[JVM]
javaVersion=21
heapUsedMiB=4096
heapMaxMiB=8192
deadlockDetected=true
deadlockedThreadCount=2
jfrFile=savewatch-sample.jfr

[GC]
G1 Young Generation count=4 totalMs=120

[THREADS]

--- Server thread | id=42 | state=WAITING | waitingOn=java.lang.Object@123 ---
    at java.base@21/java.lang.Object.wait(Native Method)
    at example.save.ModSaver.save(ModSaver.java:42)

--- Render thread | id=7 | state=RUNNABLE ---
    at example.render.Renderer.draw(Renderer.java:12)

[END]
"""


class AnalyzeReportsTests(unittest.TestCase):
    def test_parse_server_thread_and_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.txt"
            path.write_text(SAMPLE_REPORT, encoding="utf-8")
            report = parse_report(path)

        self.assertEqual(report.values["tickDurationMs"], "12000")
        self.assertIsNotNone(report.server_thread)
        self.assertEqual(report.server_thread.state, "WAITING")
        self.assertEqual(len(report.server_thread.frames), 2)
        self.assertEqual(len(report.gc_lines), 1)

    def test_focuses_trigger_thread_for_render_stall(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "render-stall.txt"
            path.write_text(
                SAMPLE_REPORT.replace("reason=LONG_SERVER_TICK", "reason=LONG_RENDER_FRAME")
                .replace("serverThread=Server thread", "serverThread=Server thread\ntriggerThread=Render thread"),
                encoding="utf-8",
            )
            report = parse_report(path)

        self.assertIsNotNone(report.focus_thread)
        self.assertEqual(report.focus_thread.name, "Render thread")
        self.assertIn("Renderer.draw", render_summary([report], top=5))

    def test_summary_highlights_repeated_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.txt"
            path.write_text(SAMPLE_REPORT, encoding="utf-8")
            report = parse_report(path)

        summary = render_summary([report, report], top=5)
        self.assertIn("Reports read: **2**", summary)
        self.assertIn("Longest captured stall: **12000 ms**", summary)
        self.assertIn("example.save.ModSaver.save", summary)
        self.assertIn("Snapshots with detected JVM deadlocks: **1**", summary)
        self.assertIn("Snapshots with a JFR export: **1**", summary)
        self.assertIn("not proof of a root cause", summary)


if __name__ == "__main__":
    unittest.main()
