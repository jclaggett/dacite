# Value explorer

A web UI that shows the **root value** of a rooted store as a tree.
This is the sixth application in the [roadmap](../../roadmap.md): **lazy
access** — expand a collection, fetch that neighborhood, never dump the
whole tree.

You will:

1. Open `/app/explorer/` against `clojure -M:service`.
2. See every public Dacite type as **type + value** (not a JSON/EDN dump).
3. Expand vectors, maps, and sets; page long collections 32 at a time.
4. Measure that the first page of a 128-element vector costs less than
   `seq` of the whole vector.

## Prerequisites

- JDK 17+ and the Clojure CLI
- A clone of this repo (see [Install](../getting-started/install.md))

## The shape

The explorer does not invent a domain. It displays **whatever is at the
server root**. If the root is empty, it CAS-seeds a **type gallery** — a
map that includes every public type, plus `"page-me"` (128 small maps)
so paging is forced. An existing root (notes, event log, sync, todo) is
left alone.

Domain code lives in `dacite.examples.explorer` and uses only
`dacite.value`: `value-type`, `count`, `nth`, paged `seq`, lazy
`realize` prefixes for strings/blobs. It never calls `dac->clj`.

The browser store is the same as the todo demo: write-back cache plus
default pack-filled `GET /node`. One request returns **data** — realized
literals for a neighborhood under the asked hash, inside the ~1k soft
budget. `apply-chunk!` installs those as ordinary nodes in the tab's mem
cache. The explorer then walks **values** locally (`dacite.value`); a
tree click is not a GET.

A 5-item todo list fits in a single literal, so load + expand of the
first item is one node GET after `GET /root`. A title longer than 1k
still takes several GETs, but each GET BFS-fills ~1k of neighborhood
(last item may overshoot toward 2k). A refresh starts a new heap, so
that cache is empty again.

`?raw=1` and `?nodes=1` exist for debug (bare node / node-only pack).
They are not the explorer path.

String/blob rows still realize a short prefix of char/byte nodes; after
a string literal those nodes are already local.

Strings and blobs are **leaves**: a truncated preview and the total
count. A later “read more” can lengthen that prefix; this first pass
does not.

## Run

```bash
cd impl/clojure
clojure -M:cljs-explorer          # once / after cljs changes
clojure -M:service --port 8080 --store mem
# open http://127.0.0.1:8080/app/explorer/   (trailing slash)
```

Todo stays at [http://127.0.0.1:8080/app/](http://127.0.0.1:8080/app/).

**Reload** re-fetches `GET /root`. It does not watch `GET /events`.

## What to look at

- Each row is a type badge then a summary (`i64 -64`, `vector 3`,
  `string "prefix…" (n chars)`, `blob n bytes 0x…`).
- Maps show **typed keys** as well as values (the gallery includes a
  vector key).
- `"page-me"` is 128 entries; the first expand shows 32, then **show
  next 32**.
- The **bw** line is store-protocol bodies only (`GET /node`, `GET /root`,
  CAS) — the same meter as the todo demo.

## Existing data

Point the service at a store you already seeded:

```bash
cd impl/clojure
clojure -M:log -- --reset show          # 2000 events in target/dacite-log
clojure -M:service --port 8080 --store file:target/dacite-log
# open /app/explorer/ — the ledger, not the gallery
```

The explorer must not overwrite that root.

## Measure

```bash
cd impl/clojure
clojure -M:dev:test -n dacite.examples.explorer-test
```

`remote-expand-page-cheaper-than-full-seq` seeds the gallery over HTTP,
then compares a cold client’s first `child-page` of `"page-me"` with
`seq` of all 128. Page bytes and requests stay strictly below the full
walk.

## Related

- [Values API](../reference/values.md)
- [Stores API](../reference/stores.md)
- [Browser todo](https://github.com/jclaggett/dacite/blob/main/examples/web/README.md)
- [Directory / blob sync](sync.md) — another tree; `ls` without bodies
