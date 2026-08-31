# A browser app

The Values / Store split still holds in the tab. Domain code is
`dacite.value`. The store is HTTP plus a write-back cache. The UI is DOM
(todo) or a typed tree (explorer). Neither app dumps the root to JSON.

## Run

```bash
cd impl/clojure
clojure -M:cljs-web                  # todo bundle (first time / after cljs)
clojure -M:cljs-explorer             # explorer bundle
clojure -M:service --port 8080 --store mem
```

| URL | App |
|-----|-----|
| http://127.0.0.1:8080/app/ | Todo — add / toggle / remove; CAS the root |
| http://127.0.0.1:8080/app/explorer/ | Value explorer — walk the **same** root as typed values |

Use a trailing slash. Hard-refresh after rebuilding JS (`main.js?v=…`
cache-busts when we bump it).

Durable service: `--store file` or `--store lmdb`. Details:
[examples/web/README.md](https://github.com/jclaggett/dacite/blob/main/examples/web/README.md).

## What the browser is doing

1. `GET /root` — current hash, or none.
2. Domain ops (`add-todo`, expand a node) run on Dacite values in memory.
3. `GET /node/{hex}` — one pack-filled chunk (literals under the hash,
   ~1k soft budget). The client applies the chunk, then reads the value.
   A tree click is not a GET if that neighborhood is already local.
4. Commit: write-back flush `POST /nodes` (same Layer 1 literals as GET),
   then `POST /root/cas`.

Todo CLI uses `v/root-ref` on a file store. The browser todo still
coordinates hashes at the CAS layer in places — a library hole, not a
reason to invent a JS model. Explorer is read-only on whatever root the
service already has (todo, notes, the type gallery).

Sync XHR keeps `IStore` blocking so value ops stay synchronous. That is
demo-only; an async remote is deferred until an app pulls it.

## Next

- [Browse without dumping](../tutorial/explorer.md) — paging and pack GET.
- [Anatomy of a Dacite app](anatomy.md) — the same recipe as the CLI.
- [The Dacite way](../the-dacite-way.md)
