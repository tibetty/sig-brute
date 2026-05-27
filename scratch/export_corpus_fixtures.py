#!/usr/bin/env python3
"""Export CI fixtures: samples that pass greedy and heuristic_search decode without skeleton hints."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "scratch/tuple-calldata-corpus"
DST = ROOT / "src/test/resources/tuple-corpus"
REPORT = SRC / "structure-check-decoded.json"
MAX_FIXTURES = 20


def main() -> None:
    manifest = json.loads((SRC / "manifest.json").read_text(encoding="utf-8"))
    by_file = {Path(row["file"]).name: row for row in manifest}

    if REPORT.exists():
        report = json.loads(REPORT.read_text(encoding="utf-8"))
        mismatches = {m.split(" — ")[0] for m in report["mismatches"]}
        errors = {e.split(":")[0] for e in report["errors"]}
        greedy_bad = {
            m.split(" — ")[0] for m in report["mismatches"] if "[greedy]" in m
        }
        heuristic_bad = {
            m.split(" — ")[0] for m in report["mismatches"] if "[heuristic_search]" in m
        }
        errors = {e.split(":")[0] for e in report["errors"]}
        both_ok = {
            n
            for n in by_file
            if n not in greedy_bad and n not in heuristic_bad and n not in errors
        }
        names = sorted(both_ok)[:MAX_FIXTURES]
        print(f"Selecting {len(names)} passing samples from {REPORT.name}")
    else:
        names = sorted(by_file)[:MAX_FIXTURES]
        print("No structure report — exporting first", len(names), "samples")

    calldata_dst = DST / "calldata"
    calldata_dst.mkdir(parents=True, exist_ok=True)
    for old in calldata_dst.glob("*.calldata"):
        old.unlink()

    subset = []
    for name in names:
        row = by_file[name]
        shutil.copy2(SRC / row["file"], calldata_dst / name)
        subset.append(
            {
                "id": name,
                "selector": row["selector"],
                "text_signature": row["text_signature"],
                "file": "calldata/" + name,
            }
        )

    (DST / "manifest.json").write_text(
        json.dumps(subset, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Exported {len(subset)} fixtures to {DST}")


if __name__ == "__main__":
    main()
