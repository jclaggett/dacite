# 🪨 Dacite

> Data citing with fused hashing.

Dacite is a system for **distributed immutable data structures** with content-addressed nodes.

## Features

- **Structural sharing** — unchanged data shares identity across versions
- **Efficient sync** — fetch only what changed
- **Lazy access** — pull only what you use
- **Perfect caching** — immutable = cacheable forever

## Status

**Reference implementation (Clojure)** — hash fusion, content stores, values, and rooted stores are implemented with 307+ tests. Remote store and service layers are in progress. See [docs/roadmap.md](docs/roadmap.md).

## Documentation

**The Dacite Book** (primary living documentation):

- [00 — Preface](docs/book/00-preface.md)
- [01 — Content Stores](docs/book/01-stores/chapter.md)
- [02 — Hash Fusion](docs/book/02-hash-fusion/chapter.md)
- [03 — Values](docs/book/03-values/chapter.md)
- [04 — Rooted Stores](docs/book/04-rooted-stores/chapter.md)

Historical chapters (authorization, sharing) are archived under [docs/book/archive/](docs/book/archive/).

**Development Dialogue** — chronological record of the design process:  
[docs/development-dialogue.md](docs/development-dialogue.md)

**Design docs:** [docs/design/](docs/design/)

## Implementations

| Language | Status | Location |
|----------|--------|----------|
| Clojure | Reference implementation | [impl/clojure](impl/clojure) |
| Node.js | Planned | — |
| C++ | Planned | — |

## Examples

From `impl/clojure`:

```bash
clojure -M:cards    # durable LMDB rooted-store card game
```

See [examples/](examples/) for `cards.clj` and `config.clj`.

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
