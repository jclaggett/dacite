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

- [Specification](docs/spec/SPEC.md) — the core protocol and data model
- [Development Dialogue](docs/development-dialogue.md) — journal of the design and development process

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
