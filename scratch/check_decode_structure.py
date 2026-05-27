#!/usr/bin/env python3
"""
Compare decode output to known 4byte signatures (structure only; leaf types ignored).
Decoder runs on calldata bytes only (manifest signatures are ground truth, not skeleton hints).
Reports greedy and heuristic_search match counts (see TupleCorpusLocalEvaluationTest).

Modes:
  decoded  — compare AbiDecoder arg trees (recommended); runs Gradle test
  skeleton — compare YAML prototype shape with skeleton decode (both strategies)
  prototype — compare recovered prototype comment line from YAML (legacy; often flat)

Usage:
  python3 scratch/check_decode_structure.py
  python3 scratch/check_decode_structure.py --mode skeleton
  python3 scratch/check_decode_structure.py --mode prototype
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

# --- prototype-mode helpers (legacy) -----------------------------------------


@dataclass(frozen=True)
class StructNode:
    kind: str
    array_suffix: str
    fields: tuple[StructNode, ...] = ()

    def shape(self) -> tuple[str, str, tuple]:
        children = () if self.kind == "leaf" else tuple(f.shape() for f in self.fields)
        return (self.kind, self.array_suffix, children)


def split_top_level_params(s: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    start = 0
    for i, ch in enumerate(s):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(s[start:i].strip())
            start = i + 1
    tail = s[start:].strip()
    if tail:
        parts.append(tail)
    return parts


def parse_type(type_str: str) -> StructNode:
    s = type_str.strip()
    array_suffix = ""
    while True:
        m = re.search(r"\[[^\]]*\]$", s)
        if not m:
            break
        array_suffix = m.group(0) + array_suffix
        s = s[: m.start()].strip()
    if s.startswith("("):
        inner = s[1:-1]
        fields = tuple(parse_type(p) for p in split_top_level_params(inner))
        return StructNode("tuple", array_suffix, fields)
    return StructNode("leaf", array_suffix, ())


def parse_signature(text_sig: str) -> StructNode:
    lp = text_sig.index("(")
    rp = text_sig.rindex(")")
    fields = tuple(parse_type(p) for p in split_top_level_params(text_sig[lp + 1 : rp]))
    return StructNode("tuple", "", fields)


def parse_prototype_line(proto: str) -> StructNode:
    lp = proto.index("(")
    fields = tuple(parse_type(p) for p in split_top_level_params(proto[lp + 1 : proto.rindex(")")]))
    return StructNode("tuple", "", fields)


def _missing_manifest_files(corpus: Path) -> int:
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    return sum(1 for row in manifest if not (corpus / row["file"]).is_file())


def _prepare_gradle_test(root: Path) -> None:
    import shutil

    results = root / "build/test-results/test/binary"
    if results.exists():
        shutil.rmtree(results, ignore_errors=True)
    for dot in root.rglob("._*"):
        if dot.is_file():
            dot.unlink(missing_ok=True)


def run_decoded_mode(root: Path, corpus: Path) -> int:
    missing = _missing_manifest_files(corpus)
    if missing:
        print(
            f"Corpus manifest out of sync: {missing} calldata file(s) missing on disk.\n"
            "Fix: python3 scratch/reindex_corpus.py --rebuild-manifest && "
            "python3 scratch/reindex_corpus.py",
            file=sys.stderr,
        )
        return 1

    _prepare_gradle_test(root)
    proc = subprocess.run(
        [
            str(root / "gradlew"),
            "-q",
            "test",
            "--tests",
            "me.tibetty.sigbrute.decode.strategy.TupleCorpusLocalEvaluationTest",
        ],
        cwd=root,
        check=False,
    )
    report = corpus / "structure-check-decoded.json"
    if report.exists():
        data = json.loads(report.read_text(encoding="utf-8"))
        total = data.get("total", 0)
        greedy = data.get("greedy_match", data.get("match", 0))
        search = data.get("heuristic_search_match", greedy)
        print(
            f"Decoded structure check: greedy {greedy}/{total}, "
            f"heuristic_search {search}/{total}, "
            f"{data.get('mismatch', 0)} mismatch, {data.get('error', 0)} error"
        )
        print(f"Report: {report}")
    return proc.returncode


def run_prototype_mode(root: Path, corpus: Path) -> int:
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    jar = _ensure_decode_jar(root)

    ok = mismatches = errors = 0
    mismatch_lines: list[str] = []
    error_lines: list[str] = []
    for entry in manifest:
        path = corpus / entry["file"]
        label = Path(entry["file"]).name
        try:
            out = subprocess.run(
                ["java", "-jar", str(jar), "decode", str(path)],
                capture_output=True,
                text=True,
                check=False,
            )
            if out.returncode != 0:
                raise RuntimeError(out.stderr.strip() or f"exit {out.returncode}")
            proto = next(
                ln[4:].strip()
                for ln in out.stdout.splitlines()
                if ln.strip().startswith("#   ") and "(" in ln
            )
            if parse_signature(entry["text_signature"]).shape() == parse_prototype_line(proto).shape():
                ok += 1
            else:
                mismatches += 1
                mismatch_lines.append(f"{label}: prototype mismatch")
        except Exception as e:
            errors += 1
            error_lines.append(f"{label}: {e}")

    total = len(manifest)
    print(f"Prototype structure check: {ok}/{total} match, {mismatches} mismatch, {errors} error")
    report = corpus / "structure-check-prototype.json"
    report.write_text(
        json.dumps(
            {
                "total": total,
                "match": ok,
                "mismatch": mismatches,
                "error": errors,
                "mismatches": mismatch_lines,
                "errors": error_lines,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Report: {report}")
    return 0 if mismatches == 0 and errors == 0 else 1


def _ensure_decode_jar(root: Path) -> Path:
    jar = sorted((root / "build/libs").glob("sig-brute-*-all.jar"))
    if not jar:
        jar = [root / "build/libs/sig-brute-all.jar"]
    if not jar[0].exists():
        subprocess.run([str(root / "gradlew"), "shadowJar", "-q"], cwd=root, check=True)
        jar = sorted((root / "build/libs").glob("sig-brute-*-all.jar")) or [
            root / "build/libs/sig-brute-all.jar"
        ]
    return jar[0]


def run_skeleton_mode(root: Path, corpus: Path) -> int:
    if _missing_manifest_files(corpus):
        print(
            "Corpus manifest out of sync.\n"
            "Fix: python3 scratch/reindex_corpus.py --rebuild-manifest && "
            "python3 scratch/reindex_corpus.py",
            file=sys.stderr,
        )
        return 1

    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    jar = _ensure_decode_jar(root)
    stats: dict[str, dict] = {}
    for strategy in ("greedy", "heuristic_search"):
        ok = mismatches = errors = 0
        mismatch_lines: list[str] = []
        error_lines: list[str] = []
        t0 = time.perf_counter()
        for entry in manifest:
            path = corpus / entry["file"]
            label = Path(entry["file"]).name
            out = subprocess.run(
                ["java", "-jar", str(jar), "decode", "--strategy", strategy, str(path)],
                capture_output=True,
                text=True,
                check=False,
            )
            if out.returncode != 0:
                errors += 1
                error_lines.append(f"{label}: {out.stderr.strip() or f'exit {out.returncode}'}")
                continue
            try:
                proto = next(
                    ln[4:].strip()
                    for ln in out.stdout.splitlines()
                    if ln.strip().startswith("#   ") and "(" in ln
                )
                known = parse_signature(entry["text_signature"]).shape()
                got = parse_prototype_line(proto).shape()
                if known == got:
                    ok += 1
                else:
                    mismatches += 1
                    mismatch_lines.append(f"{label}: prototype mismatch")
            except Exception as e:
                errors += 1
                error_lines.append(f"{label}: {e}")

        elapsed = time.perf_counter() - t0
        total = len(manifest)
        stats[strategy] = {
            "total": total,
            "match": ok,
            "mismatch": mismatches,
            "error": errors,
            "elapsed_sec": round(elapsed, 3),
            "avg_ms_per_file": round((elapsed / total) * 1000, 2) if total else 0.0,
            "mismatches": mismatch_lines,
            "errors": error_lines,
        }
        print(
            f"Skeleton structure check [{strategy}]: {ok}/{total} match, "
            f"{mismatches} mismatch, {errors} error, {elapsed:.2f}s total "
            f"({(elapsed / total * 1000):.1f} ms/file)"
        )

    report = corpus / "structure-check-skeleton.json"
    report.write_text(json.dumps(stats, indent=2) + "\n", encoding="utf-8")
    print(f"Report: {report}")
    return 0 if all(stats[s]["mismatch"] == 0 and stats[s]["error"] == 0 for s in stats) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--mode",
        choices=("decoded", "skeleton", "prototype"),
        default="decoded",
        help="decoded: no-skeleton tree check; skeleton: strategy compare; prototype: YAML comment",
    )
    ap.add_argument("--corpus", type=Path, default=Path("scratch/tuple-calldata-corpus"))
    args = ap.parse_args()

    root = Path(__file__).resolve().parents[1]
    corpus = args.corpus.resolve()
    if not (corpus / "manifest.json").exists():
        print(f"Missing corpus at {corpus}", file=sys.stderr)
        return 1

    if args.mode == "decoded":
        return run_decoded_mode(root, corpus)
    if args.mode == "skeleton":
        return run_skeleton_mode(root, corpus)
    return run_prototype_mode(root, corpus)


if __name__ == "__main__":
    raise SystemExit(main())
