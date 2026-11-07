# SaveWatch Python tools

The tools in this directory are developer and diagnostic utilities. They are not loaded by Minecraft and do not become part of the mod JAR.

## Report analyzer

The report analyzer reads SaveWatch snapshot files and produces a Markdown summary. It uses only the Python 3 standard library; no pip packages are required.

Windows:

```bat
analyze.bat logs\savewatch
```

Or directly:

```bat
py -3 tools\analyze_reports.py logs\savewatch
```

Analyze selected reports:

```bat
py -3 tools\analyze_reports.py report-one.txt report-two.txt --top 25 --output analysis.md
```

The analyzer summarizes captured tick durations, server-thread states, and repeated stack frames. Repeated frames are evidence to investigate, not automatic proof that a specific mod caused the stall.

## Tests

Run the standard-library unit tests from the repository root:

```bat
py -3 -m unittest discover -s tests -v
```
