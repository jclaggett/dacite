# 🪨 Dacite

> Data citing with fused hashing.

**Website:** [dacite.io](https://dacite.io) — overview and [The Dacite Book](https://dacite.io/book/) as navigable HTML.

Dacite lets applications work with **immutable Values** functionally while **Stores** hold and transmit data efficiently. Values give you structural sharing and lazy access; stores give you content-addressed persistence, caching, and sync.

## Features

- **Structural sharing** — unchanged data shares identity across versions
- **Efficient sync** — fetch only what changed
- **Lazy access** — pull only what you use
- **Perfect caching** — immutable = cacheable forever

## Status

**Reference implementation (Clojure)** — hash fusion, content stores, values, and rooted stores are implemented with 318+ tests. Remote store and service layers are in progress. See [docs/roadmap.md](docs/roadmap.md).

## Documentation

- **[The Dacite Book](https://dacite.io/book/)** — primary living documentation (also under [docs/book/](docs/book/))
- **[Development dialogue](docs/development-dialogue.md)** — design history
- **[Design docs](docs/design/)** — phase plans and service design
- **[Portable core & host compatibility](docs/design/portable-core.md)** — running on JVM/babashka/nbb and the porting contract

Build the site locally:

```bash
# requires mdbook + mdbook-mermaid (cargo install mdbook mdbook-mermaid)
bash scripts/build-site.sh
open target/site/index.html
```

GitHub Actions deploys `target/site/` to the `gh-pages` branch on push to `main`. Configure Pages to serve from that branch (root).

## Implementations

| Language | Status | Location |
|----------|--------|----------|
| Clojure (JVM) | Reference implementation | [impl/clojure](impl/clojure) |
| SCI (babashka + nbb) | Runs the portable core from the same `.cljc` source | [docs/design/portable-core.md](docs/design/portable-core.md) |
| Python | Planned | — |
| C / C++ | Planned | — |

## Examples

From `impl/clojure`:

```bash
clojure -M:cards    # durable LMDB rooted-store card game
```

See [examples/](examples/) for `cards.clj` and `config.clj`.

Portable examples run identically on every host (JVM, babashka, nbb):

```bash
bb todo                        # babashka
npx nbb -m dacite.examples.todo  # nbb
bin/hash-parity.sh             # assert identical root hash on all hosts
```

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
