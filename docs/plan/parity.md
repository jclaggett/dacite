# nbb reference parity

nbb is the reference implementation; the JVM remains as a performance target and must pass the shared checks.
The JVM is on the same tier as possible future C++/WASM implementations. The gap is JVM-only checks and code that still need a portable or nbb version.
Each line is tagged [done], [in progress], or [not started].

How to update: tick the item and add the PR number when it merges.

Scope: parity only has to demonstrate peering parity. The model is that every communication is between peers; future work layers on top of that. The HTTP client/server API is legacy, because the peering protocol will be the only way to transmit data. Its items are under "Legacy HTTP (removed after parity)" and are not parity work; removing the HTTP code is a separate process after parity is achieved.

Workflow: parity work lands on the long-lived `parity` branch. Each checklist slice is its own PR into `parity` and ticks this file in the same PR. Main is merged into `parity` whenever it moves (no rebase). One final `parity` to main PR lands it all once the checklist is done.

Next up: a Peering or Tests item: more of `literal_limits_test`, or a test for one of C06, C08, C24, C25, C42. What is left of `core_test` is JVM only unless `convert.clj` is ported (its `dacite.convert` tests would come with it). `value/serial_test` (27) tests the legacy `.clj` codec and goes with `value/serial.clj` (see below). Legacy HTTP items are not next.

## Done

