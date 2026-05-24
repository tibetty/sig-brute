# sig-brute public API

**Status:** Draft for Maven Central publication (v1.x)  
**Coordinates:** `me.tibetty:sig-brute:1.x`  
**Java:** 17+

> **Repository note:** [JFrog JCenter](https://jfrog.com/blog/jcenter-sunset/) was shut down in 2021 and is read-only. New releases publish to **[Maven Central](https://central.sonatype.com/)** using the same `groupId` / `artifactId` / `version` coordinates Gradle and Maven consumers already expect.

## Purpose

sig-brute is primarily a CLI, but three capabilities are useful as a **library**:

1. **Decode** — turn Etherscan calldata (or raw hex) into a structural argument tree and draft YAML.
2. **Search** — brute-force Keccak-256 selectors from a `SearchConfig`.
3. **Primitives** — Keccak-256, hex helpers, wildcard type expansion, and Cartesian iteration for custom tooling.

Everything else is implementation detail and may change in minor releases without notice.

## Stability tiers

| Tier | Meaning | Packages / types |
|------|---------|------------------|
| **Stable** | Semver-guaranteed for 1.x; breaking changes only in 2.0 | Listed under [Stable API](#stable-api) |
| **CLI** | Shipped in the same JAR; not for programmatic embedding | `Main`, `DecodeMain` |
| **Internal** | Not exported; no compatibility promise | `decode.infer.*`, package-private helpers |

### Versioning policy (1.x)

- **Patch** (1.0.x): bug fixes, performance; no API or YAML changes.
- **Minor** (1.x.0): new wildcard patterns, new optional YAML keys, new methods on stable types; existing callers keep compiling.
- **Major** (2.0.0): removed exports, changed record components, changed YAML required keys, changed expansion sets for existing patterns.

The **YAML config schema** (`config-schema.yaml`) is part of the stable contract for file-based workflows.

---

## Stable API

### `me.tibetty.sigbrute.model` — search configuration

Immutable value types describing a brute-force job. Build these in code or via `YamlConfigParser`.

| Type | Role |
|------|------|
| `SearchConfig` | Selector (4 bytes), method name candidates, argument specs, parallelism, `findFirst`, sharding |
| `ArgSpec` | Sealed root: `LeafArgSpec` or `TupleArgSpec` |
| `LeafArgSpec` | List of type patterns for one argument position (e.g. `address`, `uint64+`) |
| `TupleArgSpec` | Nested fields + optional array suffix (`""`, `[]`, `[N]`) |

`SearchConfig` exposes a 5-arg convenience constructor (no sharding); sharding fields default to `shardIndex=0`, `totalShards=1`.

`ArgSpec.expand()` returns a `Dimension<String>` of concrete ABI types for that position (applies `TypeExpander` + `TypeRanker` ordering for leaves).

---

### `me.tibetty.sigbrute.parser` — YAML → model

| Type | Role |
|------|------|
| `YamlConfigParser` | `parse(InputStream)` → `SearchConfig` |

Throws `IllegalArgumentException` on invalid YAML or schema violations. Uses SnakeYAML `SafeConstructor` (no arbitrary object deserialization).

**Transitive dependency:** `org.yaml:snakeyaml` is required at runtime if you use this parser.

---

### `me.tibetty.sigbrute.search` — selector brute-force

| Type | Role |
|------|------|
| `SearchEngine` | `search()` → matching canonical signatures (`name(types…)`) |
| `SearchException` | Unchecked failure (interrupted pool, execution error) |

Constructor: `SearchEngine(SearchConfig config, PrintStream out)` — progress and summary go to `out` (use `PrintStream.nullOutputStream()` for silent runs).

Does **not** call 4byte.directory or any network API.

---

### `me.tibetty.sigbrute.decode` — calldata → structure

| Type | Role |
|------|------|
| `CalldataInput` | Parsed selector, body, optional Etherscan method name / top-level type skeleton |
| `CalldataInput.parse(String)` | Etherscan block or raw `0x…` hex |
| `AbiDecoder` | `decodeArgs(byte[] body, List<String> topLevelHint)` → `List<DecodedArg>` |
| `DecodedArg` | Sealed tree: `Leaf`, `PrimArray`, `Tuple` (mirrors YAML shapes) |

`topLevelHint` may be `null` or empty for fully heuristic decode. When Etherscan provides `Function: foo(uint256, tuple, tuple[])`, pass the parsed type list to remove top-level ambiguity.

**Heuristic limits** (documented, not API bugs): no `int*` / `fixed*` inference; `bool` only for 0/1 slots; tuple innards are guesses.

---

### `me.tibetty.sigbrute.decode.emit` — structure → YAML / prototype

| Type | Role |
|------|------|
| `ConfigEmitter.emit(…)` | Full sig-brute YAML string |
| `PrototypeRenderer.render(…)` | One-line `name(type,…)` summary (first candidate per leaf) |

---

### `me.tibetty.sigbrute.expander` — type patterns

| Type | Role |
|------|------|
| `TypeExpander.expand(String pattern)` | Wildcard → concrete ABI type list (`uint*`, `uint64+`, `bytes*`, `fixed*x18`, …) |
| `TypeRanker.rank` / `rankOf` / `comparator` | Corpus frequency ordering (used by search; safe for custom tools) |

**Stability:** Existing patterns never shrink their expansion set in 1.x. New patterns may be added.

---

### `me.tibetty.sigbrute.util` — shared primitives

| Type | Role |
|------|------|
| `HexUtil` | `fromHex` / `toHex` (optional `0x` prefix) |
| `Keccak256Util` | `hash`, `selectorMatches(sig, selector4)` |
| `Dimension<T>` | Lazy finite domain for one Cartesian axis |
| `Dimension.ofList(List)` | List-backed dimension |
| `CartesianStream<T>` | Mixed-radix iterator, `snapshot()`, `shard()`, `totalSize`, `Spliterator` |
| `CartesianProduct` | `lazyStream`, `decode(index, dims)` helpers |

These are stable for integrators building custom search orchestration (e.g. external checkpoint stores).

---

## CLI-only (not library API)

| Type | Role |
|------|------|
| `me.tibetty.sigbrute.Main` | `main`, package-private `run(args, out, err)` |
| `me.tibetty.sigbrute.decode.DecodeMain` | `decode` subcommand; file/stdin glue |

Prefer composing **`YamlConfigParser` + `SearchEngine`** and **`CalldataInput` + `AbiDecoder` + `ConfigEmitter`** in your own application entry points.

The **fat JAR** (`shadowJar`, classifier none) is the supported CLI distribution. The **Maven Central JAR** is the library artifact (no relocated dependencies except as declared in the POM).

---

## Internal (not exported)

Package `me.tibetty.sigbrute.decode.infer` and all types therein:

- `TypeInferrer`, `SlotInferrer`, `AddressInferrer`, `LeftAlignedInferrer`, `RightAlignedUintInferrer`, `ZeroSlotInferrer`, `ValueOneInferrer`, `SlotMeta`

Enforced by `module-info.java` (JPMS). Do not rely on reflection into these packages.

---

## Typical library flows

### Decode → YAML

```java
var input = CalldataInput.parse(etherscanText);
var args = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
var yaml = ConfigEmitter.emit(input.selector(), input.methodName(), args);
```

### YAML → signatures

```java
SearchConfig config;
try (var in = Files.newInputStream(path)) {
    config = new YamlConfigParser().parse(in);
}
var matches = new SearchEngine(config, System.out).search();
```

### Custom search (no `SearchEngine` progress UI)

```java
var dims = new ArrayList<Dimension<String>>();
dims.add(Dimension.ofList(config.methodNames()));
config.args().forEach(a -> dims.add(a.expand()));
var cs = CartesianStream.shard(dims, shardIndex, totalShards);
// ... hash combos with Keccak256Util.selectorMatches ...
```

---

## Maven artifacts (planned)

| Artifact | Classifier | Contents |
|----------|------------|----------|
| `me.tibetty:sig-brute` | _(none)_ | Library JAR + module descriptor |
| `me.tibetty:sig-brute` | `sources` | Sources JAR |
| `me.tibetty:sig-brute` | `javadoc` | Javadoc JAR |
| `me.tibetty:sig-brute` | _(shadow / optional)_ | CLI fat JAR with Main-Class (GitHub Releases, not required for library users) |

**Dependency scope for consumers:**

```gradle
implementation 'me.tibetty:sig-brute:1.0.0'
```

SnakeYAML is pulled transitively when using `YamlConfigParser`.

---

## Publication checklist (maintainers)

1. Register `me.tibetty` namespace on [Central Portal](https://central.sonatype.com/) and create a user token.
2. Upload your GPG public key to the namespace (required for signed artifacts).
3. Add GitHub Actions secrets on `tibetty/sig-brute`:
   - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — Portal user token
   - `SIGNING_KEY` — armored GPG private key
   - `SIGNING_PASSWORD` — key passphrase
4. Tag and push: `git tag v1.0.0 && git push origin v1.0.0` — triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml) (upload to OSSRH Staging API, then promote to namespace `me.tibetty` with automatic release).
5. **Upload the release GPG public key** to the `me.tibetty` namespace on [Central Portal](https://central.sonatype.com/publishing/namespaces). The private key in `SIGNING_KEY` must match; without the public key on the namespace, validation fails with *Could not find a public key by the key fingerprint*.
6. Confirm the deployment at [central.sonatype.com/publishing](https://central.sonatype.com/publishing).
7. Attach the CLI fat JAR (`./gradlew shadowJar` → `build/libs/sig-brute-*-all.jar`) to the GitHub Release if desired.

---

## Related documents

- [`config-schema.yaml`](../config-schema.yaml) — YAML keys and examples
- [`sig_brute_design.md`](sig_brute_design.md) — implementation design and stability notes
- [`README.md`](../README.md) — CLI installation and workflow
