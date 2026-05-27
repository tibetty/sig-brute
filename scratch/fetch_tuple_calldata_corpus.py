#!/usr/bin/env python3
"""
Fetch ~N real Ethereum calldata samples for 4byte signatures that include tuple
(struct) parameters. Writes Etherscan-style .calldata files + manifest.json locally.

Usage:
  python3 scratch/fetch_tuple_calldata_corpus.py [--target 100] [--out scratch/tuple-calldata-corpus]

No network credentials required (public 4byte API + public Ethereum RPC).
"""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

FOURBYTE_BASE = "https://www.4byte.directory/api/v1/signatures/"
RPC_URLS = [
    "https://eth.drpc.org",
    "https://ethereum-rpc.publicnode.com",
]
USER_AGENT = "sig-brute-corpus/1.0"
_rpc_idx = 0

# Nested struct/tuple in parameter list: inner '(', literal 'tuple', or tuple-array forms.
TUPLE_SIG = re.compile(
    r"^[A-Za-z_]\w*\("
    r"(?:[^()]*\([^)]+\)[^()]*)+"  # at least one nested (...) group in params
    r"\)$"
    r"|^[^()]+\([^)]*\btuple\b"  # Etherscan-style opaque tuple
    r"|^[^()]+\([^)]*\([^)]+\)\[\]"  # (T,...)[]
    ,
    re.IGNORECASE,
)


def http_json(url: str, *, data: bytes | None = None, retries: int = 4) -> dict | list:
    headers = {"User-Agent": USER_AGENT}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    delay = 1.0
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504) and attempt + 1 < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise
        except urllib.error.URLError:
            if attempt + 1 < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise
    raise RuntimeError("unreachable")


def rpc(method: str, params: list) -> dict:
    global _rpc_idx
    payload = json.dumps({"jsonrpc": "2.0", "method": method, "params": params, "id": 1}).encode()
    last_err: Exception | None = None
    for _ in range(len(RPC_URLS) * 2):
        url = RPC_URLS[_rpc_idx % len(RPC_URLS)]
        try:
            out = http_json(url, data=payload)
            if "error" in out:
                code = out["error"].get("code")
                msg = str(out["error"].get("message", ""))
                if code in (-32001, -32005) or "limit" in msg.lower():
                    _rpc_idx += 1
                    time.sleep(0.5)
                    continue
                raise RuntimeError(f"RPC {method}@{url}: {out['error']}")
            return out["result"]
        except Exception as e:
            last_err = e
            _rpc_idx += 1
            time.sleep(0.5)
    raise RuntimeError(f"RPC {method} failed on all endpoints: {last_err}")


def is_tuple_signature(text: str) -> bool:
    text = text.strip()
    if not text or "(" not in text:
        return False
    # quick reject: no nested structure
    inner = text[text.index("(") :]
    if "tuple" in inner.lower():
        return True
    if inner.count("(") >= 2:
        return TUPLE_SIG.match(text) is not None
    return False


def normalize_selector(hex_sig: str | None) -> str | None:
    if not hex_sig:
        return None
    sel = hex_sig.lower()
    if not sel.startswith("0x"):
        sel = "0x" + sel
    if len(sel) != 10:
        return None
    return sel


def try_register_signature(
    by_selector: dict[str, str],
    hex_sig: str | None,
    text_sig: str | None,
) -> None:
    """Keep the longest text_signature per selector when it looks tuple-shaped."""
    sel = normalize_selector(hex_sig)
    if sel is None or not text_sig or not is_tuple_signature(text_sig):
        return
    prev = by_selector.get(sel)
    if prev is None or len(text_sig) > len(prev):
        by_selector[sel] = text_sig


def paginate_fourbyte(
    params: dict[str, str],
    by_selector: dict[str, str],
    *,
    max_sigs: int,
    max_pages: int,
    pause_sec: float,
) -> None:
    url: str | None = FOURBYTE_BASE + "?" + urllib.parse.urlencode(params)
    pages = 0
    while url and len(by_selector) < max_sigs and pages < max_pages:
        data = http_json(url)
        for row in data.get("results", []):
            try_register_signature(
                by_selector,
                row.get("hex_signature"),
                row.get("text_signature"),
            )
        url = data.get("next")
        if url:
            url = url.replace("http://", "https://")
        pages += 1
        time.sleep(pause_sec)


