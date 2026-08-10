# Wire format v1 fixtures

Language-agnostic golden tests for [docs/spec/wire-v1.md](../../docs/spec/wire-v1.md).

Every case is a **complete chunk message** (`DAC1` …), not a bare store entry.

## Layout

```text
fixtures/wire-v1/
  README.md
  manifest.json
  cases/<name>/
    description.json   # human-readable intent
    message.hex        # full ChunkMessage, lowercase hex, no whitespace
    hash.hex           # optional: primary item hash (64 hex chars)
```

## Port checklist

1. Decode `message.hex` as binary.
2. Apply items to an empty content store (node → put; literal → materialize + put).
3. If `hash.hex` is present, assert that hash exists and matches the item claim.
4. Re-encode the same logical chunk with **canonical** rules → bytes must equal
   `message.hex`.

## Generating new cases

Regenerate from the shipped codec:

```bash
cd impl/clojure && clojure -M:dev -m gen-wire-fixtures
```

Goldens are **only** produced by `dacite.wire.binary` (canonical re-encode must
match). Do not hand-edit `message.hex` without re-running the generator.

## Status

Golden suite for wire-v1 (see `manifest.json`, **73** cases). Covers:

- **All public scalars** as **node and literal**: null, bool, char, i8/i16/i32/i64,
  u8/u16/u32/u64/u256, f32/f64, negative
- **All public collections** as **node and literal**: string, blob, vector, map, set
  (empty + representative non-empty)
- **FT / HAMT** store nodes + intermediate pack literals (empty, digit/deep/node,
  hamt empty/entry/bitmap)
- Mixed encodings; multi-chunk 3000-char string pack series

Ports should pass every case under `cases/`.

## Categories

| Prefix | Meaning |
|--------|---------|
| `chunk-literal-*` | Item(s) with `enc=literal` |
| `chunk-node-*` | Item(s) with `enc=node` (store entry payload) |
| `chunk-mixed-*` | Both encodings in one message |
| `chunk-string-3000-part-*` | Soft-budget multi-chunk series (apply in order 0,1,2…) |
