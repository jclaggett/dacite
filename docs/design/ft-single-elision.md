# Elide `ft/single` (implicit leaf singles)

## Status

**PR1 in progress:** dual-read of legacy `ft/single` and bare value hashes.
Writers still emit `ft/single` until PR2.

## Goal

Stop persisting leaf adapter nodes. Digit/node children and 1-element tree
roots are **value hashes** (or structural `ft/node`s on the spine).

## Discrimination

| Kind under digit/node (or 1-elem root) | Type | Role |
|----------------------------------------|------|------|
| Leaf element | any non-`ft/*` | implicit single |
| Structural | `ft/empty`, `ft/digit`, `ft/node`, `ft/deep` | recurse |
| Legacy leaf adapter | `ft/single` | dual-read unwrap |

## Laws

1. Public collections always wrap spines (`vector` / `string` / `blob` nodes);
   never expose a bare `ft/deep` as a user value.
2. `ft/` (and `hamt/`) remain internal reserved prefixes.
3. Collection **value hashes** unchanged (leaf `elements_fuse` only).
4. `hamt/entry` is out of scope.

## Representation

| Elements | Root |
|----------|------|
| 0 | `ft/empty` |
| 1 | value hash (after PR2); today still `ft/single` |
| 2+ | `ft/deep` with digits of value hashes / `ft/node`s |

Leaf measure (synthesized when not stored):

```
{count 1, size-bytes (dacite-size entry), elements-fuse leaf-hash}
```

## PR stack

1. **PR1** — `measure-of` / `as-leaf-hash`; dual-read all ops; still write singles.
2. **PR2** — stop writing singles; golden hash fixtures.
3. **PR3** — pack intermediate FT alignment.
4. **PR4** — binary serial keep subtype 1 decode.
5. **PR5** — docs, law tests, bench.
6. **PR6** (optional) — hard-drop singles after migration.

## Dual-read rule

- If entry is `ft/single` → use `:value-hash` and stored measure.
- Else if type starts with `ft/` → structural (stored measure / children).
- Else → leaf (identity hash; synthesized measure).
