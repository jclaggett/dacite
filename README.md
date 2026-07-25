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

**Reference implementation (Clojure)** — hash fusion, content stores, values,
rooted stores, remote HTTP service, and leaf-chunking transport packing are
implemented (**376+ tests**). Browser todo demo uses pack-filled gets and
write-back flush. Soft pack budget default **1024**. See [docs/roadmap.md](docs/roadmap.md).

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

Portable examples run on every host (JVM, babashka, nbb). Todo is a **durable**
rooted-store app (file content + `ROOT`).

```bash
npm install
npm run todo                       # interactive nbb UI (chalk + prompts)
npm run todo:batch                 # non-interactive list
npx nbb -m dacite.examples.todo-ui -- --reset
bb todo                            # babashka batch (java.io file store)
bin/hash-parity.sh                 # assert identical root hash on all hosts
```

### Browser demo (HTTP service + Dacite values in the browser)

```bash
cd impl/clojure
clojure -M:cljs-web                # build examples/web/js/main.js (first time)
clojure -M:service --port 8080 --mem
# open http://127.0.0.1:8080/app/
```

See [examples/web/README.md](examples/web/README.md). The UI loads Dacite
collections in the browser and persists via `GET/PUT /node/{hex}` and
`GET /root` + `POST /root/cas`.

## Use Cases

- **Configuration management** — server pushes root hash, clients pull what they need
- **Distributed state** — sync structured data across nodes efficiently
- **Versioned data** — every root hash is a complete snapshot

## Name

*Dacite* — an obscure volcanic rock. Also: **Da**ta **Cite**ation.

## License

Apache 2.0 — see [LICENSE](LICENSE)
