# 🪨 Dacite

> Data citing with fused hashing.

Dacite is a system for **distributed immutable data structures** with content-addressed nodes.

## Features

- **Structural sharing** — unchanged data shares identity across versions
- **Efficient sync** — fetch only what changed
- **Lazy access** — pull only what you use
- **Perfect caching** — immutable = cacheable forever

## Status

🚧 **Early design phase** — spec in progress, reference implementation underway.

## Documentation

**The Dacite Book** (primary documentation):

- [Introduction & Philosophy](docs/book/00-introduction.md) *(coming soon)*
- [01 — Hash Fusion](docs/book/01-hash-fusion/chapter.md)
- [02 — Values](docs/book/02-values/chapter.md)
- [03 — Stores](docs/book/03-stores/chapter.md)
- [04 — Authorization](docs/book/04-authorization/chapter.md)
- [05 — Sharing](docs/book/05-sharing/chapter.md)

The book is the authoritative source. Older files remain in `docs/spec/` and `docs/development-dialogue.md` for historical reference.

## Implementations

| Language | Status | Location |
|----------|--------|----------|
| Clojure | 🔨 Reference implementation | [impl/clojure](https://github.com/jclaggett/dacite/tree/main/impl/clojure) |
| Node.js | 📋 Planned | — |
| C++ | 📋 Planned | — |

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
