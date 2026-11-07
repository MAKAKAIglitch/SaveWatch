import tempfile
import unittest
from pathlib import Path

from tools.report_html import build, parse


SAMPLE = """SaveWatch diagnostic report
version=0.2.0
reason=LONG_RENDER_FRAME
timestamp=2026-11-07T14:30:00
tickDurationMs=3500
triggerThread=Render thread
serverThread=Server thread
heapUsedMiB=512
heapMaxMiB=2048

[GC]
G1 Young Generation count=2 totalMs=80

[THREADS]
--- Render thread | id=3 | state=RUNNABLE ---
    at TRANSFORMER/examplemod@1.0/example.Mod.render(Mod.java:10)
--- Server thread | id=4 | state=WAITING ---
    at net.minecraft.server.MinecraftServer.wait(MinecraftServer.java:10)
[END]
"""


class HtmlReportTests(unittest.TestCase):
    def test_parse_trigger_and_mod_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "snapshot.txt"
            source.write_text(SAMPLE, encoding="utf-8")
            report = parse(source)
            self.assertEqual(report["focus"]["name"], "Render thread")
            self.assertEqual(report["focus"]["frames"][0]["line"], 16)

    def test_build_self_contained_html(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "snapshot.txt"
            output = Path(directory) / "index.html"
            source.write_text(SAMPLE, encoding="utf-8")
            build([parse(source)], output)
            page = output.read_text(encoding="utf-8")
            self.assertIn("Potentially involved mods", page)
            self.assertIn("examplemod", page)
            self.assertIn("dump-0-thread-3-line-16", page)
            self.assertIn("Memory and GC", page)
            self.assertNotIn("__DATA__", page)

    def test_deadlock_and_jfr_are_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "deadlock.txt"
            output = Path(directory) / "index.html"
            diagnostic = SAMPLE.replace(
                "[GC]",
                "deadlockDetected=true\ndeadlockedThreadCount=2\njfrFile=savewatch-test.jfr\n\n[GC]",
            )
            source.write_text(diagnostic, encoding="utf-8")
            build([parse(source)], output)
            page = output.read_text(encoding="utf-8")
            self.assertIn("Critical — JVM deadlock detected", page)
            self.assertIn("Deadlocked threads", page)
            self.assertIn("savewatch-test.jfr", page)


if __name__ == "__main__":
    unittest.main()
