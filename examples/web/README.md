# Dacite browser demos

The pages are static files under `examples/web`, served at `/app/`. From
the repo root:

```bash
npm run web:serve          # http://127.0.0.1:8080/app/ (scripts/static-server.mjs)
```

| URL | App |
|---|---|
| http://127.0.0.1:8080/app/library/ | **Library** — chapter and plate read from an nbb peer |
| http://127.0.0.1:8080/app/peer/ | **Browser peer** — read a value from an nbb peer into IndexedDB |
| http://127.0.0.1:8080/app/browser/ | **Value browser** — paste `dacite://<host>#urn:dacite:<hex>` and fetch that hash. Intended path: `dacite.io/browser` |
| http://127.0.0.1:8080/app/ | Todo — **no working mode** (see below) |
| http://127.0.0.1:8080/app/explorer/ | Value explorer — **no working mode** (see below) |

---

# Library reader (M5.3)

Chapter and plate from the nbb peer: `npm run library:build`, then
`npm run web:serve` and `nbb -m dacite.examples.library-nbb`, and open the
URL it prints. The page takes `chapter`, `root`, `signal`, and `room`, and
reads that root from the peer only. Without them it shows an error.
Measurements: `docs/plan/measurements.md`.

---

# Todo and explorer demos (no working mode)

These two pages read and wrote through the JVM HTTP service
(`dacite.service`, `GET /node`, `POST /root/cas`) with the browser HTTP
store `dacite.store.browser`. The HTTP removal (slice 2) deleted that
service, so the pages load but cannot reach a store. They, and
`dacite.store.browser`, stay in the tree until they are deleted or ported
to peering. The notes below describe how they worked.

The point of the todo demo: the browser holds real Dacite collections
(finger-tree vectors of maps), not a parallel JS model.

## Build

```bash
cd impl/clojure && clojure -M:cljs-web        # todo bundle
cd impl/clojure && clojure -M:cljs-explorer   # explorer bundle
```

See [docs/book/tutorial/explorer.md](../../docs/book/tutorial/explorer.md).

## What it did

1. Browser loads compiled CLJS (`examples/web/js/main.js`) containing the
   portable Dacite core + todo domain + HTTP remote store
   (`dacite.store.browser`).
2. `GET /root` — if empty, seed sample todos, then `POST /root/cas`.
3. Render the Dacite vector of `{title, done}` maps.
4. Add / toggle / remove: Dacite ops in the browser, nodes written via HTTP,
   root advanced with CAS.

Pack chunks used **wire-v1 binary** by default. `fetch` reads
`response.body` as chunks arrive. `IStore` stays synchronous: a miss
suspends, `dacite.store.browser/drive` waits for that body, then reruns the
op against the cache. The **bw** line counted store-protocol bodies only.

### Benchmark

The live HTTP scenarios were removed with the service. The pack-budget
encode sweep is local:

```bash
cd impl/clojure
clojure -M:dev -m dacite.bench.todo-bw
clojure -M:dev:test -n dacite.bench.todo-bw-test
```

## Code layout (Values vs Store)

| Concern | Responsibility | Where |
|---------|----------------|--------|
| **Values** | Seed items, `build` / `add-todo` / …, load root value, commit root hash | `todo.cljc` (Values section); `todo_web.cljs` (Values section) |
| **Store** | File path / HTTP base / write-back policy / root cell or CAS | `todo.cljc` (Store section); `todo_web.cljs` (Store section) |
| **UI** | CLI prompts or DOM | `todo_ui.cljs`, `todo_web.cljs` (UI) |

## Related sources

| Piece | Path |
|-------|------|
| Browser HTTP store | `impl/clojure/src/dacite/store/browser.cljs` |
| Wire EDN | `impl/clojure/src/dacite/wire.cljc` |
| Todo Values + file Store | `impl/clojure/src/dacite/examples/todo.cljc` |
| Web UI (Store + Values + DOM) | `impl/clojure/src/dacite/examples/todo_web.cljs` |
| nbb interactive UI | `impl/clojure/src/dacite/examples/todo_ui.cljs` |

## Browser peer (M5.1)

shadow-cljs compiles the peer namespaces, IndexedDB, and the browser
`RTCPeerConnection` transport. The `:cljs-web`, `:cljs-explorer`, and
`:cljs-library` aliases are unchanged.

```bash
# from the repo root
npm install
npm run peer:build          # examples/web/peer/js
npm run browser:test        # compiled transport + IndexedDB checks
```

To read a value held by an nbb peer, start the slice-3 relay and
`nbb -m dacite.examples.peer-nbb`, then open the URL that process prints.
The page verifies each item into IndexedDB and shows the root hash and the
entry count.
`npm run peer:e2e` runs the same session in headless Chromium. The Safari
and Firefox hand runs now use the M6.4 Value Browser: steps under M6.4 in
`docs/plan/peering.md`, and the checklist under Follow-ups in
`docs/design/packaging.md`.

## Value browser (M6.4)

Same static root as the other example pages (`npm run web:serve`). The local URL is
http://127.0.0.1:8080/app/browser/ . The intended public path is
`dacite.io/browser` (this directory). dacite.io is not wired here.

```bash
npm run browser:build
npm run webrtc:signal
nbb -m dacite.examples.browser-nbb --ws ws://127.0.0.1:8787 --room m64
npm run web:serve
```

Paste `dacite://<name>#urn:dacite:<hex>` from the nbb output. The relay
and the room are separate from that URI. The nbb process prints `waiting`
and stays up until Fetch; that click is when the page joins the room.
`npm run browser:e2e` runs the headless check. The hand steps are under
M6.4 in `docs/plan/peering.md`.
