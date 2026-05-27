# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

## [1.2.0] — 2026-05-27

### Added

- `decode --shallow-skeleton`: top-level inline tuples abstracted to `tuple` / `tuple[]` for RE-friendly skeleton hints
- `ShallowSkeletonHints`, `CalldataInput.withShallowSkeleton()`, and `inlineTopLevelTypes` for per-parameter decode from the full `Function:` line
- Opaque `tuple[]` decode path in `SkeletonArrayDecoder` (offset table, static rows, concatenated fallback)
- Greedy body: multi-candidate head sizes for offset-indexed tuple elements; improved static opaque tuple head span in `SkeletonLayout`
- Corpus tests: `TupleCorpusShallowSkeletonEvaluationTest`, `ShallowSkeletonDecodeTest`, `SkeletonLayoutOpaqueTupleTest`; `scratch/check_decode_structure.py` shallow/benchmark modes

### Changed

- `DecodeMain` / `AbiDecoder` accept optional inline top-level types alongside shallow hints; empty lists replace `null` skeleton hints
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
- `GeneralizedTypeInferrer` for heuristic-search floor patterns (`uintN+`, `int*`, …)
- Decode package split: `abi/`, `skeleton/`, `strategy/`, `infer/`, `emit/`; tests mirror production layout
- `CorpusFixtureLocator` and local-corpus structure tests; greedy nested dynamic-array regression test

### Changed

- Skeleton and body decoders share layout kernel instead of reaching into `GreedyBodyDecoder`
- `ConfigEmitter` emits `# WARNING:` lines and strategy header; warnings also on stderr in `decode`
- JPMS exports `decode.strategy` and `decode.emit`; `decode.infer`, `decode.layout`, `lookup` remain internal
- Greedy body decoder: nested dynamic array/tuple layout and inline tuple head handling

## [1.0.0] — 2026-05-23

### Added

- Brute-force search for Ethereum function signatures from a 4-byte ABI selector
- `decode` subcommand: Etherscan calldata dump or raw hex → draft sig-brute YAML
- Heuristic ABI decoder with optional Etherscan `Function:` skeleton
- Type wildcards (`uint*`, `bytes*`, tuples, arrays) with Cartesian-product search
- Multi-machine sharding (`shard_index` / `total_shards`)
- `--find-first` CLI flag and `find_first` YAML option
- TypeRanker: search ordering by public sigbank type frequencies
- Inline Keccak-256 (no Bouncy Castle dependency)
- Round-trip property tests (encode → decode → search)

### Open source

- Apache 2.0 license, CONTRIBUTING, CI workflow, third-party NOTICE
