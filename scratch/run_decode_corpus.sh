#!/usr/bin/env bash
# Decode every sample in the tuple calldata corpus and report failures.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORPUS="$ROOT/scratch/tuple-calldata-corpus/calldata"
JAR_GLOB="$ROOT/build/libs/sig-brute-*-all.jar"

if [[ ! -d "$CORPUS" ]]; then
    echo "Corpus not found. Run: python3 scratch/fetch_tuple_calldata_corpus.py --target 100"
    exit 1
fi

if ! compgen -G "$JAR_GLOB" >/dev/null; then
    echo "Building fat JAR…"
    (cd "$ROOT" && ./gradlew shadowJar --quiet)
fi
JAR=""
if compgen -G "$JAR_GLOB" >/dev/null; then
  JAR="$(ls -1 "$JAR_GLOB" | head -1)"
fi
if [[ -z "$JAR" && -f "$ROOT/build/libs/sig-brute-all.jar" ]]; then
  JAR="$ROOT/build/libs/sig-brute-all.jar"
fi
if [[ ! -f "$JAR" ]]; then
  echo "Fat JAR not found under build/libs/"
  exit 1
fi

ok=0
fail=0
total_files=0
while IFS= read -r -d '' f; do
    total_files=$((total_files + 1))
    file_ok=true
    for strategy in greedy heuristic_search; do
        if ! java -jar "$JAR" decode --ignore-skeleton --strategy "$strategy" "$f" \
            >/dev/null 2>&1; then
            echo "FAIL [$strategy]: $f"
            file_ok=false
        fi
    done
    if $file_ok; then
        ok=$((ok + 1))
    else
        fail=$((fail + 1))
    fi
done < <(find "$CORPUS" -name '*.calldata' ! -name '._*' -print0 | sort -z)

echo "Decode corpus (greedy + heuristic_search, no skeleton): $ok ok, $fail failed ($total_files files)"
