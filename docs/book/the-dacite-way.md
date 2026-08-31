# The Dacite way

Dacite is a way of writing programs over **immutable values** while **stores**
hold and move the bytes. The application thinks in maps, vectors, strings, and
scalars. Persistence, caching, and the network are a dictionary from content
hash to node.

This page is a stance, not an API. Anatomy of an app is next. Skip to
[How it works](./01-stores/chapter.md) only if you are implementing a store.

## Values, not blobs you round-trip

A Dacite value is store-aware and content-addressed. It knows its **type**,
its **hash**, and which **store** created it. You do not serialize a Clojure map
to EDN, write a file, and parse it back later. You **build** a value; the
constructor writes nodes into the store; the hash *is* the identity of that
content.

The closed set of user kinds is small on purpose:

- **scalar** — a typed atom (`i64`, `bool`, `char`, `null`, …)
- **string** — text (a sequence of characters)
- **blob** — bytes
- **vector** — an ordered collection of values
- **map** — keys to values
- **set** — distinct values

Model the domain in those six. Do not keep a host map “until it is time to
save.” There is no save step. An `assoc` or `conj` returns a **new** value
whose unchanged parts are the same nodes — and the same hashes — as before.

Finger trees and HAMTs implement those collections. They are not the
application model. Pack chunks are not the application model either. Domain
code requires `dacite.value` and `dacite.store`.

## Identity is the hash

Two values with the same type and content have the same hash on every host
(JVM, babashka, nbb). If you edit a document’s title and leave the body
alone, the body hash does not change. History is free: keep the old document
value (or its hash) in a vector; restoring it is installing that value again,
not replaying a diff.

This is the property a JSON file cannot match. Overwriting `config.json`
destroys the previous snapshot unless you invent versioning beside the data.
In Dacite the previous snapshot is still in the store, reachable from any
root that still names it.

## You almost never want the whole tree

A value may be larger than memory, or only partly on this machine. Reads
should name **what you need**:

| You need | Use |
|----------|-----|
| A scalar or short string field | `native` / `as-str` |
| A blob’s bytes | `as-bytes` |
| One element | `nth` / `get` |
| A page of a vector | `subvec` |
| To walk | lazy `seq` (elements are still Dacite values) |

`realize` on a scalar yields a host atom. `realize` on a collection yields a
**lazy** seq of realized elements — not `into []`. Consuming the whole seq is
an explicit full traversal.

Do not convert a Dacite value into a host collection “so the rest of the
program can use it.” That assumes the tree fits in RAM and is fully local.
`dac->clj` exists on the JVM as an emergency hatch for tests. Application
code that reaches for it has found a hole in `dacite.value` — fill the hole
(a bounded field read, a page, a streaming encoder), do not dump the tree.

## One mutable root

Underneath, nothing is updated in place. The only moving cell is a **root
hash** on a rooted store. “Save” means: compute a new value, then
compare-and-set the root to its hash.

Several writers coordinate on that one hash. The portable update is
`ref-swap!` (retry on conflict). There is no distributed lock and no CRDT
in the core. Two clients appending to a log interleave with compare-and-set;
lost updates show up as retries, not silent clobbering.

Remote stores refuse unconditional `ref-reset!`. Seeding an empty remote
root uses `ref-cas!` from `nil`.

## Stores are wiring

Mem, file, LMDB, and HTTP all speak the same store protocol. Domain functions
take and return values. They do not open files or build URLs.

A typical app splits in two, as [todo.cljc](https://github.com/jclaggett/dacite/blob/main/examples/dacite/examples/todo.cljc) does:

- **Values** — `add-todo`, `toggle-at`, seed data, `root-ref` swap. Only
  `dacite.value`.
- **Store** — path, `--url`, write-back policy, reset. No todo shape.

Point the same domain at a file store or at `clojure -M:service`. The HTTP
client packs neighborhoods on GET and flushes packed literals on commit.
That is transport. The domain does not import it.

## Not git, not REST, not an atom

| Usual habit | What Dacite does instead |
|-------------|--------------------------|
| Atom + EDN file | Values persist as they are built; the root hash is the current snapshot |
| JSON over REST | Clients pull **nodes they don't have**, not a serialized view of the whole document |
| Git | Snapshots of **values**, not of files; unchanged subtrees are identical hashes, not similar blobs |

Git is a fine tool for source. REST is a fine way to expose a view. An atom
plus a file is a fine way to sketch. Dacite is for programs whose data
**is** the value: nested, versioned, partly remote, and large enough that
you should not load all of it to change one field.

## Next

- [Anatomy of a Dacite app](./building/anatomy.md) — the recipe.
- [Install](./getting-started/install.md) — nbb or JVM.
- [First values](./tutorial/hello-nbb.md) — five minutes, no service.
- [Cookbook](./guide/read.md) — field reads, updates, commit loops.
- [Values API](./reference/values.md) when you need a function name.

If you are implementing a hash function or a new store backend, start at
[Content Stores](./01-stores/chapter.md).