def fetch_tuple_signatures(max_sigs: int = 2500) -> dict[str, str]:
    """selector (0x...) -> best text_signature"""
    by_selector: dict[str, str] = {}
    queries = (
        "tuple", "(uint", "(address", "(bytes", "(string", "(int", "(bool",
        "[]", "execute(", "swap(", "multicall", "deposit(", "claim(",
    )
    for query in queries:
        paginate_fourbyte(
            {"text_signature": query, "page_size": "100"},
            by_selector,
            max_sigs=max_sigs,
            max_pages=40,
            pause_sec=0.2,
        )
    paginate_fourbyte(
        {"ordering": "-created_at", "page_size": "100"},
        by_selector,
        max_sigs=max_sigs,
        max_pages=150,
        pause_sec=0.15,
    )
    return by_selector


def hex_to_words(calldata: str) -> tuple[str, list[str]]:
    h = calldata.lower()
    if h.startswith("0x"):
        h = h[2:]
    if len(h) < 8 or len(h) % 2 != 0:
        raise ValueError("invalid calldata hex")
    selector = h[:8]
    body = h[8:]
    words = [body[i : i + 64] for i in range(0, len(body), 64)]
    return selector, words


def format_etherscan(text_sig: str, calldata: str) -> str:
    selector, words = hex_to_words(calldata)
    lines = [
        f"Function: {text_sig}",
        "",
        f"MethodID: 0x{selector}",
    ]
    for i, w in enumerate(words):
        lines.append(f"[{i}]:  {w}")
    lines.append("")
    return "\n".join(lines)


_ETHER_WORD = re.compile(r"^\[(\d+)\]:\s*([0-9a-fA-F]{64})\s*$")


def parse_etherscan_file(path: Path) -> tuple[str, str, str]:
    """Return (selector, text_signature, calldata hex) from a .calldata file."""
    text_sig = ""
    selector = ""
    words: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("Function:"):
            text_sig = line.split(":", 1)[1].strip()
        elif line.strip().lower().startswith("methodid:"):
            m = re.search(r"0x?([0-9a-fA-F]{8})", line)
            if m:
                selector = "0x" + m.group(1).lower()
        else:
            wm = _ETHER_WORD.match(line.strip())
            if wm:
                words.append(wm.group(2).lower())
    if not selector or not words:
        raise ValueError(f"not Etherscan-format calldata: {path}")
    return selector, text_sig, "0x" + selector[2:] + "".join(words)


def repair_manifest(
    out: Path, selectors: dict[str, str], manifest: list[dict]
) -> list[dict]:
    """Add manifest rows for .calldata files on disk that are missing from the index."""
    by_file = {row["file"]: row for row in manifest}
    calldata_dir = out / "calldata"
    for path in sorted(calldata_dir.glob("*.calldata")):
        if path.name.startswith("._"):
            continue
        rel = str(path.relative_to(out))
        if rel in by_file:
            continue
        sel, text_sig, hex_cd = parse_etherscan_file(path)
        if not text_sig:
            text_sig = selectors.get(sel, "")
        entry = {
            "selector": sel,
            "text_signature": text_sig,
            "calldata": hex_cd,
            "tx_hash": None,
            "block_number": None,
            "to": None,
            "from": None,
            "file": rel,
        }
        manifest.append(entry)
        by_file[rel] = entry
        print(f"  repaired manifest: {rel}")
    return manifest


def resolve_scan_start(
    start_block: int | None, state_path: Path | None, latest: int
) -> int:
    if start_block is not None:
        return start_block
    if state_path and state_path.exists():
        return json.loads(state_path.read_text(encoding="utf-8")).get("last_block", latest)
    return latest


def hit_from_transaction(
    tx: dict, block_number: int, selector_set: set[str], selectors: dict[str, str]
) -> dict | None:
    inp = tx.get("input") or ""
    # selector + at least one 32-byte ABI word (reject bytecode / garbage)
    if not inp or inp == "0x" or len(inp) < 74:
        return None
    sel = inp[:10].lower()
    if sel not in selector_set or tx.get("to") is None:
        return None
    return {
        "selector": sel,
        "text_signature": selectors[sel],
        "calldata": inp,
        "tx_hash": tx.get("hash"),
        "block_number": block_number,
        "to": tx.get("to"),
        "from": tx.get("from"),
    }


