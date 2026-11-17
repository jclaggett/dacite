# Browse without dumping

> **No working mode right now.** This page ran against the JVM HTTP
> service (`dacite.service`), which the HTTP removal (slice 2) deleted.
> The page stays as a record until the demo is deleted or ported to
> peering. The library reader (`/app/library/`) runs over peering; see
> [examples/web/README.md](https://github.com/jclaggett/dacite/blob/main/examples/web/README.md).

You want to see the current root as **typed values**, expand a collection,
and not download the whole tree. Dacite’s move: walk with `v/type`,
`count`, `nth`, paged `seq`; never `dac->clj`. HTTP `GET /node` returns a
packed neighborhood; the UI then walks values locally.

This is the sixth claim-proving app in the
[roadmap](../../roadmap.md).

You will:

1. Open `/app/explorer/` against the (removed) HTTP service.
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
`dacite.value`: `type`, `count`, `nth`, paged `seq`, lazy
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

String/blob rows still realize a short prefix of char/byte nodes; after
a string literal those nodes are already local.

Strings and blobs are **leaves**: a truncated preview and the total
count. A later “read more” can lengthen that prefix; this first pass
does not.

## Run

```bash
cd impl/clojure
clojure -M:cljs-explorer          # once / after cljs changes
```

The page then needed the HTTP service at `/app/explorer/`; that server
is gone (see the note at the top).

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

Pointed at a store you already seeded (the event log's
`target/dacite-log`, say), the explorer showed the ledger, not the
gallery. It must not overwrite that root.

## Measure

```bash
cd impl/clojure
clojure -M:dev:test -n dacite.examples.explorer-test
```

The HTTP measurement (`remote-expand-page-cheaper-than-full-seq`) was
removed with the service. The remaining tests check the explorer's
domain walk on a local store.

## Related

- [A browser app](../building/browser-app.md) — todo + explorer on one service
- [Values API](../reference/values.md)
- [Anatomy of a Dacite app](../building/anatomy.md)
