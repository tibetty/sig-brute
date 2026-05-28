# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

## [1.3.0] — 2026-05-28

### Added

- Shallow skeleton: `ShallowSkeletonHints` helpers (`layoutHints`, `dynamicDecodeType`, `opaqueTupleFieldHints`, `singletonFixedArrayField`, `widenOpaqueRegionForEmit`)
- `SkeletonLayout.headBoundForSlot` — per-argument head bound so inflated global head scans do not steal dynamic tuple slots
- `GreedyBodyDecoder.decodeBestHeadTailTuple` — scored head/tail parse for opaque tuple interiors without inline field hints
- `scratch/README.md` — optional local tuple corpus and evaluation scripts

### Changed

- `decode --shallow-skeleton`: inline `Function:` types drive layout and tuple/array **structure** inside opaque top-level bodies; YAML leaf candidates stay calldata heuristics (`uint*`, `[bytes, string]`, …)
- Shallow skeleton structural match on the local tuple corpus (294 fixtures): **100%** for both `greedy` and `heuristic_search` (see `TupleCorpusShallowSkeletonEvaluationTest`)
- Fixed `(bytes32[N])`-style singleton wrappers decode as one `T[N]` field, not N tuple leaves
- `decode/ARCHITECTURE.md` and README document shallow-skeleton behavior

## [1.2.0] — 2026-05-27

### Added

- `decode --shallow-skeleton`: top-level inline tuples abstracted to `tuple` / `tuple[]` for RE-friendly skeleton hints
- `ShallowSkeletonHints`, `CalldataInput.withShallowSkeleton()`, and opaque top-level YAML emit (`ConfigEmitter`, `PrototypeRenderer`)
- Opaque `tuple[]` decode path in `SkeletonArrayDecoder` (offset table, static rows, concatenated fallback)
- Greedy body: multi-candidate head sizes for offset-indexed tuple elements; improved static opaque tuple head span in `SkeletonLayout`
- Corpus tests: `TupleCorpusShallowSkeletonEvaluationTest`, `ShallowSkeletonDecodeTest`, `SkeletonLayoutOpaqueTupleTest`; `scratch/check_decode_structure.py` shallow/benchmark modes

### Changed

- `DecodeMain` / `AbiDecoder`: empty lists replace `null` skeleton hints
- README documents `--shallow-skeleton` and decode/search settings for tuple-heavy signatures

## [1.1.0] — 2026-05-27

### Added

- Heuristic search: wider YAML type lists (`bytes` + `string` companions), near-tie dynamic candidate merge, alternate-parse warnings, deterministic branch picking
- `decode --wide` and commented alternate prototypes in YAML (`DecodeResult.alternateStructures`)
- README: [Choosing decode and search settings](README.md#choosing-decode-and-search-settings) (strategy/flag/workflow guide)
- `CalldataInput.withoutSkeleton()` and `decode --ignore-skeleton` for corpus verification without `Function:` hints
- `DecodeResult` — decoded args, non-fatal warnings, and strategy from `AbiDecoder.decodeResult(…)`
- `DecodeStrategy` enum (`GREEDY`, `HEURISTIC_SEARCH`) with explicit `DecodeContext` (replaces `ThreadLocal` session)
- `decode.layout` — shared `OffsetTable` and `DynamicHeadSlots` for skeleton and body decoders
- Search CLI: automatic Sourcify → 4byte.directory signature preflight (`--skip-lookup` for offline)
- Decode CLI: `--strategy`, `--validate` (YAML round-trip via `DecodeConfigValidator`)