def fetch_block(block_number: int) -> dict | None:
    try:
        return rpc("eth_getBlockByNumber", [hex(block_number), True])
    except RuntimeError as e:
        print(f"  RPC error at block {block_number}: {e}")
        time.sleep(2)
        return None


def persist_scan_state(state_path: Path | None, last_block: int, found_count: int) -> None:
    if not state_path:
        return
    state_path.write_text(
        json.dumps({"last_block": last_block, "found": found_count}) + "\n",
        encoding="utf-8",
    )


def _load_manifest_by_file(manifest_path: Path) -> dict[str, dict]:
    if not manifest_path.exists():
        return {}
    return {
        row["file"]: row
        for row in json.loads(manifest_path.read_text(encoding="utf-8"))
    }


def _dedupe_entry_from_file(
    path: Path, out: Path, by_file: dict[str, dict], selectors: dict[str, str]
) -> dict | None:
    if path.name.startswith("._"):
        return None
    rel = str(path.relative_to(out))
    try:
        sel, text_sig, cd = parse_etherscan_file(path)
    except ValueError:
        print(f"  skip invalid calldata: {rel}")
        return None
    meta = by_file.get(rel, {})
    if not text_sig:
        text_sig = meta.get("text_signature") or selectors.get(sel, "")
    idx_m = re.match(r"^(\d+)_", path.name)
    idx = int(idx_m.group(1)) if idx_m else 999_999
    return {
        "selector": sel,
        "text_signature": text_sig,
        "calldata": cd,
        "tx_hash": meta.get("tx_hash"),
        "block_number": meta.get("block_number"),
        "to": meta.get("to"),
        "from": meta.get("from"),
        "file": rel,
        "_idx": idx,
        "_path": path,
    }


def _collapse_dedupe_groups(groups: dict[str, list[dict]]) -> tuple[list[dict], int]:
    kept: list[dict] = []
    removed = 0
    for items in groups.values():
        items.sort(key=lambda e: (e["tx_hash"] is None, e["_idx"]))
        for extra in items[1:]:
            extra["_path"].unlink(missing_ok=True)
            removed += 1
        win = items[0]
        del win["_idx"]
        del win["_path"]
        kept.append(win)
    kept.sort(key=lambda r: r["file"])
    return kept, removed


def dedupe_corpus(out: Path, selectors: dict[str, str]) -> tuple[list[dict], int]:
    """Keep one .calldata file per selector; delete the rest. Rebuild manifest."""
    calldata_dir = out / "calldata"
    by_file = _load_manifest_by_file(out / "manifest.json")

    groups: dict[str, list[dict]] = {}
    for path in sorted(calldata_dir.glob("*.calldata")):
        entry = _dedupe_entry_from_file(path, out, by_file, selectors)
        if entry is not None:
            groups.setdefault(entry["selector"], []).append(entry)

    return _collapse_dedupe_groups(groups)


def register_selector_hit(
    hit: dict,
    found: dict[str, dict],
    target: int,
    selectors: dict[str, str],
    block_number: int,
    on_hit,
) -> bool:
    """Store one hit per selector. Returns True when the scan target is satisfied."""
    sel = hit["selector"]
    if sel in found:
        return len(found) >= target
    found[sel] = hit
    if on_hit:
        on_hit(hit, len(found))
    print(f"  [{len(found):3d}/{target}] {sel}  block={block_number}  {selectors[sel][:72]}")
    return len(found) >= target


def scan_block_for_hits(
    block_number: int,
    found: dict[str, dict],
    target: int,
    selector_set: set[str],
    selectors: dict[str, str],
    on_hit,
) -> tuple[bool, bool]:
    """Scan one block. Returns (block_fetched, target_reached)."""
    block = fetch_block(block_number)
    if block is None:
        return False, len(found) >= target
    for tx in block.get("transactions") or []:
        hit = hit_from_transaction(tx, block_number, selector_set, selectors)
        if hit is not None and register_selector_hit(
            hit, found, target, selectors, block_number, on_hit
        ):
            return True, True
    return True, len(found) >= target


def scan_block_batch(
    start: int,
    batch: int,
    found: dict[str, dict],
    target: int,
    selector_set: set[str],
    selectors: dict[str, str],
    on_hit,
) -> tuple[int, int, bool]:
    """Scan a descending run of blocks. Returns (next_start, blocks_scanned, target_reached)."""
    end = max(0, start - batch + 1)
    scanned = 0
    for block_number in range(start, end - 1, -1):
        fetched, done = scan_block_for_hits(
            block_number, found, target, selector_set, selectors, on_hit
        )
        if fetched:
            scanned += 1
        if done:
            return end - 1, scanned, True
    return end - 1, scanned, False


