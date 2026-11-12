#!/usr/bin/env bash
# Run each example demo once, as `npm run <demo>` does by default, but on a
# fresh store in its own temp directory, so a local run matches a cold CI run
# and leaves target/ alone. The log demo seeds 150 events, not its default
# 2000: seeding 2000 takes about 40-50 s on nbb (#63) and the demo does the
# same at either size. Fails on the first demo that exits nonzero.
set -euo pipefail
tmp=$(mktemp -d "${TMPDIR:-/tmp}/dacite-smoke-XXXXXX")
trap 'rm -rf "$tmp"' EXIT
nbb -m dacite.examples.todo "$tmp/todo"
nbb -m dacite.examples.config --path "$tmp/config"
nbb -m dacite.examples.notes --path "$tmp/notes"
nbb -m dacite.examples.event-log --path "$tmp/log" --n 150
nbb -m dacite.examples.sync --path "$tmp/sync"
nbb -m dacite.examples.library --path "$tmp/library"
