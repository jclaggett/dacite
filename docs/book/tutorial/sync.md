# Directory / blob sync

A host folder becomes a tree of Dacite maps and blobs. Listing a
directory does not realize file bodies; opening one file does not pull
its siblings. A second sync copies only missing nodes. This is the last
application in the [roadmap](../../roadmap.md).

You will:

1. Seed a sample tree (two files share a blob).
2. `ls` names and sizes without reading file bytes.
3. `cat` one file.
4. Push the tree to `clojure -M:service` and pull it elsewhere.

## Prerequisites

- JDK 17+ and the Clojure CLI, or Node.js / babashka for the file store

## The shape

```text
{"kind" "dir"
 "entries" {"readme.txt" {"kind" "file" "size" 13 "blob" <blob>}
            "copy.txt"   {"kind" "file" "size" 13 "blob" <same blob>}
            "sub"        {"kind" "dir"  "entries" {…}}}}
```

`ls` reads `kind` and `size` only. `cat` calls `v/as-bytes` on one blob.
Identical contents share one blob hash.

## Local

```bash
cd impl/clojure
clojure -M:sync -- --reset seed
clojure -M:sync -- ls
clojure -M:sync -- ls sub
clojure -M:sync -- cat readme.txt
clojure -M:sync -- bench
```

Or `npm run sync -- --reset seed` / `bb sync --reset seed`.

`put /path/to/dir` ingests a real host folder. `export DIR` writes the
tree back to disk.

## Push and pull

```bash
# terminal 1
clojure -M:service --port 8080 --store mem

# terminal 2 — publish the local tree
clojure -M:sync -- --url http://127.0.0.1:8080 push

# terminal 3 — another store
clojure -M:sync -- --path /tmp/dacite-sync-b --url http://127.0.0.1:8080 pull
clojure -M:sync -- --path /tmp/dacite-sync-b ls
```

`store/sync-reachable!` copies the subgraph (packed flush to a remote,
per-node copy otherwise). Then the dest root is CAS'd to the same hash.
A second pull copies nothing already present.

## What the bench shows

```text
sync bench
  names:        data.bin, readme.txt, sub, copy.txt
  shared blob:  true
  store nodes:  1992
  readme:       13 B
  data.bin:     256 B
```

The sample root hash is the same on JVM, babashka, and nbb:

`fe0532ab564af43a7fb7c94541eaf63d63fa70d24e1290e6899747a571f4f196`

A remote test measures store-protocol bytes: **GET of one blob**
transfers less than GET of that blob plus its siblings. A second local
`sync-reachable!` copies 0 nodes.

## What this pulled

| Why | Utility |
|---|---|
| Bytes in / bytes out | `v/blob-via`, `v/as-bytes` (limit + `:dacite/missing`) |
| Copy a tree before moving the root | `dacite.store.sync/sync-reachable!` |
| List without bodies | walk `kind`/`size`; do not `as-bytes` |

Opaque-byte store entries stayed on the shelf — EDN file nodes are
enough to prove the fetch claim. A later port can store raw bytes
without changing the app.

The five-app sequence is complete.
