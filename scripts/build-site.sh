#!/usr/bin/env bash
# Build dacite.io static site: landing page + mdBook HTML.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BOOK_DIR="$REPO_ROOT/docs/book"
SCRIPT_BIN="$REPO_ROOT/scripts/bin"

# scripts/bin is a local download and may be another OS. Use it only when
# those binaries run; otherwise mdbook on PATH (for example ~/.local/bin).
if [ -x "$SCRIPT_BIN/mdbook" ] && [ -x "$SCRIPT_BIN/mdbook-mermaid" ] \
  && "$SCRIPT_BIN/mdbook" --version >/dev/null 2>&1 \
  && "$SCRIPT_BIN/mdbook-mermaid" --version >/dev/null 2>&1; then
  export PATH="$SCRIPT_BIN:$PATH"
fi

cd "$BOOK_DIR"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "❌ Required command not found: $1" >&2
    exit 1
  fi
}

require_cmd mdbook
require_cmd mdbook-mermaid

echo "Installing mdbook-mermaid assets..."
mdbook-mermaid install .

echo "Building book..."
mdbook build

echo "Assembling site..."
mkdir -p "$REPO_ROOT/target/site"
cp "$REPO_ROOT/docs/site/index.html" "$REPO_ROOT/target/site/"
cp "$REPO_ROOT/docs/site/style.css" "$REPO_ROOT/target/site/"
cp "$REPO_ROOT/CNAME" "$REPO_ROOT/target/site/"

echo "✅ Site built at target/site/ (book at target/site/book/)"