- [x] [done] Mem store on nbb. `npm run mem:test`. #50. `store/mem_checks.cljc`
- [x] [done] LRU store on nbb. `npm run lru:test`. #53. `store/lru_checks.cljc`
- [x] [done] IndexedDB, including a rooted swap. `npm run idb:test`. `store/idb_test.cljs`
- [x] [done] File, LMDB, and IDB durable contract on nbb. `npm run store:test`. #35. `store/nbb.cljs`, `store/nbb/lmdb.cljs`
- [x] [done] Reveal subset, 18 cases. `npm run reveal:test`. #38 #39. `reveal_cljs_test.cljs`
- [x] [done] Peer fixtures, client, flow, async, mem transport, cache. `npm run peer:test`. #19 #22 #23 #25 #28
- [x] [done] C05 and C07, and a mid-item or blocked end at the deadline. #55. `floor-sends-fin-partial-when-blocked-or-credit-runs-out` in `peer/flow_checks.cljc` (`peer:test`). `stream-ends-partial-when-interest-falls-below-floor` uses a 210-item value at a lower `recv-max` (about 0.7 s on nbb, was about 1.8 s on a late store)
- [x] [done] C09 `get-interest-is-clamped-by-the-responder`. #58. `peer/flow_checks.cljc` (`peer:test`)
- [x] [done] C11 `too-many-streams-is-refused-and-a-closed-slot-frees`. #58. `peer/flow_checks.cljc` (`peer:test`)
- [x] [done] C12 `max-streams-above-64-is-read-as-64`. #58. `peer/flow_checks.cljc` (`peer:test`)
- [x] [done] C32 `item-over-max-item-is-refused-before-buffering`. #58. `peer/flow_checks.cljc` (`peer:test`)
- [x] [done] Mid-item block at the deadline, and the max-streams cap. #58. `blocked-mid-item-ends-on-the-deadline-tick`. `frame/cap-max-streams` reads an advertised value above 64 as 64
- [x] [done] nbb WebRTC. `npm run webrtc:test`. #32. Room relay `webrtc:signal` #34
- [x] [done] Browser peer on Chromium. `browser:test` and `peer:e2e` #42 #45. `browser:e2e` #49. `library:e2e` #46
- [x] [done] CI runs the scripts above. `.github/workflows/test.yml` (#36 and later)

## Storage

- [x] [done] JVM `store_test.clj` (26) and `format_test.clj` (6). `npm run storage:test`, 32 checks on the nbb file and LMDB stores. `store_checks.cljc`, `store/format_checks.cljc`. The JVM part of the old `await-future-and-unavailable-test` stays JVM only as `await-future-test` (a JVM future is a JVM host promise); its rejected-promise half is the shared `await-rejected-is-unavailable-test`
- [x] [done] nbb `store/file` and `store/lmdb` (chunked, plus a root cell). Get, put, has?, reset, persistence, sharding, `write-batch`, wire-v1 node bytes, format tag, and the LMDB root cell are `storage:test`. The chunked overlay, and `store/file` and `store/lmdb` reopened from their root cells, are `chunk_checks.cljc` (`store-units:test`). On nbb `chunk/put-reachable!` built a set of BigInt hash vectors and threw; `:keep` is now a vector. JVM sources: `store/file.clj`, `store/jvm.clj`. Browser `store/file` and `store/lmdb` throw
- [x] [done] `pack_test` (50), `chunk_test` (9), `lens_test` (13), `rate_limit_test` (7). `npm run store-units:test`, 70 checks: `store/pack_checks.cljc` (43), `store/chunk_checks.cljc` (9), `store/lens_checks.cljc` (12), `store/rate_limit_checks.cljc` (6), with `is` from `store/check.cljc`. Nine stay JVM only. Seven in `pack_test` and one in `rate_limit_test` start the HTTP service or read through `store/remote` (see Legacy HTTP). `concurrent-disjoint-cas` in `lens_test` races two threads

## Core and data model

- [x] [done] Port `peer/items.clj` to `.cljc`. Node bytes are `dacite.reveal/encode-page` (the JVM `service.reveal/encode-page` delegates, same bytes). `peer/items.cljc`
- [ ] [not started] `.clj` only: `value/render.clj`, `value/serial.clj` (legacy codec; portable codec is `wire/binary.cljc`), `convert.clj`
- [ ] [in progress] JVM value tests: `core_test` (142), `value/*_test`, `value_test`. Done: `value/root_ref_test` (5), `value/root_ref_checks.cljc` (`rooted:test`); on nbb its `@r`, `swap!`, and `reset!` steps are `v/deref`, `v/swap!`, and `root-ref/ref-reset!`. `value/path_test` (9), `value/api_test` (5), and `value/finger_tree_test` (19): `value/path_checks.cljc`, `value/api_checks.cljc`, and `value/finger_tree_checks.cljc` (`value:test`). `value/collections_test` (22) and `value/unicode_test` (14): `value/collections_checks.cljc` and `value/unicode_checks.cljc` (`value:test`). `value/hamt_collision_test` (21): `value/hamt_collision_checks.cljc` (`value:test`), all shared; the JVM keeps test.check for the 4 properties, nbb runs them from a fixed seed with `value/seeded_gen.cljc` (20 cases each). Sharing it found an nbb bug in `peer/verify.cljc` (a split-51 bitmap compared as a Number). `value_test` (8 deftests; the 9 counted the `deftest` refer): `value_checks.cljc` (`value:test`); JVM only: the one-letter-String line of `scalar-validation` (no char type on JS). `core_test` (142), in part: `core_checks.cljc` (`value:test`) has its scalar construction and size tests (16 deftests), its store context and value identity tests (9: `with-store`, `v/hash`, `wrap-hash`, coercion), and the type line of `str-test`, `blob-test`, `vec-test`, `hash-map-test`, and `dacite-set-test` (their other line is `count` or `dacite.convert`); JVM only: the one-letter-String line of `char-test` (no char type on JS) and the equality line of `neg-test`; the other 112 deftests are native collection interfaces (`count`, `nth`, `get`, `conj`, `assoc`, `seq`, IFn, `equals`, `hashCode`, `toString`, CharSequence, Iterator, MapEntry) or `dacite.convert` (`.clj` only, below). JVM only: `api_test/parity-with-native-jvm-test` (native collection interfaces) and the vector line of `path_test/pr-str-renders-long-strings` (`value/render.clj`); in `collections_test`, 7 whole deftests and the native-interface and bounded-`toString` lines of 8 more (native collection interfaces, `value/render.clj`); in `unicode_test`, `charsequence-indexes-code-points`, `cp-count-and-cp-offset-match-the-jdk`, and `indexing-inside-a-page-is-not-a-hand-written-scan` (CharSequence, the JDK as oracle and timing baseline) and the BMP-char-is-not-a-String line of `a-character-outside-the-bmp-is-one-element` (no char type on JS).
- [x] [done] `wire/binary_test` (14). `npm run wire:test`. `wire/binary_checks.cljc`, all shared; the fixtures (`fixtures/wire-v1`) are host pieces in `env`. The bad-state check moved there from `peer:test`, which ran 3 of its 5 cases. Sharing it found an nbb bug in `wire/binary.cljc`: an i32 body decoded off by 2^32 - 1. On nbb a decoded u64 entry holds a BigInt where a live one holds a Number (as with the bitmap, #64); the round trip compares u64 data as a host word
- [x] [done] `hash_test` (26 deftest, 10 defspec). `npm run hash:test`. `hash_checks.cljc`, all shared. The 10 properties keep test.check on the JVM (100 trials each); nbb runs the same 100 cases from a fixed seed with `value/seeded_gen.cljc`. The fuse and kernel vectors moved here from `peer:test`. BigInteger calls are small helpers (BigInt on nbb, with a Miller-Rabin `probable-prime?` to the JVM's error bound); sets of states are keyed by hex on nbb; a hash word is an unsigned BigInt there
- [x] [done] `rooted_test` (15) and `rooted/gc_test` (5). `npm run rooted:test`. `rooted_checks.cljc`, `rooted/gc_checks.cljc`. The JVM checks use the atom conveniences of a rooted store (`@s`, `reset!`, `swap!`, `compare-and-set!`, `add-watch`, `set-validator!`); nbb runs the same steps with the function API (`root`, `set-root!`, `update-root!`, `cas-root!`, `add-root-watch`, `set-root-validator!`). Publish, query, and suggest on `dacite-roots` is spec only (#29)

## Peering

- [ ] [not started] Still no test: C06, C08, C24, C25, C42 (`peer-v1.md` section 13)
- [x] [done] `peer_items_test` (20). `npm run items:test`. `peer/items_checks.cljc`
- [ ] [in progress] `literal_limits_test.clj` (1 of 34). `node-items-over-a-payload-are-reported-by-default-and-throw-on-request` is in `peer/items_checks.cljc`. The other 33 need test.check, `store.bound`, `peer.msg`, finger-tree paging, `with-redefs`, or JVM clocks and threads
- [x] [done] `peer_v1_test.clj` (23 deftest, 2 defspec). `peer/peer_v1_checks.cljc` (`peer:test`), all shared; the fixture case directories, the spec text, and file reads are host pieces. JVM only: the ratio, char, date, and float values of `outside-the-profile` and the `Long/MAX_VALUE` and `Long/MIN_VALUE` lines of `s5-integers-are-within-2-to-the-53` (not nbb values). The 2 properties keep test.check on the JVM (150 and 50 trials); nbb runs 60 and 25 cases from a fixed seed with `value/seeded_gen.cljc` (`msg/encode-value` is about 10 ms a value there)
- [ ] [not started] Peering phase 2: ICE discovery for peer lists, after the roots layer (`docs/plan/peering.md`)

## Tests

- [x] [done] 17 `reveal_test.clj` deftest were absent from `reveal:test` (stall, re-ask, cancel, clock, encode-once, bounds). All 17 are `reveal_checks.cljc` (`reveal:test`), shared; the page encoder is a host piece (`dacite.service.reveal` on the JVM). Their nbb copies `floor-at-a-power-of-two` and `page-is-later-than-the-constant-rate` are gone. Sharing them found an nbb bug: a reveal of more than 8 pages threw, because the session keyed its sets and maps by raw hash (a set cannot hash a BigInt); they are now keyed by `host/hash-key`
- [ ] [not started] Example `*_test.clj`, `core_boundary_test.clj`, `dev/inspect_test.clj`, `bench/*_test.clj`

## CLI and runtime

- [ ] [not started] `webrtc:signal-test` is not in CI. No JVM `/signal` route. In scope: this is peer signaling, not the HTTP API
- [ ] [not started] JVM tools: `gen_fuse_fixtures`, `gen_peer_fixtures`, `gen_wire_fixtures`, `gen_byte_table`, `gen_sl2_table`, `inspect`, `time_tests`, `bench_cp`, `page_size_bench`, `page_size_degenerate`, `bench/library_storage.clj`, `bench/todo_bw.clj`

## Hand checks and later

- [ ] [not started] Safari, Firefox, and Mac hand runs of the browser peer. Steps in `docs/plan/peering.md` M5.2. Chromium `peer:e2e` is in CI
- [x] [done] PR #47 is closed. Origin signaling spec, head `58a3b28`

## JVM-only, outside peering scope

Neither HTTP nor peering: not parity work, and the code stays.

- [ ] [not started] `json.clj` and `json_test.clj`. `json.clj` is used only by its own test
- [ ] [not started] `examples/cards.clj`, which uses only store and value. nbb scripts already: hello, config, notes, log, sync, todo, library

## Legacy HTTP (removed after parity)

Not parity work: these stay unticked, and their code stays until the HTTP removal after parity.

- [ ] [not started] `store/remote.clj` and `store/reveal.clj` (HTTP/3). `remote_test` (3). `store/remote` throws off the JVM. Browser fetch is `store/browser.cljs`
- [ ] [not started] `origin_test.clj` (3)
- [ ] [not started] `service_test.clj`, `service/reveal_test.clj`, `service/throttle_test.clj`
- [ ] [not started] The JVM-only tests in `pack_test` (7) and `rate_limit_test` (1) that start the HTTP service or read through `store/remote` (see Storage)
- [ ] [not started] JVM server: `service.clj`, `service/main.clj` (`clojure -M:service`), `service/reveal.clj`, `service/throttle.clj`
- [ ] [not started] JVM origins: `examples/peer_origin.clj`, `examples/library_origin.clj`. The nbb peers `peer_nbb.cljs` and `library_nbb.cljs` peer instead
- [ ] [not started] Archived JVM client and server: `impl/clojure/archive`
- [ ] [not started] The browser peer's HTTP origin fallback (`examples/peer_web.cljs` `origin-absent!`), and the M5.2 Brave/Safari hand check: origin returned 500. The page continues only on 404. The CI stub sends 404 (`scripts/peer-browser-e2e.mjs`)
- [ ] [not started] #65: `service/reveal_test` `http3-reveal-shares-one-connection` flakes (HTTP/3); rerun when it fails
