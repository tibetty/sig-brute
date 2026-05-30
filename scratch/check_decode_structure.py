#!/usr/bin/env python3
"""
Compare decode output to known 4byte signatures (structure only; leaf types ignored).
Decoder runs on calldata bytes only (manifest signatures are ground truth, not skeleton hints).
Reports greedy and heuristic_search match counts (see TupleCorpusLocalEvaluationTest).

Modes:
  decoded  — compare AbiDecoder arg trees (recommended); runs Gradle test
  skeleton — compare YAML prototype shape with skeleton decode (both strategies)
  shallow  — shallow skeleton (opaque tuple at top level); runs Gradle test
  full     — full inline skeleton from Function line; runs Gradle test
  benchmark — run decoded + shallow + full and write structure-check-benchmark.json
  prototype — compare recovered prototype comment line from YAML (legacy; often flat)

Usage:
  python3 scratch/check_decode_structure.py
  python3 scratch/check_decode_structure.py --mode skeleton
  python3 scratch/check_decode_structure.py --mode shallow
  python3 scratch/check_decode_structure.py --mode benchmark
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


def _load_report(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _print_mode_summary(label: str, data: dict) -> None:
    total = data.get("total", 0)
    greedy = data.get("greedy_match", data.get("match", 0))
    search = data.get("heuristic_search_match", greedy)
    pct = (100.0 * greedy / total) if total else 0.0
    print(
        f"  {label}: greedy {greedy}/{total} ({pct:.1f}%), "
        f"heuristic_search {search}/{total}, errors {data.get('error', 0)}"
    )


def _print_shallow_report(data: dict) -> None:
    total = data.get("total", 0)
    for mode_key, mode_label in (
        ("with_function_inline", "shallow + Function inline"),
        ("opaque_only", "shallow opaque only"),
    ):
        mode = data.get(mode_key)
        if not mode:
            continue
        nested = mode.get("nested", mode)
        top = mode.get("top_level_only", {})
        for block_key, block_label in (
            ("nested", "full nesting"),
            ("top_level_only", "top-level only (tuple interiors ignored)"),
        ):
            block = mode.get(block_key, nested if block_key == "nested" else top)
            if not block:
                continue
            greedy = block.get("greedy_match", 0)
            search = block.get("heuristic_search_match", greedy)
            pct = (100.0 * greedy / total) if total else 0.0
            print(
                f"  {mode_label} / {block_label}: greedy {greedy}/{total} ({pct:.1f}%), "
                f"heuristic_search {search}/{total}, "
                f"mismatches {block.get('mismatch', 0)}, errors {block.get('error', 0)}"
            )


def _run_gradle_corpus_test(root: Path, class_name: str) -> int:
    _prepare_gradle_test(root)
    return subprocess.run(
        [str(root / "gradlew"), "-q", "test", "--tests", class_name],
        cwd=root,
        check=False,
    ).returncode


def run_shallow_mode(root: Path, corpus: Path) -> int:
    if _missing_manifest_files(corpus):
        print("Corpus manifest out of sync — run reindex_corpus.py", file=sys.stderr)
        return 1
    code = _run_gradle_corpus_test(
        root, "me.tibetty.sigbrute.decode.strategy.TupleCorpusShallowSkeletonEvaluationTest"
    )
    report = corpus / "structure-check-shallow.json"
    if report.exists():
        data = _load_report(report)
        _print_shallow_report(data)
        print(f"Report: {report}")
    return code


def run_full_skeleton_mode(root: Path, corpus: Path) -> int:
    if _missing_manifest_files(corpus):
        print("Corpus manifest out of sync — run reindex_corpus.py", file=sys.stderr)
        return 1
    code = _run_gradle_corpus_test(
        root, "me.tibetty.sigbrute.decode.strategy.TupleCorpusFullSkeletonEvaluationTest"
    )
    report = corpus / "structure-check-full-skeleton.json"
    if report.exists():
        _print_mode_summary("full skeleton", _load_report(report))
        print(f"Report: {report}")
    return code


def run_benchmark_mode(root: Path, corpus: Path) -> int:
    if _missing_manifest_files(corpus):
        print("Corpus manifest out of sync — run reindex_corpus.py", file=sys.stderr)
        return 1

    t0 = time.perf_counter()
    modes = [
        (
            "no_skeleton",
            "me.tibetty.sigbrute.decode.strategy.TupleCorpusLocalEvaluationTest",
            corpus / "structure-check-decoded.json",
        ),
        (
            "shallow_skeleton",
            "me.tibetty.sigbrute.decode.strategy.TupleCorpusShallowSkeletonEvaluationTest",
            corpus / "structure-check-shallow.json",
        ),
        (
            "full_skeleton",
            "me.tibetty.sigbrute.decode.strategy.TupleCorpusFullSkeletonEvaluationTest",
            corpus / "structure-check-full-skeleton.json",
        ),
    ]
    results: dict[str, dict] = {}
    exit_code = 0
    print("Decode structure benchmark (greedy match rate; both strategies tracked in JSON):")
    for label, test_class, report_path in modes:
        step_start = time.perf_counter()
        code = _run_gradle_corpus_test(root, test_class)
        if code != 0:
            exit_code = code
        data = _load_report(report_path)
        data["elapsed_sec"] = round(time.perf_counter() - step_start, 3)
        total = data.get("total", 0)
        inline = data.get("with_function_inline", data)
        nested = inline.get("nested", inline)
        greedy = nested.get("greedy_match", data.get("greedy_match", 0))
        data["greedy_pct"] = round(100.0 * greedy / total, 2) if total else 0.0
        opaque = data.get("opaque_only", {})
        opaque_nested = opaque.get("nested", opaque)
        opaque_greedy = opaque_nested.get("greedy_match", 0)
        data["opaque_only_greedy_pct"] = round(100.0 * opaque_greedy / total, 2) if total else 0.0
        opaque_top = opaque.get("top_level_only", {})
        opaque_top_greedy = opaque_top.get("greedy_match", 0)
        data["opaque_only_top_level_greedy_pct"] = (
            round(100.0 * opaque_top_greedy / total, 2) if total else 0.0
        )
        results[label] = data
        if label == "shallow_skeleton":
            _print_shallow_report(data)
        else:
            _print_mode_summary(label, data)

    bench = {
        "total_fixtures": results.get("no_skeleton", {}).get("total", 0),
        "elapsed_sec": round(time.perf_counter() - t0, 3),
        "modes": results,
    }
    bench_path = corpus / "structure-check-benchmark.json"
    bench_path.write_text(json.dumps(bench, indent=2) + "\n", encoding="utf-8")
    print(f"Benchmark report: {bench_path} ({bench['elapsed_sec']}s total)")
    return exit_code


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

    code = _run_gradle_corpus_test(
        root, "me.tibetty.sigbrute.decode.strategy.TupleCorpusLocalEvaluationTest"
    )
    report = corpus / "structure-check-decoded.json"
    if report.exists():
        data = _load_report(report)
        _print_mode_summary("no skeleton", data)
        print(
            f"  mismatches: {data.get('mismatch', 0)}, report: {report}"
        )
    return code


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


def _decode_calldata_fixture(jar: Path, path: Path, strategy: str | None = None) -> subprocess.CompletedProcess[str]:
    cmd = ["java", "-jar", str(jar), "decode"]
    if strategy is not None:
        cmd.extend(["--strategy", strategy])
    cmd.append(str(path))
    return subprocess.run(cmd, capture_output=True, text=True, check=False)


def _prototype_from_decode_stdout(stdout: str) -> str:
    for ln in stdout.splitlines():
        stripped = ln.strip()
        if stripped.startswith("#   ") and "(" in ln:
            return stripped[4:].strip()
    raise ValueError("prototype line not found in decode output")


@dataclass(frozen=True)
class _SkeletonFixtureResult:
    status: str
    detail: str | None = None


def _check_skeleton_fixture(
    jar: Path, path: Path, text_signature: str, strategy: str
) -> _SkeletonFixtureResult:
    out = _decode_calldata_fixture(jar, path, strategy)
    if out.returncode != 0:
        return _SkeletonFixtureResult("error", out.stderr.strip() or f"exit {out.returncode}")
    try:
        proto = _prototype_from_decode_stdout(out.stdout)
        known = parse_signature(text_signature).shape()
        got = parse_prototype_line(proto).shape()
        if known == got:
            return _SkeletonFixtureResult("ok")
        return _SkeletonFixtureResult("mismatch", "prototype mismatch")
    except Exception as e:
        return _SkeletonFixtureResult("error", str(e))


def _evaluate_skeleton_strategy(
    manifest: list, jar: Path, corpus: Path, strategy: str
) -> dict:
    ok = mismatches = errors = 0
    mismatch_lines: list[str] = []
    error_lines: list[str] = []
    t0 = time.perf_counter()
    for entry in manifest:
        path = corpus / entry["file"]
        label = Path(entry["file"]).name
        result = _check_skeleton_fixture(jar, path, entry["text_signature"], strategy)
        if result.status == "ok":
            ok += 1
        elif result.status == "mismatch":
            mismatches += 1
            mismatch_lines.append(f"{label}: {result.detail}")
        else:
            errors += 1
            error_lines.append(f"{label}: {result.detail}")

    elapsed = time.perf_counter() - t0
    total = len(manifest)
    return {
        "total": total,
        "match": ok,
        "mismatch": mismatches,
        "error": errors,
        "elapsed_sec": round(elapsed, 3),
        "avg_ms_per_file": round((elapsed / total) * 1000, 2) if total else 0.0,
        "mismatches": mismatch_lines,
        "errors": error_lines,
    }


def _print_skeleton_strategy_summary(strategy: str, stats: dict) -> None:
    total = stats["total"]
    elapsed = stats["elapsed_sec"]
    if total:
        timing = f"{elapsed:.2f}s total ({(elapsed / total * 1000):.1f} ms/file)"
    else:
        timing = f"{elapsed:.2f}s total"
    print(
        f"Skeleton structure check [{strategy}]: {stats['match']}/{total} match, "
        f"{stats['mismatch']} mismatch, {stats['error']} error, {timing}"
    )


def _skeleton_stats_clean(stats: dict[str, dict]) -> bool:
    return all(s["mismatch"] == 0 and s["error"] == 0 for s in stats.values())


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
    strategies = ("greedy", "heuristic_search")
    stats = {strategy: _evaluate_skeleton_strategy(manifest, jar, corpus, strategy)
             for strategy in strategies}
    for strategy in strategies:
        _print_skeleton_strategy_summary(strategy, stats[strategy])

    report = corpus / "structure-check-skeleton.json"
    report.write_text(json.dumps(stats, indent=2) + "\n", encoding="utf-8")
    print(f"Report: {report}")
    return 0 if _skeleton_stats_clean(stats) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--mode",
        choices=("decoded", "skeleton", "shallow", "full", "benchmark", "prototype"),
        default="decoded",
        help="decoded: no skeleton; shallow/full: skeleton depth; benchmark: all three",
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
    if args.mode == "shallow":
        return run_shallow_mode(root, corpus)
    if args.mode == "full":
        return run_full_skeleton_mode(root, corpus)
    if args.mode == "benchmark":
        return run_benchmark_mode(root, corpus)
    return run_prototype_mode(root, corpus)


if __name__ == "__main__":
    raise SystemExit(main())
