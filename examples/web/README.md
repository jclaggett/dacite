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

After pulling server/client pack changes, recompile the UI (`clojure -M:cljs-web`)
and **hard-refresh** the browser (cached `main.js` will break load/add if the
server returns pack chunks but the client still expects bare nodes).

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
use a **write-back cache** so seed/add do far fewer round-trips than a bare
remote store. Value collections remain pure finger trees / HAMTs; packing for
transport belongs at the HTTP/wire layer (`dacite.store.pack`), not in
`dacite.value`.

### Benchmarks

```bash
cd impl/clojure
clojure -M:dev -m dacite.bench.todo-bw --policy write-back
clojure -M:dev -m dacite.bench.todo-bw --budget-sweep
clojure -M:dev:test -n dacite.bench.todo-bw-test
```

Scenarios: `seed-cold`, `add-warm`, `reload-cold`. Metrics: requests, bytes-sent,
bytes-recv (store protocol only).

## Wire format

Node bodies and pack chunks are EDN. Soft pack budget default **1024** bytes
(leaf-chunking 2d). 64-bit hash words that would lose precision as JSON numbers
travel as `#dacite/u64 "…"` (`dacite.wire`). JVM and browser clients share that
codec.

**Reads:** default `GET /node/{hex}` returns a pack-filled `chunk-v1` (BFS
neighborhood under the hash); client applies then `s-get`s locally.

**Writes (write-back):** flush posts soft-budget chunks to `POST /nodes` with
novelty (`:created` / `:exists`).

## API (service.md)

| Method | Path | Role |
|--------|------|------|
| GET | `/node/{64-hex}` | Pack-filled get (default); `?raw=1` bare node |
| PUT | `/node/{64-hex}` | Single-node put → novelty body |
| HEAD/DELETE | `/node/{64-hex}` | Existence / optional GC |
| POST | `/nodes` | Apply pack chunk (write) |
| POST | `/nodes/get` | Bulk pack (demoted; admin/sync) |
| GET | `/root` | Current root hash hex or null |
| POST | `/root/cas` | Body `{:expected hex-or-nil :new hex}` |

## Rebuild UI

```bash
cd impl/clojure && clojure -M:cljs-web
```

## Code layout (Values vs Store)

Demo code is split so **value logic** does not depend on path/HTTP/budget, and
**store wiring** does not know the todo list shape.

| Concern | Responsibility | Where |
|---------|----------------|--------|
| **Values** | Seed items, `build` / `add-todo` / …, load root value, commit root hash | `todo.cljc` (Values section); `todo_web.cljs` (Values section) |
| **Store** | File path / HTTP base / write-back policy / root cell or CAS | `todo.cljc` (Store section); `todo_web.cljs` (Store section) |
| **UI** | CLI prompts or DOM | `todo_ui.cljs`, `todo_web.cljs` (UI) |

Portable batch/CLI: `open-store` → `load-or-seed!` → domain ops → `commit-todos!`.  
Browser: `open-store` (HTTP + `:write-back`) → `load-or-seed!` (CAS) → same domain ops.

## Related sources

| Piece | Path |
|-------|------|
| Service handlers + HttpServer | `impl/clojure/src/dacite/service.clj` |
| CLI entry | `impl/clojure/src/dacite/service/main.clj` |
| Browser remote store | `impl/clojure/src/dacite/store/browser.cljs` |
| JVM remote store | `impl/clojure/src/dacite/store/remote.clj` |
| Wire EDN | `impl/clojure/src/dacite/wire.cljc` |
| Todo Values + file Store | `examples/dacite/examples/todo.cljc` |
| Web UI (Store + Values + DOM) | `examples/dacite/examples/todo_web.cljs` |
| nbb interactive UI | `examples/dacite/examples/todo_ui.cljs` |
