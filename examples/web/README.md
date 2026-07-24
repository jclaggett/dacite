# Dacite browser todo demo

Demonstrates a **web UI driven by Dacite values**, with persistence via the
HTTP content-store + root CAS protocol (`docs/design/service.md`).

The point of the demo: the browser holds real Dacite collections (finger-tree
vectors of maps), not a parallel JS model. Persistence is the same content-store
protocol any remote client can use.

## Run

```bash
# from repo root — compile browser bundle (first time / after cljs changes)
cd impl/clojure && clojure -M:cljs-web && cd ../..

# start HTTP service (serves API + static UI)
cd impl/clojure && clojure -M:service --port 8080 --mem
# or durable: clojure -M:service --port 8080 --store target/dacite-service
```

Open **http://127.0.0.1:8080/app/** in a browser.

## What it does

1. Browser loads compiled CLJS (`examples/web/js/main.js`) containing the
   portable Dacite core + todo domain + HTTP remote store
   (`dacite.store.browser`).
2. `GET /root` — if empty, seed sample todos (write-through `PUT /node/...`
   then `POST /root/cas`).
3. Render the Dacite vector of `{title, done}` maps.
4. Add / toggle / remove: Dacite ops in the browser, nodes written via HTTP,
   root advanced with CAS. Reload re-reads the root and materializes the same
   values.

## Bandwidth display

The UI shows a **bw** line for **store-protocol** traffic only
(`GET/PUT/HEAD/DELETE /node/*`, `GET /root`, `POST /root/cas`):

- session totals: request count, body bytes sent (↑), received (↓), sum (Σ)
- **last** action: cost of the most recent load/seed, add, toggle, remove, or reload

Static assets (`/app/` HTML/JS/CSS) are **not** counted. Sizes are request and
response **body** string lengths (≈ UTF-8 bytes for ASCII EDN).

This is meant to make content-addressed transfer cost visible. Client defaults
use a **write-back cache** (and optional domain compact todo-entry nodes) so
seed/add do far fewer round-trips than a bare remote store. Value collections
remain pure finger trees / HAMTs; chunking leaves for transport belongs at the
HTTP/wire layer (phase 2), not in `dacite.value`.

### Benchmarks

```bash
cd impl/clojure
clojure -M:dev -m dacite.bench.todo-bw --policy write-back
clojure -M:dev:test -n dacite.bench.todo-bw-test
```

Scenarios: `seed-cold`, `add-warm`, `reload-cold`. Metrics: requests, bytes-sent,
bytes-recv (store protocol only).

## Wire format

Node bodies are EDN. 64-bit hash words that would lose precision as JSON
numbers travel as `#dacite/u64 "…"` (`dacite.wire`). JVM and browser clients
share that codec.

## API (service.md)

| Method | Path | Role |
|--------|------|------|
| GET/PUT/HEAD/DELETE | `/node/{64-hex}` | Content-addressed nodes |
| GET | `/root` | Current root hash hex or null |
| POST | `/root/cas` | Body `{:expected hex-or-nil :new hex}` |

## Rebuild UI

```bash
cd impl/clojure && clojure -M:cljs-web
```

## Related sources

| Piece | Path |
|-------|------|
| Service handlers + HttpServer | `impl/clojure/src/dacite/service.clj` |
| CLI entry | `impl/clojure/src/dacite/service/main.clj` |
| Browser remote store | `impl/clojure/src/dacite/store/browser.cljs` |
| JVM remote store | `impl/clojure/src/dacite/store/remote.clj` |
| Wire EDN | `impl/clojure/src/dacite/wire.cljc` |
| Todo domain (portable) | `examples/dacite/examples/todo.cljc` |
| Web UI | `examples/dacite/examples/todo_web.cljs` |
