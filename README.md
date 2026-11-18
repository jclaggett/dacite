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

**Alpha 0.2** — Clojure / SCI reference implementation: hash fusion, content
stores, values, rooted stores, pack transport, wire-v1 binary packs (JVM +
browser), remote HTTP service (**480+ tests**). Soft pack budget default
**1024**. APIs may change; see [CHANGELOG.md](CHANGELOG.md).

## Documentation

- **[The Dacite way](https://dacite.io/book/the-dacite-way.html)** — how to think about data ([source](docs/book/the-dacite-way.md))
- **[Anatomy of a Dacite app](https://dacite.io/book/building/anatomy.html)** — Values / Store split, `v/root`
- **[Install](https://dacite.io/book/getting-started/install.html)** — nbb + JVM setup ([source](docs/book/getting-started/install.md))
- **[First values](https://dacite.io/book/tutorial/hello-nbb.html)** — five-minute tutorial
- **[Building apps](https://dacite.io/book/building/anatomy.html)** — document · history · large seq · CAS · blobs · explorer · browser
- **[Values](https://dacite.io/book/reference/values.html)** / **[Stores](https://dacite.io/book/reference/stores.html)** API reference
- **[The Dacite Book](https://dacite.io/book/)** — also under [docs/book/](docs/book/)
- **[Roadmap](docs/roadmap.md)** — next is an asynchronous IStore, then an IndexedDB store for web clients, then a QUIC reveal client
- **[Design docs](docs/design/)** — store/transport internals
- **[Portable core](docs/design/portable-core.md)** — JVM / babashka / nbb

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

See [impl/clojure/src/dacite/examples/](impl/clojure/src/dacite/examples/) for the portable apps (compiled and tested with the library). Browser UI assets live in [examples/web/](examples/web/).

Portable examples run on every host (JVM, babashka, nbb). Config and todo are
**durable** rooted-store apps (file content + `ROOT`). Config is the same
domain against a file store **or** an LMDB store.

```bash
npm install
npm run hello                      # Hello World (mem store + hashes)
npm run config                     # config CLI (file store)
npm run notes                      # versioned notes (file store)
npm run log                        # event log (file store; seeds 2000 events)
npm run sync                       # directory/blob sync (file store)
npm run todo                       # interactive nbb UI (chalk + prompts)
npm run todo:batch                 # non-interactive list
npx nbb -m dacite.examples.todo-ui -- --reset
bb config show                     # babashka config (file store)
bb notes show                      # babashka notes (file store)
bb log --reset show                # babashka event log (file store)
bb sync --reset seed               # babashka directory sync
bb todo                            # babashka batch (java.io file store)
bin/hash-parity.sh                 # assert identical root hash on all hosts

# JVM — file store
cd impl/clojure
clojure -M:config -- --reset show
clojure -M:config -- set timeout 60
clojure -M:notes -- --reset show
clojure -M:notes -- bench
clojure -M:log -- --reset show
clojure -M:log -- page 0
clojure -M:log -- bench
clojure -M:sync -- --reset seed
clojure -M:sync -- ls
clojure -M:sync -- cat readme.txt
```

See [Config](https://dacite.io/book/tutorial/config.html) ([source](docs/book/tutorial/config.md)),
[Versioned notes](https://dacite.io/book/tutorial/notes.html) ([source](docs/book/tutorial/notes.md)),
[Event log](https://dacite.io/book/tutorial/event-log.html) ([source](docs/book/tutorial/event-log.md)),
and [Directory / blob sync](https://dacite.io/book/tutorial/sync.html) ([source](docs/book/tutorial/sync.md)).

### Local peer (`npx dacite`)

```bash
npm install
npm run browser:build              # the Value Browser bundle (needs Java + Clojure CLI)
npx dacite                         # http://127.0.0.1:8790/app/ ; --port to change it
```

One process on one 127.0.0.1 port: the app pages, WebSocket signaling at
`/signal`, and an nbb peer holding one welcome value. Open the `open` URL it
prints and paste the `dacite://local#urn:dacite:<hex>` line it prints. See
[docs/design/packaging.md](docs/design/packaging.md).

### Browser demos

```bash
npm run web:serve                  # serves examples/web at http://127.0.0.1:8080/app/
```

The library reader (`/app/library/`) runs over peering; see
[examples/web/README.md](examples/web/README.md). The todo (`/app/`) and
explorer (`/app/explorer/`) pages are placeholders. No working mode yet:
they will run over peering later.

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
