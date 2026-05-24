# sig-brute — Claude Code Project Guidance

## Development workflow

### TDD — tests first, always

For every new feature or bug fix:

1. **Write the failing test first.** Add or extend a test class under
   `src/test/java/` that asserts the desired behavior before touching any
   production code.
2. **Run the test** (`./gradlew test`) to confirm it fails for the right reason.
3. **Implement the minimum production code** needed to make the test pass.
4. **Refactor** with the green test as a safety net.

This applies to all layers: `TypeInferrer` slot-inference logic, `TypeExpander`
wildcard expansion, `AbiDecoder` head/tail parsing, `SearchEngine` matching, and
new subcommands.

New example configs added to `src/main/resources/examples/config/` (YAML search
configs) or calldata fixtures added to `src/main/resources/examples/calldata/`
must have a corresponding test in `SearchEngineComplexSignatureTest` (or a new
test class) that asserts the expected signature is recovered.

### 4byte.directory — check the API before brute-forcing

**Always call the 4byte.directory API first.** Brute-force search is expensive
(Keccak-256 over millions of candidates). If the signature is already indexed,
the API returns it in milliseconds.

API call pattern (try both; Sourcify has more data):

```bash
# Preferred — Sourcify unified endpoint (merged openchain.xyz + 4byte + verified contracts)
curl -s "https://api.4byte.sourcify.dev/v1/signatures/?hex_signature=0xaabbccdd" \
  | jq '.results[].text_signature'

# Fallback — original 4byte.directory
curl -s "https://www.4byte.directory/api/v1/signatures/?hex_signature=0xaabbccdd" \
  | jq '.results[].text_signature'
```

Only proceed to brute-force if both APIs return zero results **or** all returned
entries are structurally incompatible with the decoded calldata.

## TODO

- [ ] **Integrate signature API lookup as an automatic pre-flight step** before
  brute-force search. Query both endpoints in order:
  1. `https://api.4byte.sourcify.dev/v1/signatures/?hex_signature=<selector>`
     (Sourcify unified — merges openchain.xyz, 4byte, and verified-contract data; ~4.7 M signatures)
  2. `https://www.4byte.directory/api/v1/signatures/?hex_signature=<selector>`
     (original 4byte.directory; fallback)

  Verify each returned signature by hashing it and comparing the first 4 bytes
  to the target selector. Short-circuit with the match if one is found.
  Surface a `--skip-lookup` flag for offline / air-gapped use.

## Build and test

```bash
./gradlew test          # run full test suite (run this after every change)
./gradlew shadowJar     # build the fat JAR
```

## Code conventions

See [CONTRIBUTING.md](CONTRIBUTING.md) for full coding-style rules. Key points:

- **Java 17** — use `record`, `sealed`, pattern-matching `instanceof`, `switch` expressions.
- **4 spaces** indentation; continuation lines indent one extra level (not column-aligned).
- **Always use braces** on `if`/`else`/`for`/`while` bodies.
- **Minimal dependencies** — no new runtime deps without discussion.
