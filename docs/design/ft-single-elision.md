# Elide `ft/single` (implicit leaf singles)

## Status

**Done.** `ft/single` is fully removed: no write, no dual-read, no pack
materialize, no binary subtype encode. Subtype tag `1` is reserved and
rejected on deserialize.

## Goal

Stop persisting leaf adapter nodes. Digit/node children and 1-element tree
roots are **value hashes** (or structural `ft/node`s on the spine).

## Discrimination

| Kind under digit/node (or 1-elem root) | Type | Role |
|----------------------------------------|------|------|
| Leaf element | any non-`ft/*` | implicit single |
| Structural | `ft/empty`, `ft/digit`, `ft/node`, `ft/deep` | recurse |

## Laws

1. Public collections always wrap spines (`vector` / `string` / `blob` nodes);
   never expose a bare `ft/deep` as a user value.
2. `ft/` (and `hamt/`) remain internal reserved prefixes.
3. Collection **value hashes** unchanged (leaf `elements_fuse` only).
4. `ft-conj-*` rejects structural `ft/*` hashes as elements.
5. `hamt/entry` is out of scope (different role).

## Representation

| Elements | Root |
|----------|------|
| 0 | `ft/empty` |
| 1 | value hash |
| 2+ | `ft/deep` with digits of value hashes / `ft/node`s |

Leaf measure (synthesized):

```
{count 1, size-bytes (dacite-size entry), elements-fuse leaf-hash}
```

## Binary serial

Seq subtype tags: `0` empty, `1` reserved (removed single), `2` digit,
`3` node, `4` deep. Decoding tag `1` throws.

## Shipped as

- PR1: dual-read + `measure-of` / `as-leaf-hash`
- PR2: stop writing singles
- PR3–6: pack/serial hard-drop, docs, laws, density tests (no production DBs)