def log_scan_progress(blocks_scanned: int, found_count: int, started_at: float) -> None:
    if blocks_scanned % 400 != 0:
        return
    elapsed = time.time() - started_at
    print(f"  … {blocks_scanned} blocks, {found_count} hits, {elapsed:.0f}s")


def scan_blocks(
    selectors: dict[str, str],
    target: int,
    *,
    max_blocks: int = 120_000,
    batch: int = 32,
    start_block: int | None = None,
    already_found: dict[str, dict] | None = None,
    on_hit=None,
    state_path: Path | None = None,
) -> list[dict]:
    latest = int(rpc("eth_blockNumber", []), 16)
    start = resolve_scan_start(start_block, state_path, latest)
    found: dict[str, dict] = dict(already_found or {})
    remaining = set(selectors.keys()) - set(found.keys())
    selector_set = remaining if remaining else set(selectors.keys())
    blocks_scanned = 0
    t0 = time.time()

    print(
        f"Scanning up to {max_blocks} blocks from {start} "
        f"({len(found)} already, need {target - len(found)} more)…"
    )

    while blocks_scanned < max_blocks and len(found) < target:
        start, batch_scanned, done = scan_block_batch(
            start, batch, found, target, selector_set, selectors, on_hit
        )
        blocks_scanned += batch_scanned
        persist_scan_state(state_path, start, len(found))
        log_scan_progress(blocks_scanned, len(found), t0)
        time.sleep(0.08)
        if done:
            break

    print(f"Done: {len(found)} hits in {blocks_scanned} blocks ({time.time()-t0:.0f}s)")
    return list(found.values())


def safe_filename(text_sig: str, selector: str, idx: int) -> str:
    name = text_sig.split("(")[0]
    name = re.sub(r"[^A-Za-z0-9_]+", "_", name)[:40].strip("_") or "fn"
    return f"{idx:03d}_{selector[2:10]}_{name}.calldata"


def _build_arg_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--target", type=int, default=100, help="number of calldata samples")
    ap.add_argument(
        "--out",
        type=Path,
        default=Path("scratch/tuple-calldata-corpus"),
        help="output directory",
    )
    ap.add_argument("--max-blocks", type=int, default=500_000)
    ap.add_argument(
        "--refresh-signatures",
        action="store_true",
        help="re-fetch tuple signatures from 4byte (ignore signatures.json)",
    )
    ap.add_argument(
        "--start-block",
        type=int,
        default=None,
        help="block to start scanning from (default: chain tip, or resume state)",
    )
    ap.add_argument(
        "--no-dedupe",
        action="store_true",
        help="skip one-per-selector dedupe of existing calldata files",
    )
    return ap


