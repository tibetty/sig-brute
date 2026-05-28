# Maintainer scripts (optional)

These tools are **not** required to build or run sig-brute. They support local evaluation of
tuple-heavy calldata decoding.

| Script | Purpose |
| ------ | ------- |
| `fetch_tuple_calldata_corpus.py` | Crawl tuple signatures from 4byte + chain RPC into `tuple-calldata-corpus/` |
| `check_decode_structure.py` | Compare decode structure to manifest signatures (`decoded`, `shallow`, `full`, `benchmark`) |
| `reindex_corpus.py` | Repair `manifest.json` after manual corpus edits |
| `export_corpus_fixtures.py` | Copy selected fixtures into test resources |

The generated corpus directory (`tuple-calldata-corpus/`) is gitignored. To run structure
evaluation tests locally:

```bash
python3 scratch/fetch_tuple_calldata_corpus.py --target 100
./gradlew test --tests 'me.tibetty.sigbrute.decode.strategy.TupleCorpusShallowSkeletonEvaluationTest'
python3 scratch/check_decode_structure.py --mode shallow
# Reports greedy / heuristic_search structural match counts (294 fixtures when corpus is complete)
```
