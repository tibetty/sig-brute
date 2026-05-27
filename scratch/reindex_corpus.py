#!/usr/bin/env python3
"""Renumber calldata/*.calldata to contiguous 001..N names; refresh manifest paths.

Sort order: selector ascending (stable). Uses two-phase rename to avoid collisions.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

_SCRATCH = Path(__file__).resolve().parent
if str(_SCRATCH) not in sys.path:
    sys.path.insert(0, str(_SCRATCH))
from fetch_tuple_calldata_corpus import safe_filename  # noqa: E402

StagedRow = tuple[Path, dict, int]


def _load_sorted_manifest(manifest_path: Path) -> list[dict]:
    if not manifest_path.exists():
        raise SystemExit(f"No manifest at {manifest_path}")
    manifest: list[dict] = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest.sort(key=lambda row: row["selector"])
    return manifest


def _stage_manifest_files(
    out: Path, manifest: list[dict], staging: Path, *, dry_run: bool
) -> list[StagedRow]:
    staged: list[StagedRow] = []
    for i, row in enumerate(manifest, start=1):
        src = out / row["file"]
        if not src.is_file():
            raise SystemExit(f"Missing calldata file: {src}")
        tmp = staging / f"stage_{i:06d}.calldata"
        if not dry_run:
            shutil.move(src, tmp)
            staged.append((tmp, row, i))
        else:
            staged.append((src, row, i))
    return staged


def _apply_final_names(
    out: Path, calldata_dir: Path, staged: list[StagedRow], *, dry_run: bool
) -> None:
    for path, row, i in staged:
        name = safe_filename(row["text_signature"], row["selector"], i)
        dest = calldata_dir / name
        if dry_run:
            continue
        shutil.move(path, dest)
        row["file"] = str(dest.relative_to(out))


def _persist_manifest_and_prune(
    manifest_path: Path,
    calldata_dir: Path,
    manifest: list[dict],
    staging: Path,
) -> None:
    if staging.exists():
        staging.rmdir()
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    keep = {Path(row["file"]).name for row in manifest}
    for path in calldata_dir.glob("*.calldata"):
        if not path.name.startswith("._") and path.name not in keep:
            path.unlink()


def rebuild_manifest_from_disk(out: Path) -> int:
    """Rebuild manifest.json from calldata/*.calldata on disk (fixes stale paths)."""
    from fetch_tuple_calldata_corpus import dedupe_corpus

    sig_path = out / "signatures.json"
    selectors: dict[str, str] = {}
    if sig_path.exists():
        data = json.loads(sig_path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            selectors = {row["selector"]: row["text_signature"] for row in data}
        elif isinstance(data, dict):
            selectors = data

    manifest, removed = dedupe_corpus(out, selectors)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Rebuilt manifest: {len(manifest)} samples ({removed} duplicate file(s) removed)")
    return len(manifest)


def reindex(out: Path, *, dry_run: bool = False) -> int:
    manifest_path = out / "manifest.json"
    calldata_dir = out / "calldata"
    manifest = _load_sorted_manifest(manifest_path)

    staging = calldata_dir / ".reindex_staging"
    if not dry_run:
        staging.mkdir(parents=True, exist_ok=True)

    staged = _stage_manifest_files(out, manifest, staging, dry_run=dry_run)
    _apply_final_names(out, calldata_dir, staged, dry_run=dry_run)
    if not dry_run:
        _persist_manifest_and_prune(manifest_path, calldata_dir, manifest, staging)
    return len(manifest)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--out",
        type=Path,
        default=Path("scratch/tuple-calldata-corpus"),
        help="corpus root directory",
    )
    ap.add_argument("--dry-run", action="store_true", help="report only; do not rename")
    ap.add_argument(
        "--rebuild-manifest",
        action="store_true",
        help="rebuild manifest.json from .calldata files on disk before reindex",
    )
    args = ap.parse_args()
    out = args.out.resolve()
    if args.rebuild_manifest:
        rebuild_manifest_from_disk(out)
    n = reindex(out, dry_run=args.dry_run)
    print(f"{'Would reindex' if args.dry_run else 'Reindexed'} {n} samples under {out}/calldata")


if __name__ == "__main__":
    main()
