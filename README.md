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

**Alpha 0.1** — Clojure / SCI reference implementation: hash fusion, content
stores, values, rooted stores, pack transport, wire-v1 binary packs (JVM +
browser), remote HTTP service (**460+ tests**). Soft pack budget default
**1024**. APIs may change; see [CHANGELOG.md](CHANGELOG.md).

## Documentation

- **[The Dacite way](https://dacite.io/book/the-dacite-way.html)** — how to think about data ([source](docs/book/the-dacite-way.md))
- **[Anatomy of a Dacite app](https://dacite.io/book/building/anatomy.html)** — Values / Store split, `root-ref`
- **[Install](https://dacite.io/book/getting-started/install.html)** — nbb + JVM setup ([source](docs/book/getting-started/install.md))
- **[First values](https://dacite.io/book/tutorial/hello-nbb.html)** — five-minute tutorial
- **[Building apps](https://dacite.io/book/building/anatomy.html)** — document · history · large seq · CAS · blobs · explorer · browser
- **[Values](https://dacite.io/book/reference/values.html)** / **[Stores](https://dacite.io/book/reference/stores.html)** API reference
- **[The Dacite Book](https://dacite.io/book/)** — also under [docs/book/](docs/book/)
- **[Roadmap](docs/roadmap.md)** — where we are; next is a real app
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

See [examples/](examples/) for `cards.clj` and the portable config/todo apps.

Portable examples run on every host (JVM, babashka, nbb). Config and todo are
**durable** rooted-store apps (file content + `ROOT`). Config is the same
domain against a file store **or** `clojure -M:service`.

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

# JVM — file or HTTP (start the service in another terminal)
cd impl/clojure
clojure -M:config -- --reset show
clojure -M:config -- --url http://127.0.0.1:8080 set timeout 60
clojure -M:notes -- --reset show
clojure -M:notes -- bench
clojure -M:log -- --reset show
clojure -M:log -- page 0
clojure -M:log -- bench
# two writers + SSE watch (service in another terminal)
clojure -M:log -- --url http://127.0.0.1:8080 contend 10
clojure -M:log -- --url http://127.0.0.1:8080 watch
clojure -M:sync -- --reset seed
clojure -M:sync -- ls
clojure -M:sync -- cat readme.txt
```

See [Remote config](https://dacite.io/book/tutorial/config.html) ([source](docs/book/tutorial/config.md))
[Versioned notes](https://dacite.io/book/tutorial/notes.html) ([source](docs/book/tutorial/notes.md)),
[Event log](https://dacite.io/book/tutorial/event-log.html) ([source](docs/book/tutorial/event-log.md)),
[Two-client live](https://dacite.io/book/tutorial/two-client.html) ([source](docs/book/tutorial/two-client.md)),
and [Directory / blob sync](https://dacite.io/book/tutorial/sync.html) ([source](docs/book/tutorial/sync.md)).

### Browser demo (HTTP service + Dacite values in the browser)

```bash
cd impl/clojure
clojure -M:cljs-web                # build examples/web/js/main.js (first time)
clojure -M:service --port 8080 --store mem
# durable file:  clojure -M:service --port 8080 --store file
# durable LMDB:  clojure -M:service --port 8080 --store lmdb
# open http://127.0.0.1:8080/app/            # todo
clojure -M:cljs-explorer             # explorer bundle
# open http://127.0.0.1:8080/app/explorer/   # value explorer
```

See [examples/web/README.md](examples/web/README.md). The UI loads Dacite
collections in the browser and persists via `GET/PUT /node/{hex}` and
`GET /root` + `POST /root/cas`. The explorer walks the current root as
typed values without dumping the tree.

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
