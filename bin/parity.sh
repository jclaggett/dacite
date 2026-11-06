#!/usr/bin/env bash
# Reveal, pack, and root-hash parity.
#
# Runs dacite.examples.parity (bin/hash-parity.sh) and the reveal/pack
# decision suite on the JVM and on nbb. Fails if a host fails or the
# decision digests differ. The decisions are also pinned by
# fixtures/parity/decisions.edn.
#
# Usage: bin/parity.sh
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

echo "== root hashes =="
bin/hash-parity.sh

echo "== reveal and pack decisions =="

if [[ -x "$ROOT/node_modules/.bin/nbb" ]]; then
  NBB=("$ROOT/node_modules/.bin/nbb")
elif command -v nbb >/dev/null 2>&1; then
  NBB=(nbb)
elif command -v npx >/dev/null 2>&1; then
  NBB=(npx nbb)
else
  echo "nbb is not available" >&2
  exit 1
fi

jvm_log="$(mktemp)"
nbb_log="$(mktemp)"
trap 'rm -f "$jvm_log" "$nbb_log"' EXIT

( cd "$ROOT/impl/clojure" && clojure -M:parity ) | tee "$jvm_log"
( cd "$ROOT" && "${NBB[@]}" -cp impl/clojure/src:impl/clojure/test -m dacite.parity.checks ) | tee "$nbb_log"

jvm_d="$(grep '^parity-digest: ' "$jvm_log" | tail -1 || true)"
nbb_d="$(grep '^parity-digest: ' "$nbb_log" | tail -1 || true)"
jvm_n="$(grep -c '^parity ok$' "$jvm_log" || true)"
nbb_n="$(grep -c '^parity ok$' "$nbb_log" || true)"

if [[ "$jvm_n" != "1" || "$nbb_n" != "1" ]]; then
  echo "MISMATCH: a host did not report parity ok" >&2
  exit 1
fi

if [[ -z "$jvm_d" || "$jvm_d" != "$nbb_d" ]]; then
  echo "MISMATCH: reveal/pack digests differ" >&2
  echo "  JVM $jvm_d" >&2
  echo "  nbb $nbb_d" >&2
  exit 1
fi

echo "OK: $jvm_d"
