#!/usr/bin/env python3
"""Build a self-contained, offline HTML report from SaveWatch text snapshots."""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

HEADER = re.compile(r"^--- (?P<name>.*?) \| id=(?P<id>\d+) \| state=(?P<state>[A-Z_]+)(?: \| .*?)? ---$")
FRAME = re.compile(r"^\s+at (?P<frame>.+)$")
KV = re.compile(r"^(?P<key>[A-Za-z][A-Za-z0-9]*)=(?P<value>.*)$")
MOD = re.compile(r"(?:TRANSFORMER|LAYER SERVICE)/(?P<id>[A-Za-z0-9_.-]+)@")

def parse(path: Path) -> dict:
    data = {"path": path, "values": {}, "threads": [], "gc": []}
    section = ""
    current = None
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for number, raw in enumerate(lines, 1):
        line = raw.strip("\ufeff")
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            current = None
            continue
        if section == "GC":
            if line:
                data["gc"].append(line)
            continue
        if section == "THREADS":
            match = HEADER.match(line)
            if match:
                current = {
                    "name": match.group("name"), "id": int(match.group("id")),
                    "state": match.group("state"), "frames": [], "line": number,
                }
                data["threads"].append(current)
                continue
            match = FRAME.match(line)
            if match and current is not None:
                current["frames"].append({"text": match.group("frame"), "line": number})
            continue
        match = KV.match(line)
        if match:
            data["values"][match.group("key")] = match.group("value")
    data["line_count"] = len(lines)
    expected = data["values"].get("triggerThread") or data["values"].get("serverThread", "")
    data["focus"] = next((t for t in data["threads"] if t["name"] == expected), None)
    return data

def timestamp(report: dict) -> str:
    return report["values"].get("timestamp", "")

def millis(report: dict):
    try:
        return float(report["values"].get("tickDurationMs", ""))
    except (TypeError, ValueError):
        return None

def safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "-", value)

def build(reports: list[dict], output: Path) -> None:
    all_mods = Counter()
    trigger_mods = Counter()
    mod_anchors = {}
    for report_index, report in enumerate(reports):
        focus = report["focus"]
        for thread in report["threads"]:
            for frame in thread["frames"]:
                for match in MOD.finditer(frame["text"]):
                    mod_id = match.group("id")
                    if mod_id.lower() in {"minecraft", "neoforge", "fml_loader", "java"}:
                        continue
                    all_mods[mod_id] += 1
                    if thread is focus:
                        trigger_mods[mod_id] += 1
                        mod_anchors.setdefault(
                            mod_id, f"dump-{report_index}-thread-{thread['id']}-line-{frame['line']}"
                        )

    snapshots = []
    for index, report in enumerate(reports):
        values = report["values"]
        focus = report["focus"]
        used = set()
        if focus:
            for frame in focus["frames"]:
                used.add(frame["text"])
        snapshots.append({
            "id": index, "file": report["path"].name,
            "time": timestamp(report), "reason": values.get("reason", "UNKNOWN"),
            "duration": millis(report), "trigger": values.get("triggerThread") or values.get("serverThread", "unknown"),
            "state": focus["state"] if focus else "UNKNOWN",
            "heap": _number(values.get("heapUsedMiB")),
            "heapMax": _number(values.get("heapMaxMiB")),
            "gc": _gc_total(report["gc"]), "frames": sorted(used),
            "threadCount": len(report["threads"]),
        })

    suspicious = []
    for mod, count in trigger_mods.most_common():
        total = all_mods[mod]
        suspicious.append({
            "id": mod, "trigger": count, "total": total,
            "anchor": mod_anchors.get(mod, "dumps"),
        })

    raw = []
    for index, report in enumerate(reports):
        file_id = f"dump-{index}"
        thread_rows = []
        for t_index, thread in enumerate(report["threads"]):
            tid = f"{file_id}-thread-{thread['id']}"
            frame_rows = "".join(
                f'<div class="frame" id="{tid}-line-{f["line"]}"><a class="ln" href="#{tid}-line-{f["line"]}">{f["line"]}</a><code>{html.escape(f["text"])}</code></div>'
                for f in thread["frames"]
            )
            thread_rows.append(
                f'<details class="thread" id="{tid}"><summary><span>{html.escape(thread["name"])}</span>'
                f'<span class="muted">#{thread["id"]} · {thread["state"]} · {len(thread["frames"])} frames</span></summary>'
                f'<div class="frames">{frame_rows or "<p class=muted>No stack frames captured.</p>"}</div></details>'
            )
        values = report["values"]
        raw.append(
            f'<section class="dump" id="{file_id}"><h3>{html.escape(report["path"].name)}</h3>'
            f'<p class="muted">{html.escape(timestamp(report))} · {html.escape(values.get("reason","UNKNOWN"))} · '
            f'{len(report["threads"])} threads · {report["line_count"]} lines</p>'
            f'<div class="dump-meta">{_meta(values)}</div>{"".join(thread_rows)}</section>'
        )

    payload = json.dumps(snapshots, ensure_ascii=False).replace("</", "<\\/")
    mod_rows = "".join(
        f'<tr><td><code>{html.escape(item["id"])}</code></td><td>{item["trigger"]}</td><td>{item["total"]}</td>'
        f'<td><a href="#{html.escape(item["anchor"])}">open exact frame</a></td></tr>' for item in suspicious
    ) or '<tr><td colspan="4" class="muted">No mod package identifiers found in captured stacks.</td></tr>'

    cards = _cards(reports, snapshots)
    html_doc = PAGE.replace("__TITLE__", "SaveWatch diagnostic report")
    html_doc = html_doc.replace("__CARDS__", cards)
    html_doc = html_doc.replace("__MOD_ROWS__", mod_rows)
    html_doc = html_doc.replace("__DUMPS__", "".join(raw))
    html_doc = html_doc.replace("__DATA__", payload)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(html_doc, encoding="utf-8")

