#!/usr/bin/env bash
# Cross-host hash-parity check.
#
# Builds the same canonical Dacite value (dacite.examples.parity) on every
# available host and asserts the 64-char root hash is byte-for-byte identical.
# The portable core guarantees this across JVM, babashka, nbb, and any future
# language port.
#
# Usage: bin/hash-parity.sh
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Resolve babashka: prefer a locally installed copy, fall back to PATH.
BB=""
if [[ -x "$ROOT/.bin/bb" ]]; then
  BB="$ROOT/.bin/bb"
elif command -v bb >/dev/null 2>&1; then
  BB="bb"
fi

echo "Computing canonical root hash on each host..."

jvm_hex="$(clojure -Sdeps '{:paths ["impl/clojure/src"]}' \
  -M -e "(require (quote dacite.examples.parity))(println (dacite.examples.parity/canonical-hex))" \
  2>/dev/null | tail -1)"
echo "  JVM      : $jvm_hex"

hexes=("$jvm_hex")

if [[ -n "$BB" ]]; then
  bb_hex="$("$BB" parity 2>/dev/null | tail -1)"
  echo "  babashka : $bb_hex"
  hexes+=("$bb_hex")
else
  echo "  babashka : (not installed, skipped)"
fi

if command -v npx >/dev/null 2>&1 && [[ -f "$ROOT/nbb.edn" ]]; then
  nbb_hex="$(npx nbb -m dacite.examples.parity 2>/dev/null | tail -1)"
  echo "  nbb      : $nbb_hex"
  hexes+=("$nbb_hex")
else
  echo "  nbb      : (not available, skipped)"
fi

# Assert all collected hashes are identical.
for h in "${hexes[@]}"; do
  if [[ "$h" != "$jvm_hex" ]]; then
    echo "MISMATCH: hashes differ across hosts" >&2
    exit 1
  fi
done

echo "OK: identical root hash on all $(( ${#hexes[@]} )) host(s): $jvm_hex"
