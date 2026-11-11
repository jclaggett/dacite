# nbb reference parity

nbb is the reference implementation; the JVM remains as a performance target and must pass the shared checks.
The JVM is on the same tier as possible future C++/WASM implementations. The gap is JVM-only checks and code that still need a portable or nbb version.
Each line is tagged [done], [in progress], or [not started].

How to update: tick the item and add the PR number when it merges.

Workflow: parity work lands on the long-lived `parity` branch. Each checklist slice is its own PR into `parity` and ticks this file in the same PR. Main is merged into `parity` whenever it moves (no rebase). One final `parity` to main PR lands it all once the checklist is done.

Next up: the Core and data model ports, starting with `rooted_test` (15) and `rooted/gc_test` (5).

## Done

- [x] [done] Mem store on nbb. `npm run mem:test`. #50. `store/mem_checks.cljc`
- [x] [done] LRU store on nbb. `npm run lru:test`. #53. `store/lru_checks.cljc`
- [x] [done] IndexedDB, including a rooted swap. `npm run idb:test`. `store/idb_test.cljs`
- [x] [done] File, LMDB, and IDB durable contract on nbb. `npm run store:test`. #35. `store/nbb.cljs`, `store/nbb/lmdb.cljs`
- [x] [done] Reveal subset, 19 cases. `npm run reveal:test`. #38 #39. `reveal_cljs_test.cljs`
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
- [x] [done] `pack_test` (50), `chunk_test` (9), `lens_test` (13), `rate_limit_test` (7). `npm run store-units:test`, 70 checks: `store/pack_checks.cljc` (43), `store/chunk_checks.cljc` (9), `store/lens_checks.cljc` (12), `store/rate_limit_checks.cljc` (6), with `is` from `store/check.cljc`. Nine stay JVM only. Seven in `pack_test` and one in `rate_limit_test` start the HTTP service or read through `store/remote` (see the next line). `concurrent-disjoint-cas` in `lens_test` races two threads
- [ ] [not started] `store/remote.clj` and `store/reveal.clj` (HTTP/3). `remote_test` (3). `store/remote` throws off the JVM. Browser fetch is `store/browser.cljs`

## Core and data model

- [x] [done] Port `peer/items.clj` to `.cljc`. Node bytes are `dacite.reveal/encode-page` (the JVM `service.reveal/encode-page` delegates, same bytes). `peer/items.cljc`
- [ ] [not started] `.clj` only: `value/render.clj`, `value/serial.clj` (legacy codec; portable codec is `wire/binary.cljc`), `convert.clj`, `json.clj`
- [ ] [not started] JVM value tests: `core_test` (142), `value/*_test`, `value_test`. `wire/binary_test` past the bad-state check inside `peer:test`
- [ ] [not started] `hash_test` past fuse and kernel vectors. `unicode_test` past `string_vectors.cljc`. Those vectors run in `peer:test`
- [ ] [not started] `rooted_test` (15) and `rooted/gc_test` (5). `rooted.cljc` loads on nbb. Publish, query, and suggest on `dacite-roots` is spec only (#29)

## Peering

- [ ] [not started] Still no test: C06, C08, C24, C25, C42 (`peer-v1.md` section 13)
- [x] [done] `peer_items_test` (20). `npm run items:test`. `peer/items_checks.cljc`
- [ ] [in progress] `literal_limits_test.clj` (1 of 34). `node-items-over-a-payload-are-reported-by-default-and-throw-on-request` is in `peer/items_checks.cljc`. The other 33 need test.check, `store.bound`, `peer.msg`, finger-tree paging, `with-redefs`, or JVM clocks and threads
- [ ] [not started] 21 deftest and 2 defspec in `peer_v1_test.clj` stay on the JVM. Fixtures and framing run in `peer:test`
- [ ] [not started] `origin_test.clj` (3)
- [ ] [not started] Peering phase 2: ICE discovery for peer lists, after the roots layer (`docs/plan/peering.md`)

## Tests

- [ ] [not started] 17 `reveal_test.clj` deftest are absent from `reveal:test` (stall, re-ask, cancel, clock, encode-once, bounds)
- [ ] [not started] Example `*_test.clj`, `core_boundary_test.clj`, `dev/inspect_test.clj`, `bench/*_test.clj`
- [ ] [not started] `service_test.clj`, `service/reveal_test.clj`, `service/throttle_test.clj`, `json_test.clj`

## CLI and runtime

- [ ] [not started] JVM server: `service.clj`, `service/main.clj` (`clojure -M:service`), `service/reveal.clj`, `service/throttle.clj`
- [ ] [not started] JVM origins: `examples/peer_origin.clj`, `examples/library_origin.clj`. nbb peers: `peer_nbb.cljs`, `library_nbb.cljs`
- [ ] [not started] `examples/cards.clj`. nbb scripts already: hello, config, notes, log, sync, todo, library
- [ ] [not started] `webrtc:signal-test` is not in CI. No JVM `/signal` route
- [ ] [not started] JVM tools: `gen_fuse_fixtures`, `gen_peer_fixtures`, `gen_wire_fixtures`, `gen_byte_table`, `gen_sl2_table`, `inspect`, `time_tests`, `bench_cp`, `page_size_bench`, `page_size_degenerate`, `bench/library_storage.clj`, `bench/todo_bw.clj`
- [ ] [not started] Archived JVM client and server: `impl/clojure/archive`

## Hand checks and later

- [ ] [not started] Safari, Firefox, and Mac hand runs of the browser peer. Steps in `docs/plan/peering.md` M5.2. Chromium `peer:e2e` is in CI
- [ ] [not started] M5.2 Brave/Safari hand check: origin returned 500. The page continues only on 404 (`examples/peer_web.cljs` `origin-absent!`). The CI stub sends 404 (`scripts/peer-browser-e2e.mjs`)
- [x] [done] PR #47 is closed. Origin signaling spec, head `58a3b28`