def _number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None

def _gc_total(lines):
    total = 0
    for line in lines:
        match = re.search(r"totalMs=(\d+)", line)
        if match:
            total += int(match.group(1))
    return total

def _meta(values):
    keys = ["javaVersion", "availableProcessors", "heapUsedMiB", "heapCommittedMiB", "heapMaxMiB", "nonHeapUsedMiB"]
    return " ".join(f'<span>{html.escape(k)}: <b>{html.escape(values.get(k,"—"))}</b></span>' for k in keys)

def _cards(reports, snapshots):
    count = len(reports)
    durations = [s["duration"] for s in snapshots if s["duration"] is not None]
    longest = max(durations) if durations else None
    heap = [s["heap"] for s in snapshots if s["heap"] is not None]
    latest = reports[-1]["values"] if reports else {}
    threshold = _number(latest.get("thresholdMs")) or 2000
    heap_ratio = None
    if reports:
        used = _number(latest.get("heapUsedMiB"))
        maximum = _number(latest.get("heapMaxMiB"))
        if used is not None and maximum and maximum > 0:
            heap_ratio = used / maximum
    if longest is not None and longest >= threshold * 5:
        status = "Critical · long stall captured"
    elif (longest is not None and longest >= threshold) or (heap_ratio is not None and heap_ratio >= .90):
        status = "Attention · threshold exceeded"
    elif any(r["values"].get("reason", "").startswith("LONG_") for r in reports):
        status = "Attention · long event recorded"
    else:
        status = "No long stall in these snapshots"
    return (
        f'<div class="cards"><article><small>Overall assessment</small><strong>{html.escape(status)}</strong></article>'
        f'<article><small>Snapshots</small><strong>{count}</strong></article>'
        + (f'<article><small>Longest captured tick/frame</small><strong>{longest:g} ms</strong></article>' if longest is not None else
           '<article><small>Longest captured tick/frame</small><strong>—</strong></article>')
        + (f'<article><small>Latest heap used</small><strong>{heap[-1]:g} MiB</strong></article>' if heap else
           '<article><small>Latest heap used</small><strong>—</strong></article>')
        + f'<article><small>Latest trigger</small><strong>{html.escape(latest.get("reason","—"))}</strong></article></div>'
    )

PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__</title><style>
:root{color-scheme:dark;--bg:#0b1020;--panel:#121a2b;--line:#26334b;--text:#e8eef9;--muted:#93a4bd;--accent:#78a9ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.55 system-ui,Segoe UI,sans-serif}
main{max-width:1180px;margin:auto;padding:32px 22px 70px}h1{font-size:30px;margin:0}h2{margin:34px 0 12px;font-size:20px}h3{font-size:16px}p{color:var(--muted)}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
header{display:flex;justify-content:space-between;align-items:end;gap:20px;margin-bottom:22px}.muted,small{color:var(--muted)}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}
article,.panel,.dump{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}article strong{display:block;font-size:24px;margin-top:5px;overflow-wrap:anywhere}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.chart{width:100%;height:230px;display:block}.timeline{display:grid;gap:9px}
.event{display:grid;grid-template-columns:190px 1fr 100px;gap:12px;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--panel);align-items:center}
.bar{height:7px;background:#263650;border-radius:9px;overflow:hidden;margin-top:6px}.bar i{display:block;height:100%;background:var(--accent)}
table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:10px;border-bottom:1px solid var(--line)}code{font:12px ui-monospace,Consolas,monospace;overflow-wrap:anywhere}
.dump{margin:12px 0}.thread{border-top:1px solid var(--line);padding:9px 0}.thread summary{display:flex;justify-content:space-between;gap:12px;cursor:pointer}.frames{padding:8px 0 4px}.frame{display:flex;gap:12px;padding:3px 6px;border-radius:5px}.frame:target{background:#3a2e1b}.ln{min-width:42px;text-align:right;color:var(--muted);font:11px ui-monospace,monospace}
.dump-meta{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.dump-meta span{padding:5px 8px;background:#1b2940;border-radius:7px;color:var(--muted);font-size:12px}
.actions{display:flex;gap:12px;flex-wrap:wrap}.pill{border:1px solid var(--line);border-radius:99px;padding:7px 12px}
@media(max-width:760px){.grid{grid-template-columns:1fr}.event{grid-template-columns:1fr}.header{display:block}}
</style></head><body><main>
<header><div><h1>SaveWatch</h1><p>Offline diagnostic report · generated from local snapshots</p></div><div class="actions"><a class="pill" href="#timeline">Timeline</a><a class="pill" href="#mods">Mods</a><a class="pill" href="#dumps">Thread dumps</a></div></header>
__CARDS__
<h2>Memory and GC</h2><div class="grid"><section class="panel"><h3>Heap usage</h3><canvas id="heap" class="chart"></canvas><p class="muted">Heap used / maximum, MiB. Missing values are omitted.</p></section><section class="panel"><h3>Cumulative GC time</h3><canvas id="gc" class="chart"></canvas><p class="muted">Cumulative GC time reported by the JVM; not a per-pause timeline.</p></section></div>
<h2 id="timeline">Stall timeline</h2><div id="timeline-list" class="timeline"></div>
<h2>Stack comparison</h2><section class="panel"><p id="stack-note">Comparing consecutive trigger-thread stacks across snapshots.</p><div id="stack-list"></div></section>
<h2 id="mods">Potentially involved mods</h2><section class="panel"><p>Sorted by how often a mod package appeared in the trigger thread. This is a lead for investigation, not proof of responsibility.</p><table><thead><tr><th>Mod ID</th><th>Trigger stack hits</th><th>All-thread hits</th><th>Snapshot</th></tr></thead><tbody>__MOD_ROWS__</tbody></table></section>
<h2 id="dumps">Thread dumps</h2><p>Expand a thread to inspect its stack. Click a line number to create a direct link to that exact frame.</p>
__DUMPS__
</main><script>
const data=__DATA__;
function chart(id, key, maxKey, suffix){const c=document.getElementById(id),ctx=c.getContext('2d'),w=c.clientWidth,h=c.clientHeight,dpr=devicePixelRatio||1;c.width=w*dpr;c.height=h*dpr;ctx.scale(dpr,dpr);ctx.strokeStyle='#34445f';ctx.fillStyle='#93a4bd';ctx.font='11px system-ui';ctx.beginPath();ctx.moveTo(34,12);ctx.lineTo(34,h-28);ctx.lineTo(w-8,h-28);ctx.stroke();const vals=data.map(x=>x[key]).filter(x=>x!==null);if(!vals.length){ctx.fillText('No data in snapshots',45,40);return}const max=Math.max(1,...data.map(x=>x[maxKey]??x[key]??0));const pts=data.map((x,i)=>({x:40+(w-55)*(i/Math.max(1,data.length-1)),y:h-30-(h-52)*((x[key]??0)/max),v:x[key]}));ctx.strokeStyle='#78a9ff';ctx.lineWidth=2;ctx.beginPath();let started=false;for(const p of pts){if(p.v===null){started=false;continue}if(!started){ctx.moveTo(p.x,p.y);started=true}else ctx.lineTo(p.x,p.y)}ctx.stroke();pts.forEach(p=>{if(p.v!==null){ctx.fillStyle='#78a9ff';ctx.beginPath();ctx.arc(p.x,p.y,3,0,Math.PI*2);ctx.fill()}});ctx.fillStyle='#93a4bd';ctx.fillText('0',8,h-26);ctx.fillText(Math.round(max)+suffix,4,15)}
chart('heap','heap','heapMax',' MiB');chart('gc','gc','gc',' ms');
const list=document.getElementById('timeline-list');const max=Math.max(1,...data.map(x=>x.duration||0));
data.forEach(s=>{const el=document.createElement('div');el.className='event';const dur=s.duration===null?'n/a':Math.round(s.duration)+' ms';el.innerHTML='<div><b>'+esc(s.time||'Unknown time')+'</b><div class="muted">'+esc(s.reason)+'</div></div><div><a href="#dump-'+s.id+'">'+esc(s.file)+'</a><div class="bar"><i style="width:'+Math.max(1,((s.duration||0)/max)*100)+'%"></i></div></div><div><b>'+dur+'</b><div class="muted">'+esc(s.trigger)+' · '+esc(s.state)+'</div></div>';list.appendChild(el)});
const stack=document.getElementById('stack-list');for(let i=1;i<data.length;i++){const a=data[i-1].frames,b=data[i].frames,shared=[...a].filter(x=>b.has(x)).length,union=new Set([...a,...b]).size,score=union?Math.round(shared/union*100):0;const p=document.createElement('p');p.textContent=data[i-1].file+' → '+data[i].file+': '+score+'% identical trigger-stack frames ('+shared+' shared).';stack.appendChild(p)}
if(data.length<2)document.getElementById('stack-note').textContent='At least two snapshots are needed for stack comparison.';
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
</script></body></html>"""

def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a self-contained SaveWatch HTML report.")
    parser.add_argument("paths", nargs="*", type=Path, help="Snapshot files or folders (default: logs/savewatch)")
    parser.add_argument("-o", "--output", type=Path, help="Output HTML path (default: index.html in the first folder)")
    parser.add_argument("--open", action="store_true", help="Open the generated report in the default browser")
    args = parser.parse_args()
    roots = args.paths or [Path("logs/savewatch")]
    files = []
    for root in roots:
        if root.is_dir():
            files.extend(p for p in root.glob("*.txt") if p.name.lower() != "index.html")
        elif root.is_file() and root.suffix.lower() == ".txt":
            files.append(root)
        else:
            print(f"[WARN] Not found or unsupported: {root}", file=sys.stderr)
    files = sorted(set(files), key=lambda p: (p.stat().st_mtime_ns, str(p)))
    if not files:
        print("[ERROR] No SaveWatch .txt snapshots found.", file=sys.stderr)
        return 2
    reports = [parse(path) for path in files]
    output = args.output or ((roots[0] / "index.html") if roots[0].is_dir() else roots[0].parent / "index.html")
    build(reports, output.resolve())
    print(f"[OK] HTML report: {output.resolve()}")
    if args.open:
        import webbrowser
        webbrowser.open(output.resolve().as_uri())
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
