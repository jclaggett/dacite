# Changelog

All notable changes to the Dacite reference implementation are documented here.
The project follows [Semantic Versioning](https://semver.org/) with an **alpha**
stability promise: public APIs may still change before 1.0.

## Unreleased

- **Fix #16: a string is a sequence of code points, on every host; a lone
  surrogate is refused.** A string was walked as UTF-16 code units on the JVM and on
  nbb, so an emoji (U+1F600) was two elements, two lone surrogates, which the JVM
  encoded as `?` and a JS `TextEncoder` as U+FFFD: the same string was two different
  values on the two hosts (BMP text agreed, which is why no vector caught it). Now
  `count`, `nth`, `slice`, `remove-nth`, page and run counts, and the `n` of a
  `run` of `char` on the wire are in **code points**; the hash of `a😀b` is one
  value on the JVM, nbb and in the browser. Design, with the lone-surrogate
  decision and its reasons: `docs/design/text-code-points.md`; the rule in
  `docs/spec/wire-v1.md` ("Text") and the book (03 values).
  - **A lone surrogate throws** `ex-info` with `:reason :lone-surrogate` (`:index`,
    `:code-unit`), the same on both hosts: building a `string` or `char` from it,
    `host/utf8-bytes`, and the size functions. It is not turned into `?` or U+FFFD,
    because that silently changes the text (and is not injective: `"\uD83D"` and
    `"\uFFFD"` would be one value) and UTF-8, the wire format, has no form for it.
    Repair text first (JS: `toWellFormed()`).
  - **Malformed UTF-8 is a decode error** (`:reason :invalid-utf8`) instead of
    becoming U+FFFD (wire-v1 already said so; the code did not do it). A `char` that
    decodes to other than one code point is `:bad-char`. A leading U+FEFF is text:
    `TextDecoder` used to drop it on nbb and in the browser (`ignoreBOM` is now set).
  - **Pages hold whole code points.** `chunk-run` and `split-body-at` cut at
    code point boundaries and count each character's 1 to 4 UTF-8 bytes against the
    budget (the old code could cut between the halves of a pair). `lit/utf8-len-upper`
    is gone: with no split pairs, `lit/utf8-len` is the exact size.
  - **Host forms.** On the JVM a character outside the BMP is a one-code-point
    `String` (a `Character` has 16 bits), a BMP character is still a `char`, and
    `(v/char ctx s)` takes either; in ClojureScript every character is a
    one-code-point string. `DaciteString`'s `CharSequence` indexes code points like
    `count`; `charAt` of a non-BMP character throws `:non-bmp-char`.
    `dacite.host` gets `cp-count`, `cp-offset`, `cp-subs`, `chars-of`, `char-at`,
    `code-point-at`, `code-point->char`, `one-char?`, `lone-surrogate-index` and
    `check-text!`.
  - **Hashes.** BMP-only strings and chars hash as before. A string with a
    character outside the BMP changes on the JVM (it was the hash of `?` per
    half); nbb changes too (it was U+FFFD per half) and now they agree. No wire or
    store-format version bump (draft, no values in the wild, as for #12 and #14).
  - **Tests and fixtures.** `fixtures/fuse-sl2/vectors.edn` gets `:strings` (emoji,
    mixed BMP and non-BMP, a BOM, the highest code point, and four strings whose
    page ends inside non-BMP characters), non-BMP chars in `:scalars`, `:utf8`,
    `:invalid-utf8` and `:rejected-strings` (lone surrogates), checked by the JVM
    (`unicode_test`), nbb (`npm run peer:test`, 0.2 s) and `reference.py` (which
    computes the string hashes from the code points on its own).
    `dacite.string-vectors/problems` is the shared checker. `unicode_test` also pages
    one text at ten page budgets and checks `count`, `nth`, `slice` against the
    JDK's `codePoints`, cuts a run of 4-byte characters at every offset, and checks
    the wire. The `utf8-len` property now generates code points (not raw UTF-16
    units); the `utf8-len-upper` test became a test that runs cut at code points add
    up. Browser bundles are rebuilt (cache busters api-10, explorer-24, library-12).
- **Fix #14: the fuse is a product in SL(2, F_p) (hash version 2, store format 2).**
  The old fuse (a 4x4 unitriangular matrix over Z/2^64) is a nilpotent group, so
  collisions come in families: for any `a`, `b`, `x`, `y`, `z`, the sequences
  `x a b y b a z` and `x b a y a b z` had one hash. A state is now a 2x2 matrix
  over F_p with determinant 1, with a **256-bit prime**
  p = 75509794529310452520362503714625764980405239500720831432010267419866053829067
  (p - 1 = 2 * 3 * a 253-bit prime; p + 1 = 2^2 * a 254-bit prime; found by
  `fixtures/fuse-sl2/find_prime.py`). The design, with the prime, both
  factorizations, how to verify them, the security analysis and the reasons, is
  `docs/design/fuse-sl2.md`.
  - **Security: the margin is about 2^128, one attack, no proof.** Words in the
    upper-triangular subgroup collide by a birthday search at about
    60 * sqrt(p) multiplications (found and measured by Grok Bot in the review
    of this change at 2^20 to 2^32; 2^36 and 2^40 added here), not the ~p of the
    subgroup search this change first measured: a 127-bit prime would have given
    only ~2^70. So p is 256 bits (~2^134 for this attack; the 32-byte ref is
    bounded by 2^128). `fixtures/fuse-sl2/attack/` has the code and numbers;
    `small-prime-collision.edn` is a 36-bit collision that `hash_test` replays.
    **An outside cryptographic review is required before Dacite trusts hashes
    from untrusted peers** (roadmap, "Review gate").
  - **Every value hash changes.** The 256 byte generators are SL(2, F_p)
    matrices derived from the old byte table's seeds (label
    `dacite/fuse-sl2/v2`), each of order at least 2^252 (exact test,
    `dacite.sl2-table`). A ref is still 32 bytes: the digest
    `a xor rotl64(b) xor rotl128(c) xor rotl192(d)` of the state's four 256-bit
    cells. A ref enters a fuse as the product of the generators of its 32 bytes
    (`ref->state`, memoized in a cache that is emptied when it holds 65,536
    entries). `value_hash = digest(fuse(type_state, data_state))`. A HAMT bitmap
    node is `digest(fuse(fuse(type_state, elements_fuse), fuse_bytes(bitmap_be8 ++ [split])))`.
    The low-entropy guard is gone (nothing to guard: `A^n` is the identity only
    at a multiple of the order, above 2^252).
  - **A measure keeps its full 128-byte state** (`elements-fuse`): on the wire a
    measure is **144 bytes** (was 48). A receiver checks each cell is below p and
    the determinant is 1; otherwise the decoder throws an ex-info with
    `:reason :bad-state` (message "bad fuse state"), and the peer item reader
    reports `bad-entry` with `:detail :bad-state`. A writer handed an invalid
    state throws `:reason :invalid-state`. Refs, stores, the HAMT key hash and hex
    are unchanged in shape (32 bytes).
  - **Versions.** `dacite.hash/hash-version` 2; `dacite.store/store-format` 2: a
    store written under 1 is refused, never read with wrong addresses. The wire
    chunk `version` and the `hello` versions are **not** bumped (draft; no values
    in the wild, as for #12): the format did change, and the first release with
    users must bump them.
  - **Portable, behind one boundary.** The kernel is five hex-in, hex-out
    functions (`dacite.hash/kernel`: `:state-mul`, `:bytes->state`,
    `:ref->state`, `:state->ref`, `:state-valid?`), built on `sl2-mul`,
    `sl2-inverse`, `sl2-valid?`, `sl2->bytes` and `sl2-digest-words` in
    `dacite.host` (`BigInteger` on the JVM, `BigInt` on nbb and in the browser
    bundles); nothing else reads a cell, so a WASM or native implementation can
    replace the arithmetic. `fixtures/fuse-sl2/vectors.edn` `:kernel` tests
    exactly that boundary (101 answers, also recomputed by `reference.py`;
    `dacite.kernel-vectors/problems` runs them on any implementation). Reduction
    is the host's `mod`; a Montgomery reference (R = 2^264) is in the tests and
    was not benchmarked.
  - **Cost (measured end to end, one core, this box; the first draft's "as
    fast" was wrong).** Building a value and hashing it, JVM, main -> 256-bit:
    3,000-entry map 186 -> 595 ms; 10,000-element vector 199 -> 598 ms; 100,000-char
    string 53 -> 180 ms; 500 KB blob 69 -> 687 ms; 500 assocs on a 1,000-entry
    map 77 -> 260 ms. nbb is about the same as main for collections (6.2 -> 6.5 s
    for the map) and faster for the blob (3.5 -> 1.3 s). A blob costs 1.01
    products per byte: `ft-from-run` used to measure every page twice (2.03),
    and `as-node!` and `as-digit!` now reuse the measure the page holds. A fuse step is about 1.7 us on the JVM and 1.9 us
    on nbb. An element of a sequence costs 31 products. The harness is
    `impl/clojure/dev/bench_ops.cljc`; the table is in the design doc. The JVM
    suite takes about 65 s (main: 46 s).
  - **Tests and fixtures.** The #14 collisions, through values and refs; the
    36-bit Borel collision; every string of length 0 to 5 over `abcde` distinct;
    the digits 0000 to 9999 distinct; associativity, identity and inverse; a run
    of one element squared to 2^40 against the identity and every shorter run;
    `A^n = I` only at a multiple of the order; the digest; state validation; the
    kernel boundary; the prime and both factorizations; `:bad-state` at the wire
    decoder, in a serialized measure, in the peer item reader and on nbb.
    `fixtures/fuse-sl2/reference.py` re-derives the table and checks every
    vector in pure Python. The wire (79 cases) and peer (51 cases) fixtures are
    regenerated; the HAMT test keys and the telescoped-bitmap fixture keys changed
    because their colliding hash groups moved; the 3000-char string series is rebuilt at
    1 KiB pages (a 4 KiB page holds it in one). The leaf-budget pins in
    `literal_limits_test` are new hashes, checked at both budgets; the property
    tests there run 10 samples per frame size (was 30) to stay near 3 s. Browser
    bundles are rebuilt (cache busters api-9, explorer-23, library-11).
- **Fix #12: a telescoping HAMT (peer-v1 wire change).** Keys whose hashes share
  their first 5-bit chunks made a chain of one-child `hamt/bitmap` nodes holding
  the same entries; two nodes of a chain with the same chunk had one hash, so a
  node was its own child. Now no bitmap node has one child, and each stores
  `split`, the index (0..51) of the hash chunk it branches on: the first chunk
  at which its keys differ. The shape is canonical: a node is the HAMT of its
  own key set, whatever the history of `assoc` and `dissoc` and whichever map
  holds it: the shape is a function of the key set, and equal entries (the same
  keys with the same values) give byte-identical nodes. `assoc` branches
  directly where two keys first differ; `dissoc` replaces a node left with one
  child by that child. Lookup reads chunk `split` at each node and compares the
  whole key hash at an entry. Two keys with one 256-bit hash are treated as one key (no
  collision bucket); distinct hashes differ at a chunk 0..51, so the last chunk
  is a split like any other. (An earlier commit on this branch folded the child's
  hash into one-child nodes; that is removed.)
  - **The bug hit ordinary data, not only forged hashes.** On the old code 1,142
    sequential i64 keys from 89891 gave a map whose `count` was right but whose
    `get` lost 2 keys and whose `seq` overflowed the stack, and a 4-key set
    (9422, 19429, 22349, 25338) had a different hash for each insertion order and
    `get` found none of the 4.
  - **Value hashes of correct maps do not change.** A map's hash is the fuse of
    its entries in key-hash order, not of the tree. Checked against main
    (`d1a0455`) on 20 maps of 1 to 2,000 string and i64 keys: 20 of 20 equal.
    A map the bug corrupted can change: the 4-key set had one hash per insertion
    order on main and has one hash now, equal to neither. The 1,142-key map
    kept its value hash (only its tree was corrupt, its fuse was not).
  - **Every `hamt/bitmap` node hash changes.** It is now
    `fuse(node_hash(type, elements_fuse), fuse_bytes(bitmap_be8 ++ [split]))`. In
    the same 20 maps no bitmap node kept its hash (entries, scalars and value
    hashes did). A map's root node hash changes, so a stored map's node
    addresses change. Maps of 500 or more keys have about 0.3 to 0.7% fewer
    nodes (the removed one-child nodes).
  - **Wire bytes change: `hamt/bitmap` gains one byte.** The layout is
    `measure, bitmap u32, split u8, n u8, children Hash32 x n` (was
    `measure, bitmap u32, n u8, children`); an item with two children is 157
    bytes, was 156. A receiver checks the hash, `split` in 0..51 and
    `n >= 2` with `n = popcount(bitmap)` (at split 51, exactly bits 0 and 16),
    and, for an entry, `key-hash = key-ref`, from the node's own bytes
    (`bad-hash` or `bad-entry`). The rule that a child's split is greater than
    its parent's is checked when the tree is walked (`bad-hamt-node`), not when
    nodes arrive: a node does not know its parent. Every other node and every value hash is as
    before. An old peer and a new peer reject each other's bitmap nodes;
    peer-v1 is a draft.
  - **Persisted stores carry a format tag.** One constant,
    `dacite.store/store-format` (1), is written when a file, LMDB or IndexedDB store
    is created and checked when it opens: file `{base}/FORMAT`; LMDB meta key
    `format`; IndexedDB `meta` key `format` (written in the schema upgrade). A store
    with nodes and no tag (an older build's), or another number, is refused with
    `ex-data {:reason :store-format-mismatch :expected :found :where}` instead of
    failing a node-hash check; rebuild it from its source data (a store the bug
    corrupted keeps its cyclic nodes). The wire and `hello` versions are not
    bumped (draft). An older file store's bitmap nodes lack `:split`; an older
    LMDB or IndexedDB store holds the old byte layout.
  - Every `hamt/bitmap` literal now round-trips to its own hash, so an inner
    HAMT node no longer falls back to a node item for that reason.
  - Tests (store tags: new store tagged, reopen passes, untagged and wrong-version
    refused, on the JVM and in the idb suite): forged-chunk pairs `[1 1 2]/[1 1 3]` and `[1 2 1 4]/[1 2 1 5]`, the
    issue's repro, a property over assoc/dissoc histories (same key set, same
    root bytes and node set; no node ever stored with one child), the last-chunk
    case, tampered `split`, bitmap and shape, a forged store with splits out of
    order, and a wire fixture (`chunk-node-hamt-bitmap-telescoped`).
- **`pack/default-budget` is 4 KiB** (the leaf page size; HTTP and peers), tested
  `(>= default-budget lit/page-budget)`. Durable stores (file, LMDB,
  IndexedDB) keep 1 KiB as `chunk/durable-budget`, a constant of their own.
- **M1.1a: page and literal sizes (PR #9, Fixes #8).** A leaf page is 4 KiB
  (`lit/page-budget`), counted in wire bytes: a string or blob its length + 5,
  other single elements their body and a tag, a run or repeat its header
  (never below the wire size). A literal is at most one frame payload; a blob
  inside a literal page body now works (#8); a string or blob is read inside
  its page (`v/nth` touches one element); `encode-value` caches a literal per
  hash and cap, reports node items over one frame (`:oversized`), and
  `frame/min-max-frame` is 8192. Page building is linear (`lit/append-item`,
  an incremental page size kept per thread, an allocation-free `lit/utf8-len`),
  and encoding a 100 KB blob takes 2 s, not 107 s. Value hashes do not change.
- **M1.1b: a node bound replaces the literal gate.** `dacite.store.bound` bounds
  the wire size of a node's literal from the node alone (a sum with no run or
  repeat compression, kept in memory per call; never stored, sent or hashed).
  A literal is built only when its bound is at or under the cap, and then it
  fits. `pack/literal-gate` is gone. Value hashes do not change.
- **Peer protocol v1 draft (M1.1)** — `docs/spec/peer-v1.md`. Every peer
  message is a small Dacite value: a control message is the wire-v1
  literal of a string-keyed map, with no item header, hash or store
  entry. A 6-byte frame envelope adds a message type, flags and a u32
  stream id that the requester picks and that is never the hash (the
  protocol side of N1). Frames are at most 16 KiB, the RFC 8831 limit
  for senders without message interleaving. An item stream is wire-v1
  items packed whole into frames: a node item, or a literal when that
  is shorter and fits one frame payload (16 378 bytes), so a literal
  never spans frames. Every item is verified from its own bytes and
  nothing waits for a child (`dacite.peer.items`). Messages: `hello`,
  `get`, `item-stream`, `ack`, `release`, `cancel`, `error`.
  Senders cut free text to 256 UTF-8 bytes (`msg/cut-text`); integers are
  within ±(2^53 − 1) on both hosts; `msg/negotiate` combines two `hello`
  values. A collection header goes first, is stored on arrival, and has its
  hash, count and size recomputed when its root arrives (a bare-leaf root
  included). v1 assumes good actors: branch nodes are not bound to their
  children (spec section 11).
- **Tests:** no test over about 3 s without a `JUSTIFICATION` comment (`CONTRIBUTING.md`, `dev/time_tests.clj`); `pack/host-string` walks leaves once (the JVM suite: about 261 s to about 45 s).
  `dacite.peer.frame` and `dacite.peer.msg` are portable `.cljc`.
  `fixtures/peer-v1/` holds 50 golden cases made by the real encoder
  (`clojure -M:dev -m gen-peer-fixtures`), checked on the JVM and on nbb
  (`npm run peer:test`). New public helpers: `bin/item-header`,
  `ft/page-body-measure`.
- **Reveal order and interest** — `dacite.reveal` names the pages a default
  reveal would send, and the share of the ceiling each open reveal gets.
  The share is `R_max * I / max(peak, sum I)`. Above one peak the shares
  fill the ceiling. At one peak and below, the clock integrates the
  exponential, so a lone reveal decays from the open. Below the floor a
  reveal pauses. Cancel drops its share at once and removes the reveal.
  A session clock spends those shares in wire-item bytes, one whole page
  at a time, and reads the next page when it is about to be sent.
  Offering a page does not hold it. `ack-pages` records the hold, and
  `release-pages` queues a reset or evicted page again. A hash already
  held, or still offered, is skipped. Asking for a hash still open
  resumes the pages the client does not hold and restarts that clock at
  the peak. Credit already earned stays.
- **Reveal stream** — `POST /reveal` on the Jetty handler, over HTTP/3
  and HTTP/1.1. The body is EDN `{:hash "<64 hex>"}`. The response is
  `application/vnd.dacite.item-stream.v1`: concatenated wire-v1 node
  items, no chunk envelope, and no `Content-Length`. One connection
  keeps one `dacite.reveal` session. On HTTP/3 that connection is the
  QUIC session, not the request stream. A stream that finishes
  acknowledges the pages it wrote. A stream that fails cancels the
  reveal and releases those offers. There is no client ack or release
  message yet. `remote-store` opts into the stream with `:reveal` and
  keeps one HTTP/3 connection; the default read is still pack
  `GET /node/{hex}`. A second POST on that connection, while the
  first response is still open, is a focused child: the parent stays
  open, and a hash either stream has offered is not sent again.
  `GET /events` stays up while that parent and child are both open.
  A second POST for a hash that is already open waits on that reveal
  and does not replace the stream that is writing it. A focused read
  waits for pages still in flight on another reveal of that connection. A reveal that
  pauses, or that cannot cover its next page, ends the response and
  stays open so a later request resumes. That re-ask keeps credit
  already earned and the remainder of the clock it replaces, so a page
  larger than one budget is covered across asks. The 20 second bound is
  idle time with no new page. A lone decay can send at most `r-max * tau`
  bytes; the default `tau` is `1.0e6`.
  `peak`, `tau`, `floor`, and `r-max` are server
  placeholders. A missing hash is 404. A malformed body is 400.
- **IndexedDB store** — `dacite.store.idb` is a page-local dictionary.
  `idb-store` is the content store. `open` is a rooted store with the
  same pack-literal flush as `s/file`. Methods suspend until IndexedDB
  completes, and `drive` replays the operation. `s-merge` writes its
  batch in one transaction. The cache keeps a bounded number of nodes
  (1024 by default; `{:cache-limit 0}` does not evict). A hit stays
  until it is the oldest past that limit. A miss is remembered only for
  the current `drive`, so the next drive sees a put from another tab or
  worker. One browser `drive` covers this store and HTTP together, so
  the page can put IndexedDB in front of the remote store.
- **Asynchronous `IStore`** — every store method returns a promise.
  `s-get` fulfills with nil when the hash is absent. A required read
  fails as `:missing`. A store that cannot answer rejects as
  `:unavailable`. The JVM and babashka block inside `await`, so
  `v/nth`, `v/get`, `v/conj`, and `v/seq` still return values there.
  ClojureScript walks still replay through `drive`.
- **HTTP/3 server** — `dacite.service` is Jetty. Cleartext HTTP/1.1 is
  still `base-url`. HTTP/3 listens on UDP at that same port number and
  responses carry `Alt-Svc: h3=":<port>"`. The certificate is a local
  self-signed keystore (`localhost` and `127.0.0.1`). Pack GET still
  sends `Content-Length`. Java 21's `HttpClient` has no `HTTP_3`.
- **Browser `fetch`** — `dacite.store.browser` no longer uses synchronous
  XHR. `drive` runs a value op, reads `response.body` as bytes arrive, and
  reruns the op against the cached exchange. Todo, explorer, and library
  call `drive`, so a miss does not block the tab. Pack decode still waits
  for the full Content-Length body.
- **1k literal pages** — `ft/digit` and `ft/node` are pages of about
  1,024 payload bytes: a sequence literal (`run` / `repeat` / nested
  lits / `ref`) of the direct children. Collection value hashes are
  unchanged (`elements_fuse` of the logical leaves). Strings and blobs
  pack a book without one store entry per character. Wire-v1: nested
  `ref` (`0x52`); packed digit/node nodes use `n = 0` plus a Lit body.
  A u8 run payload is a copied byte buffer, measured by fusing the 256
  `u8` scalar hashes in place.
- **`s/lens`** — a lens over a rooted store at a path. Same `root` /
  `cas-root!` / `v/root` API; the document cell is unchanged. Client
  convenience (hygiene in one process), not an HTTP path. `path` is a
  Dacite vector of keys (or a host seq). Empty path is the original
  store. Nested lenses concatenate. Over `s/remote` the lens still
  CASes the document hash.

## [0.2.0-alpha] — 2026-09-14

### Public API (breaking, alpha)

- One constructor family: `(v/vector ctx …)`, `(v/map ctx …)`, `(v/i64 ctx n)`,
  … — context is a store, `v/root`, or another value. Dropped `*-via`,
  `*-with-store`, and implicit `*store*` constructors.
- `v/type` and `v/hash` replace `v/value-type` and `v/dacite-hash`.
- Root handle: `v/root`, `v/deref`, `v/swap!`, `v/cas!`, `v/swap-info!`,
  `v/add-watch`. Dropped `ref-` prefix and `ref-reset!`.
- Dropped `v/as-str` (use `v/native`). `dac->clj` / `clj->dac` stay on
  `dacite.convert`, not `dacite.value`.
- Application stores are rooted: `s/mem`, `s/file`, `s/lmdb`, `s/remote`.
  Apps should not call `IStore` (`s-get`, `s-snapshot`, …).
- Removed `dacite.core` and the `dacite.value.api` alias. Require
  `dacite.value` + `dacite.store` only.
- Examples live in `impl/clojure/src/dacite/examples/` (compiled and tested
  with the library). They open stores with `s/mem` / `s/file` / `s/lmdb` /
  `s/remote`.
- `v/slice` replaces `v/subvec`: `[start, end)` of a vector, string, or
  blob, same type, shared leaves.
- **`dacite.examples.library`** — public-domain catalog: `epubs` / `books`
  sets (tables), `indexes.title` vector (sorted records). Display pages use
  `v/nth` per character (not `v/slice` of the reading string); shelf uses
  `v/slice` on the title index. Duplicate file ingest is identity.
  Browser reader at `/app/library/` (shelf → TOC → page).
- **`dacite.store.chunk`** — persist pack Layer-1 items (`encode-reachable`
  at budget 1024) in an inner store; hydrate on `s-get`. **Default for
  `s/file` and `s/lmdb`:** constructors write an overlay; root commit
  flushes pack items. Raw `file-store` / `lmdb-store` stay exploded.
  Seed catalog flushes to 48 entries vs ~365 live exploded.
- **`dacite.bench.library-storage`** — file + LMDB size of the seed catalog
  (`snapshot` / `gc-live` / pack-literal flush). Fair live-vs-chunked ratio
  is ~16× on file data and ~15× on LMDB used pages; the ~380× file figure
  is snapshot debris + hex names. `clojure -M:library-storage`.

### Documentation

- Book inverted for app authors: [The Dacite way](docs/book/the-dacite-way.md)
  and [Anatomy of a Dacite app](docs/book/building/anatomy.md) lead;
  tutorials teach patterns; cookbook; chapters 1–4 plus pack/HTTP are
  How it works / reference. Roadmap (2026-08-31): then hold for a real app.

### Value explorer

- **`dacite.examples.explorer`** — web tree of the current rooted-store
  root. Expand/collapse vector, map, and set; strings/blobs are truncated
  leaves. Empty root CAS-seeds a type gallery; an existing root is
  browsed as-is.
- `GET /app/explorer/` (todo stays at `/app/`). Directories under `/app/`
  serve `index.html`.
- Browser explorer uses the same pack-filled `GET /node` as todo
  (realized literals, write-back cache). Values are walked locally;
  HTTP ships neighborhood *data*.
- Browser ClojureScript does not re-export `v/scalar` / `v/root`
  (they smash the `dacite.value.scalar` / `.root-ref` namespaces). nbb
  and JVM keep the vars. Rooted re-exports on `dacite.store` are stubs
  in the browser (nbb still requires at call time).
- Tutorial: [docs/book/tutorial/explorer.md](docs/book/tutorial/explorer.md)
- `/app` and `/app/explorer` without a trailing slash **301** to the
  slashed URL so relative `js/main.js` is not the todo bundle.

### Pack GET

- Removed `GET /node/{h}?raw=1` (bare node) and `?nodes=1` (node-only
  pack). GET always returns a pack-filled chunk. `pack-under` with
  budget 0 is a single item (the asked hash).
- Removed `GET /node/{h}?near=` and `store/*pack-near*`. Fill is always
  under the asked hash. Long-string expand is one neighborhood GET via
  `run`/`repeat`, not a parent query param.

### Pack literals (leaf-chunking 2f)

- Layer 1 uses cached **`size-bytes`** (not wire-item size) to choose
  `:literal` vs `:node`. Dry-run hash still required. Layer 2 still seals
  on sent bytes with include-then-seal (~2× cap on non-first items).
- Sequence literal bodies collapse contiguous same-type leaves to nested
  **`run`** (`0x50`) and all-equal **`repeat`** (`0x51`). Item type stays
  the store type (`ft/node`, `vector`, …). Char runs are UTF-8; a 24-`x`
  finger-tree node is a `repeat`, not 24 tagged chars.

### Pack fill (leaf-chunking 2e)

- `pack-under` / `pack-items` seal on **sent** bytes (wire-v1 by default),
  not EDN length of hash words. A 1893-char string GET now BFS-fills ~1k
  of FT/char neighborhood instead of stopping after two spine nodes.
- Literal gate is **≤ 1024** wire bytes per item. Include-then-seal can
  still push a chunk toward ~2k. Remaining-budget skip is deferred.
- Bottom-level `ft/node` of 2–32 leaves rematerializes via `make-node!`
  (not conj-right), so a 24-char node is a small literal instead of an
  856-byte `:node`. After a fat `:node` crosses 1k, BFS continues
  **children-first** up to ~2k so a 64-char string prefix fits in one GET.
### Todo web: long titles

- Pack `host-string` no longer uses `apply str` (CLJS apply of a long
  character seq can throw or stop around 52 args).
- `encode-item` falls back to `:node` if building a literal throws.
- Todo UI shows Add/Toggle/Remove errors instead of failing silently.

### HTTP inbound throttle

- **`dacite.service.throttle`** — per-client token bucket + inflight cap
  so one caller cannot starve another. Empty bucket is **429**
  (`Retry-After`), oversized body is **413**, global slot exhaustion is
  **503**. Client key is `Authorization: Bearer` or remote IP.
- `POST /nodes/get` `:budget` and start lists are clamped.
- `remote-store` retries 429/503 and accepts `:token`.
- CLI: `--throttle off`, `--rate`, `--burst`, `--inflight`, `--max-body`,
  `--threads`.

### nbb LMDB store (optional)

- **`dacite.store.nbb.lmdb`** — named DBs `dacite` / `meta`, 32-byte hash
  keys, wire-v1 node payloads. Same on-disk layout as `dacite.store.jvm`.
  Requires `lmdb` built for **data format v1**
  (`LMDB_DATA_V1=true npm rebuild lmdb`). File-store remains the default.
- **`dacite.wire.binary`** — CLJS collection counts decode as JS numbers
  (not BigInt) so nbb can re-encode trees.

### Directory / blob sync

- **`dacite.examples.sync`** — directory tree of maps + blobs. `ls` does
  not realize file bodies; `cat` uses `v/as-bytes`. Identical files share
  a blob hash. `push` / `pull` use `store/sync-reachable!`.
- **`v/as-bytes`** — blob → host bytes; optional limit; `:dacite/missing`
  when the node is not in the store. CLJS returns a vector of 0–255 ints
  (SCI cannot seq a JS array).
- **`dacite.store.sync/sync-reachable!`** — copy a reachable subgraph
  (packed flush to remotes, per-node otherwise). Re-exported on
  `dacite.store`.
- **nbb `file-store` snapshot** — keys by hex so `s-snapshot` does not
  blow up on BigInt hash words.
- Tutorial: [docs/book/tutorial/sync.md](docs/book/tutorial/sync.md)

### Two-client live

- **`GET /events`** — SSE root announcements on `dacite.service`
- **`dacite.store.remote/watch-root`** — JVM SSE client
- **`v/swap-info!`** — CAS-retry with `{:value :retries}`
- Event log **`watch`** and **`contend`** — two remotes, no lost appends
- Tutorial: [docs/book/tutorial/two-client.md](docs/book/tutorial/two-client.md)

### Event log app

- **`dacite.examples.event-log`** — append-only credit/debit log + derived
  view. Page via `v/slice`; replay via `nth`. Bench shows one append
  adds a handful of nodes at n=100 and n=2000.
- Tutorial: [docs/book/tutorial/event-log.md](docs/book/tutorial/event-log.md)

### Versioned notes app

- **`dacite.examples.notes`** — notebook `{doc, history}`; restore reuses a
  historical doc hash; `diff` compares field hashes; `bench` shows a
  title-only edit adds fewer store nodes than a body rewrite.
- Tutorial: [docs/book/tutorial/notes.md](docs/book/tutorial/notes.md)

### Remote config app

- **`dacite.examples.config`** — same domain against a file-rooted store
  and `store/remote-rooted-store` (HTTP). Seed with `ref-cas!` from nil;
  CLI: `show` / `get` / `set` / `add-feature` / `watch`.
- **`v/native`**, **`v/native`**, **`v/pr-str`**, **`v/get-in`**,
  **`v/assoc-in`**, **`v/update`**, **`v/update-in`** — field and path ops
  that stay on Dacite values (not `dac->clj`). `as-str` is `native` then
  stringify. `*string-char-limit*` / an optional limit realizes at most
  that many string characters (`native`/`as-str` throw if longer;
  `pr-str` renders `"prefix…" (n chars)`).
- **`ft-seq` flattens to leaves** — a 100-char string now realizes all
  100 characters (overflowed digits can mix leaves and `ft/node` cells;
  seq used to stop at 52).
- **`dacite.rooted/IRoot`** — local `RootedStore` and remote HTTP wrapper
  share `root` / `cas-root!`. Remote `set-root!` / `ref-reset!` throw.
- Tutorial: [docs/book/tutorial/config.md](docs/book/tutorial/config.md)

### Roadmap

- **Application-driven next phase** — [docs/roadmap.md](docs/roadmap.md)
  rewritten around writing apps that prove the thesis and pull library work.
  Sequence: remote config → versioned notes → event log → two-client live →
  directory/blob sync. `dac->clj` is documented as a JVM emergency hatch, not
  a portable API goal; specific “whys” (print, field access, path updates)
  get targeted value utilities instead.

### API cleanup (two public namespaces)

- **Public surface** is now `dacite.value` + `dacite.store` for application code
- **`*-via` constructors** — `(v/vector peer …)` allocates in the peer’s store
  (peer = Dacite value, root-ref, or `IStore`)
- **Value-level root-ref** — `(v/root rooted)` with `ref-deref` / `ref-swap!` /
  `ref-reset!` / watches; JVM also supports `@` / `swap!` / `reset!`
- **`dacite.store`** re-exports rooted ops and common host ctors (`file-store`,
  `lmdb-store` on JVM)
- **Deprecated** as app requires: `dacite.core`, `dacite.value.api` (alias),
  direct use of `dacite.rooted` / `value.scalar` / `value.collections` in apps

## [0.1.0-alpha] — 2026-07-30

First public alpha of the Clojure / SCI (babashka, nbb) reference library.

### Includes

- **Values** — content-addressed scalars, string, blob, vector, map, set
  (prefer `dacite.value`; older `value.api` / `core` remain as compatibility)
- **Stores** — `IStore`, mem, layered, LRU; file (JVM/bb + nbb); LMDB (JVM);
  rooted stores + GC; client cache (write-back); pack (`flush-from!`); rate-limit;
  stats
- **Wire-v1** — chunk-only binary codec (`dacite.wire.binary`, portable `.cljc`),
  fixtures under `fixtures/wire-v1/`, dual EDN/binary HTTP on the service
- **HTTP remote** — JVM and browser clients; pack GET/POST default to wire-v1 binary
- **Examples** — nbb todo CLI, browser todo demo, hash-parity script
- **Docs on dacite.io** — Install, Hello World (nbb), Values/Stores API reference,
  The Dacite Book

### Known limits (alpha)

- API may change before beta/1.0; pin a git tag/sha for experiments
- Browser remote uses **synchronous XHR** (demo only; blocks the main thread)
- Multi-tenant service productization, authz, and other-language ports are out of scope
- Clojars / Maven coordinates are not required for this alpha (git or local root)

### Wire notes

- Pack transport: `application/vnd.dacite.chunk.v1`
- Novelty PUT bodies, `/root`, and CAS remain EDN by design
- Pass `{:binary false}` on remote clients for legacy EDN packs