def _load_selectors(out: Path, refresh: bool) -> dict[str, str]:
    sig_path = out / "signatures.json"
    if refresh or not sig_path.exists():
        print("Fetching tuple signatures from 4byte.directory…")
        selectors = fetch_tuple_signatures(max_sigs=2500)
        print(f"Collected {len(selectors)} tuple-like signatures")
        sig_path.write_text(
            json.dumps(
                [{"selector": k, "text_signature": v} for k, v in sorted(selectors.items())],
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        return selectors
    selectors = {
        row["selector"]: row["text_signature"]
        for row in json.loads(sig_path.read_text(encoding="utf-8"))
    }
    print(f"Loaded {len(selectors)} tuple signatures from {sig_path}")
    return selectors


def _load_or_dedupe_manifest(
    out: Path, selectors: dict[str, str], no_dedupe: bool
) -> list[dict]:
    manifest_path = out / "manifest.json"
    if not no_dedupe:
        manifest, removed = dedupe_corpus(out, selectors)
        if removed:
            print(f"Deduped corpus: removed {removed} duplicate selector samples")
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(f"Unique selectors on disk: {len(manifest)}")
        return manifest
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        print(f"Loaded manifest with {len(manifest)} samples (--no-dedupe)")
        return manifest
    return []


def _next_calldata_index(calldata_dir: Path) -> int:
    next_idx = 1
    for path in calldata_dir.glob("[0-9]*_*.calldata"):
        m = re.match(r"^(\d+)_", path.name)
        if m:
            next_idx = max(next_idx, int(m.group(1)) + 1)
    return next_idx


def _resume_block(start_block: int | None, manifest: list[dict]) -> int | None:
    if start_block is not None:
        return start_block
    blocks = [r["block_number"] for r in manifest if r.get("block_number") is not None]
    return min(blocks) - 1 if blocks else None


def _make_persist_hit(
    calldata_dir: Path,
    out: Path,
    manifest_path: Path,
    manifest: list[dict],
    start_idx: int,
):
    next_idx = start_idx

    def persist_hit(hit: dict, count: int) -> None:
        nonlocal next_idx
        calldata_dir.mkdir(parents=True, exist_ok=True)
        fname = safe_filename(hit["text_signature"], hit["selector"], next_idx)
        path = calldata_dir / fname
        path.write_text(format_etherscan(hit["text_signature"], hit["calldata"]), encoding="utf-8")
        manifest.append({**hit, "file": str(path.relative_to(out))})
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        next_idx += 1

    return persist_hit


def _scan_if_needed(
    args: argparse.Namespace,
    out: Path,
    selectors: dict[str, str],
    manifest: list[dict],
    calldata_dir: Path,
) -> list[dict]:
    manifest_path = out / "manifest.json"
    if len(manifest) >= args.target:
        return manifest
    already = {row["selector"]: row for row in manifest}
    persist_hit = _make_persist_hit(
        calldata_dir, out, manifest_path, manifest, _next_calldata_index(calldata_dir)
    )
    scan_blocks(
        selectors,
        args.target,
        max_blocks=args.max_blocks,
        start_block=_resume_block(args.start_block, manifest),
        already_found=already,
        on_hit=persist_hit,
        state_path=out / "scan_state.json",
    )
    if manifest_path.exists():
        return json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest


def _final_dedupe(
    out: Path, selectors: dict[str, str], no_dedupe: bool, manifest: list[dict]
) -> list[dict]:
    if no_dedupe:
        return manifest
    manifest_path = out / "manifest.json"
    manifest, removed = dedupe_corpus(out, selectors)
    if removed:
        print(f"Final dedupe: removed {removed} orphan/duplicate files")
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def _write_corpus_readme(
    out: Path, manifest: list[dict], selectors: dict[str, str], target: int
) -> None:
    readme = f"""# Tuple calldata evaluation corpus (local)

Generated by `scratch/fetch_tuple_calldata_corpus.py`.

- **Samples:** {len(manifest)} / target {target} (one calldata per selector)
- **Signatures catalog:** `signatures.json` ({len(selectors)} tuple-like entries from 4byte)
- **Calldata files:** `calldata/*.calldata` (Etherscan word layout + Function/MethodID header)
- **Index:** `manifest.json` (selector, signature, tx hash, block, paths)

## Run decode on all samples

```bash
./scratch/run_decode_corpus.sh
python3 scratch/check_decode_structure.py
```

`run_decode_corpus.sh` uses `decode --ignore-skeleton` so the embedded `Function:` line does not
shortcut decode. `check_decode_structure.py` runs `TupleCorpusLocalEvaluationTest`, which
compares heuristic decode to manifest `text_signature` (4byte ground truth at fetch time only).

On external volumes macOS may create `._*` sidecar files; the batch script skips them.
Remove with: `dot_clean -m scratch/tuple-calldata-corpus`.

Regenerate:

```bash
python3 scratch/fetch_tuple_calldata_corpus.py --target 200
```
"""
    (out / "README.md").write_text(readme, encoding="utf-8")


def main() -> None:
    args = _build_arg_parser().parse_args()
    out: Path = args.out.resolve()
    calldata_dir = out / "calldata"
    calldata_dir.mkdir(parents=True, exist_ok=True)

    selectors = _load_selectors(out, args.refresh_signatures)
    manifest = _load_or_dedupe_manifest(out, selectors, args.no_dedupe)
    manifest = _scan_if_needed(args, out, selectors, manifest, calldata_dir)
    manifest = _final_dedupe(out, selectors, args.no_dedupe, manifest)
    _write_corpus_readme(out, manifest, selectors, args.target)
    print(f"\nWrote {len(manifest)} samples under {out}/")


if __name__ == "__main__":
    main()
