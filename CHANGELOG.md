# Changelog

All notable changes to this project are documented in this file.

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
