# Dacite Peering Project Plan (Draft)

Status: draft, Oct 30, 2026. Owner: Jonathan Claggett (design lead). Prepared by Grok Bot.
Baseline: repo `jclaggett/dacite` at HEAD `bc697c2` (571 JVM tests green, nbb `reveal:test` and `idb:test` green).
Inputs: `peering-design-note.md`, `streaming-design-note.md`, `review-2026-10-29-evening.md`, `reveal-review-checklist-evening.md`, `docs/roadmap.md`.

## 1. Direction in one paragraph

Narrow the reference implementation to one thing: a **peer**. Every Dacite client (nbb/Node and browser) is both a client and a server of immutable, hash-verified entries, and peers talk over **WebRTC data channels** using the wire-v1 pack format and the rate-based reveal protocol. A portable `.cljc` core (hashing, encoding, reveal policy) is shared by every host. The origin server stays the authority for root updates (CAS) and signaling. The JVM server and its tests remain a **regression baseline** until the nbb peer passes them; then the pure-Clojure JVM server may be retired.

## 2. Goals and non-goals

### Goals
1. One protocol and one implementation shape: peer = store tiers + reveal engine + transport.
2. A portable `.cljc` core with identical hashes and identical reveal decisions on JVM, nbb and browser.
3. A minimal nbb peer (file or LMDB store) and a browser peer (IndexedDB store), both serving reads.
4. WebRTC peering with signaling through the origin, plus admin-configured peers and mDNS for Node peers.
5. A demo (open a large value, read it through peers) and a cleaner dacite.io portal for people and AIs.
6. A documented, evidence-based decision on retiring the JVM server.

### Non-goals (for this plan)
- DHT or wide-area discovery without an origin.
- TURN relay support (direct-only until measured otherwise).
- Peers serving writes. Root updates and CAS stay with the origin.
- Access control beyond "serve any hash you hold" (tracked as an open question).
- QUIC/HTTP3 reveal client. The data-channel transport replaces it as the target; the existing HTTP/3 code stays as baseline, not new work.
- Python / C / C++ ports (the spec should make them possible, but we don't build them).
- Choosing final decay parameters or reveal-policy overrides (carried as open items, not blocking).

## 3. Team and roles

| Who | Role |
|---|---|
| Jonathan | Design lead. Sets the daily milestone, decides open questions, merges PRs. |
| Grok Bot | Design partner and reviewer. Morning design discussion, keeps roadmap and design notes current, runs the 4 PM ET weekday review and writes the checklist. |
| Dacite Programmer bot | Implements the daily milestone on a branch, opens PRs, runs JVM + nbb tests, addresses the review checklist. Never reviews its own PRs. |
| Doc/Spec bot (future) | Peer protocol spec, docs, dacite.io portal, live demo page, `llms.txt`. Starts after the spec draft exists (see section 9). |
| Projects Manager bot (proposed) | Mirrors the milestone list into the task tracker, flags slips. Optional; Jonathan to confirm. |

Review rule: a PR is reviewed by Grok Bot (not the author) before Jonathan merges. Jonathan's local Grok Build can take checklist items, but PRs still go through the same review.

## 4. Daily cycle

Times are ET. Jonathan's deep-work block is 8:45 to 11:45.

| Time | Step | Output |
|---|---|---|
| 8:30 | Grok Bot kickoff message (existing routine) | Today's context, yesterday's review status |
| 8:45 to ~9:30 | **Design discussion**: Jonathan + Grok Bot (+ Programmer bot as needed). Review roadmap, decide open questions, update design notes. | `docs/daily/YYYY-MM-DD.md` with the **daily milestone** (goal, acceptance tests, out of scope) |
| ~9:30 to 3:45 | **Dev**: Programmer bot works toward the milestone on a branch `m<phase>.<n>-<slug>`, small commits, PR opened as draft early. First action each day: address yesterday's checklist items marked "blocking". | PR, test results in PR description |
| 4:00 PM (weekdays) | **Review** (Grok Bot routine): review new commits and PRs against the milestone and design notes. | Review checklist (same format as `reveal-review-checklist-evening.md`: file:line, problem, suggested fix, verification) |
| Next morning | **Address**: Programmer bot (or Grok Build) works the checklist; Jonathan skims it during the design discussion. | Checklist items ticked, in the next day's PR |

Rhythm by weekday:
- **Tue and Wed: Dacite dev days.** Jonathan is fully in the design loop. Schedule milestones that need design decisions here (protocol, signaling, browser peer).
- **Mon and Fri.** Lighter design time. Mon: weekend review and week plan. Fri: week retro, update the roadmap and this plan. Use for mechanical milestones (extraction, parity, fixes).
- **Thu: docs plus outreach.** Jonathan works on docs and advisor/NSF outreach. Dev bot continues on a mechanical milestone; Doc/Spec bot's day once it exists.
- Slip rule: if a milestone isn't done, the next day's milestone is the remainder. Do not compress later days.

Working files in the repo: `docs/roadmap.md` (current direction), this plan (moved to `docs/plan/peering.md`), `docs/daily/` (one file per day), design notes under `docs/design/`.

## 5. Phases and milestones

Each milestone is sized to about one working day. Dates are targets starting Mon Nov 2 and assume no slips. Thanksgiving (Thu Nov 26) and the following Friday are treated as buffer.

### Phase 0: Baseline and setup (Mon Nov 2)

**M0. Baseline, branches and cycle in place.**
- Tag the baseline (`regression-baseline-bc697c2`). Record test counts and run commands in `docs/plan/baseline.md`: `cd impl/clojure && clojure -M:dev:test` (JVM), `npm run reveal:test`, `npm run idb:test`.
- Programmer bot has a checkout, an authenticated `gh`, and can run all three suites. PR template with "milestone", "tests run", "checklist items closed".
- Review routine running at 4 PM ET weekdays.
- Acceptance: the Programmer bot opens a no-op PR and the 4 PM review produces a checklist for it. Baseline numbers written down.

### Phase 1: Peer protocol spec (Tue Nov 3 to Wed Nov 4)

**M1.1. Messages and framing.** `docs/spec/peer-v1.md` draft: roles, one data channel for control plus one per request stream, message set (`hello`, `get`/reveal, `item-stream`, `ack`/`release`, `cancel`, `error`), framing on top of wire-v1 items, max message size (chunk to a safe SCTP size).
- Acceptance: every message has a byte layout or EDN shape, an example, and at least one golden fixture in `fixtures/peer-v1/`. Jonathan signs off on the message set.

**M1.2. Verification, rates and failure rules.** Per-entry hash verification before store, "bad entry ends the session", how peer rates share the client's `R_max` and token buckets, flow control (`bufferedAmount`), idle timeouts, explicit client ack and release (this closes the wire gap in N7/N8), read-only rule, stream ids that are not the hash (fixes the N1 design flaw).
- Acceptance: a conformance list of at least 20 numbered "MUST" statements, each mapped to a planned test. Open questions 1, 3 and 4 from the peering note get a decision or an explicit "deferred".

**M1.1a. Tighten literalizing and serializing.** Scheduled right after M1.1 (PR #7) merges, before M1.2. Source: Grok Bot's read-only investigation of Oct 31, 2026, checked against `main` @ cda5850 and PR #7; its findings and file:line references are reproduced in the items below. Paths are under `impl/clojure/src/dacite/`. The question was whether wire-v1 can carry internal finger-tree nodes as literals under a frame-sized cap. The answer is "partially yes": leaf pages are already lists of literals, and pack already picks literal or node per store node (`store/pack.cljc:556-590`, `:592-627`). What is missing is below. No code for this phase is in PR #7 except where noted.

1. **A serializer parameter for the largest literal.** Default 16 KiB, tied to the frame payload (`peer/frame.cljc` `max-payload`, 16 378 bytes; the whole literal item must fit, so 16 378 − 37 for the payload). It is separate from the pack chunk budget (`pack/default-budget`, a soft Layer-2 target) and from the leaf page budget (`lit/page-budget`). Today `encode-item`  uses one `budget` for the first two, and nothing references the frame constants. *Partly in PR #7:* `dacite.peer.items` applies the cap by size-checking the finished literal item (`items/literal-bytes`) and passes a generous gate to `encode-item`. The parameter itself, on `encode-item` and `encode-reachable`, is this phase.
2. **Sizing stays on payload bytes for now.** `size-cue` (`store/pack.cljc:169`) and `clearly-oversized?` (`:181`) keep using the measure's `:size-bytes`, as a cheap pre-filter. The cap is checked on the encoded item after `literal-of`, through the existing `pack/wire-item-size` hook (`store/pack.cljc:91-97`). *Open design item:* add an "encoded byte size" node measure, and define how it is calculated (sum of the wire-v1 sizes of the page body, including the 9 bytes a 4-byte string costs on the wire against the 4 the cue counts; whether it is part of the measure fused into the hash, which it must not be, or a cached side value). Decide before any change to the node encoding.

   *Done in M1.1a (the bounded part):* a string or blob counts its length **+ 5** (`lit/string-wire-overhead`), so a page of tiny strings stays near its budget on the wire; `null` and `negative` count 1 (an element is never free). *Review of PR #9 (S1):* every other single element also counts its **one-byte tag** (a `char` two: tag and length), and every `run` or `repeat` its **6-byte header** (a `char` run 4 more, the text length), so the page size is never below the wire size and is exact for scalars and fixed-size runs (a test compares it with the encoder). Before, a page of alternating `u8` and `char` was 8 261 bytes, over the minimum frame; now the largest node item over the adversarial mixes (alternating one-byte kinds, in groups of 1 to 5) is about 4 200.

   *Two ideas from Jonathan for the exact measure, recorded in spec 3.2 with the same analysis; neither is built:*
   - **(a) A second measure beside `size-bytes`: encoded size, a sum monoid.** It gives an exact size cue for literal-or-node and for cutting pages. The sum is only an upper bound (a run or repeat spends its header once, so an element's cost depends on its neighbors; define it "as if each element were its own item"). If it joins the measure fused into the hash, every hash changes: a wire and hash bump, so for a later version. If it is only stored beside the measure there is no hash change, but +8 bytes per measure on every node, and it cannot be checked from a spine page's bytes (like count and size, Q1).
   - **(b) The literal encoding as an in-memory cache, bounded by the cap.** Keep a node's literal bytes with the node while within the cap, and a "too big" mark above it (inherited by the nodes above, so the mark is O(1)); "shorter wins" is then a comparison, not a build. **The literal is never stored in any entry, never on the wire, never hashed; it is a cache only.** An in-memory store and a durable one therefore differ: the durable store rebuilds or lazily computes the cache on read (a miss costs time, not correctness). No wire or hash change; content addressing means no invalidation. Costs: memory (up to 16 KiB per cached node, and a literal at each level repeats its children, so bound it by size or an LRU and keep the lower levels), and the different cost profile of the two stores. The worry of bloating spine nodes does not arise when the cache is in memory only.

   No implementation in M1.1a.
3. **Fix `lit/item-payload-bytes` and `scalar-payload-bytes`** (`value/lit.cljc:328-378`). A `run` or `repeat` of `string` or `blob` counts 0 bytes (`:364-375` falls to `scalar-payload-bytes "string" nil`, `:348`; blob `:349`), so `try-append` and `try-prepend` (`value/finger_tree.cljc:314`, `:326`) let a page grow without bound: 99 453 bytes for 100 distinct 1 KB strings, 40 049 for 5 000 four-char strings. This is the likely source of the 39 753-byte page in the PR #7 fixture `item-stream-mixed` (formerly `item-stream-three-frames`). Count real sizes, then consider switching `try-append`, `try-prepend` and `split-body-at` to the encoded size.
4. **Fix issue #8** ([#8](https://github.com/jclaggett/dacite/issues/8)): add a `"blob"` branch to `intern-lit-item!` (`value/lit.cljc:294-313`, the `string` case is at `:310`). The page-body blob is a vector of ints, so convert to host bytes first (the verified sketch used `types/coerce-and-store!`). Tests for `[blob nil]`, `[blob blob]` and `[blob 1]`, run through `literal-of`, `literal-round-trips?` and `peer/msg encode-value`. When it lands, drop the map-value-only blob rule in spec 2.1 and the known issue in section 10.
5. **Decide the leaf page size.** `lit/page-budget` is hardcoded to 1024 (`value/lit.cljc:320`; used by `ft-from-run` `value/finger_tree.cljc:976`, `try-append`, `split-body-at` at `:500` and `:536`). Raising it toward 16 KiB changes golden fixtures and size-based tests, not hashes (`add-node!` hashes type and `elements_fuse`, `value/finger_tree.cljc:58-67`). Keep the spine at 32 refs a page, separate from the leaf budget. Trade-off: fewer nodes and requests against coarser dedup and a larger granule per `get`. The PR #7 fixtures `item-stream-*` are regenerated when this changes.

   *Decided (Jonathan, Nov 2): 4 KiB.* Data in `docs/notes/page-size-measurements.md`, reasons in `docs/design/leaf-chunking.md` 2g. Roughly 3.7 times fewer pages for a long string, 9% fewer total bytes, about twice the bytes a one-element edit adds; the largest node item is under 4.3 KB, which fits the new 8192 minimum frame. 16 KiB was ruled out: its pages are over one payload. No value hash changed (seven shapes compared with `main`, pinned by a test); inner page hashes did, so fixtures were regenerated.
6. **Peer frames: literal items and a capped `encode-value`.** *Done in PR #7 (Q5):* `frame.cljc` accepts `enc = 1` items, refuses a literal over one frame payload (`literal-too-large`) from its header, and packs whole items into frames; `dacite.peer.items/encode-value` writes streams under the literal rule; spec 3.2 documents it; fixtures `item-stream-literal-at-cap` and `-over-cap` pin the boundary. *Not done:* `peer/msg.cljc` `encode-value` (the control-message encoder) is not capped by itself. A control message is bounded by the string and set limits of spec 2.1 and by `encode-frame`'s `max-frame` check, so an oversized one fails at the frame, not in `encode-value`. This phase caps it at the source. *Also left here:* letting the JVM reveal service (`service/reveal.clj`, `reveal.cljc:268` `page-bytes`) emit literals for self-contained pages; optional, a page of leaves is about the same bytes.
7. **A property test** over shapes (string, blob, vector of scalars, of strings, of blobs, nested): every durable page and every emitted literal has an encoded size within the cap, and `literal-round-trips?` holds. Keep the investigation's measurement script (it builds 100,000-byte strings, blobs and a vector of 20,000 `i64` and reports node counts and the largest page) as a regression bench in `impl/clojure/dev/`. *Shorter wins:* PR #7's `items/encode-value` compares the literal with the node entries it replaces; moving that comparison into `encode-item` (`store/pack.cljc:578-590`) so HTTP pack and peers share it is part of this phase.
8. **The S3.3 clarification.** *Done in PR #7 (spec 3.3):* leaf pages (inline leaves, no `ref`) verify on their own, because the receiver recomputes `elements_fuse` from the leaves (`body-measure`, `value/finger_tree.cljc:267`). A spine node's hash checks locally against its claimed measure, but its measure depends on its children, whose measures its bytes do not hold. The spec states this and `dacite.peer.items/verify-item!` does exactly the local checks. What to do about the unchecked part (binding a spine page's measure to its children) is an open question for M1.2; see spec section 11.
9. **Cap node items at one payload (decision Q3).** After items 3 and 5 make every page at most about 4 KiB, the sender SHOULD keep each node item within one frame payload (`items/encode-value` checks it; a test builds the shapes of item 7 and asserts every node item fits). **Decided:** it reports by default (`:oversized` in the result) and throws (`:node-too-large`) only with `:strict-node-cap? true`. Receivers keep the 1 MiB `max-item`. `frame/min-max-frame` is raised from 1024 to **8192** (the largest node item, `frame/max-node-item-bytes` 5000, plus the 37-byte item header and the 6-byte envelope fit it: `frame/min-max-frame-fits-node?`), and the `hello` limits range in spec 6.1 says 8192..16384. A new fixture, `item-stream-node-spans-frames`, keeps the oversized-node-item case covered.
10. **Make reading one element cost one element.** `tree-nth*`/`scan-children` (`value/finger_tree.cljc`) call `page-child-hashes`, which interns every character of a 1 KB leaf page (a scalar hash and a store put each) to find one, so `v/nth` and `get` on any string or vector page cost about a page of work per element (about 1 ms a character). Find the element inside the page body, as `u8-page-nth` already does for bytes, and intern only that one child. PR #7 fixes only the worst caller: `pack/host-string` now walks the leaves once with `coll/seq-vals` (the JVM suite went from about 261 s to about 50 s).
11. **Do not rebuild the same literal repeatedly.** `items/encode-value` calls `literal-bytes` for the header and for every page while it chooses literal or node, and each call rebuilds the string. Cache `literal-bytes` per hash within one `encode-value` call (a map holding one string under 40 keys pays for it 40 times), or skip the attempt when the stored size cue already says the literal cannot win.
12. **M4 follow-up: delete only the failing headers.** *Decided:* when several headers wait on one root and a check fails, each header is checked on its own; the failing ones are removed and the honest ones stay (verified). The first failure still ends the session. Done (`items/resolve-pending!`, test `only-the-failing-headers-waiting-on-a-root-are-removed`).

**Node bound instead of a gate (M1.1b, built).** The sender no longer builds a value's literal to learn it is over the cap: `dacite.store.bound` bounds the literal's wire size from the node (sum of element sizes, no run or repeat compression, in memory per call, never stored, sent or hashed); over the cap, no literal is built; at or under it, the literal fits. `pack/literal-gate` is gone. The cost is compression of compressible values over the cap (they go as node items).

**Roadmap only, not built: the exact bound.** Carry each node's first and last run in the bound's summary and merge them on combine, so a run or repeat that spans pages is bounded by its real size; or give `literal-of` a byte limit that aborts when the output passes the cap.

**Status.** All twelve items are in PR "M1.1a" (branch `m1.1a-literals`, Fixes #8). Two things came up on the way and are fixed there because the 4 KiB page made them worse: building a page one element at a time was quadratic in the page budget (every append re-expanded and re-measured the page), so `lit/append-item` rewrites only the last group and `tree-conj-right!` combines the old page measure with the new element's (a 20 000-character text went from 15 s to 1 s to encode, 30 s to 1.7 s to receive; a blob of 300 KB from 25 s to 0.14 s); and a literal of a value held under many keys was rebuilt per key (`pack/*form-cache*`). Decided after review (Jonathan): `pack/default-budget` (the 1024 HTTP chunk budget) follows the leaf page size: 4 KiB as its own constant, with a test that it is at least `lit/page-budget`. It changes the layout of every pack chunk and many size tests, so it is its own PR (stacked on this one).

Acceptance: items 1, 3, 4, 7, 9, 10, 11 and 12 land with tests; items 2 and 5 are decisions written into `docs/design/leaf-chunking.md`; 6 and 8 are confirmed against the code. All suites pass, and the peer fixtures are regenerated if page sizes change.

### Phase 2: `.cljc` core extraction (Thu Nov 5 to Mon Nov 9)

**M2.1. Boundary inventory (mechanical).** List every namespace as `core` (pure `.cljc`), `host` (needs interop) or `server-only`. Today `reveal.cljc`, `hash.cljc`, `store.cljc`, `wire/binary.cljc`, `store/pack.cljc` are already `.cljc`; `service/reveal.clj`, `store/reveal.clj`, `store/remote.clj`, `service.clj` are JVM-only.
- Acceptance: `docs/design/core-boundary.md` plus a lint test that fails if a `core` namespace requires a host-only namespace.

**M2.2. Move the reveal engine's host-neutral parts into core.** Page ordering, interest and share math, session clock, session state, ack/release. Replace any JVM interop with `dacite.host` calls. No behavior change.
- Acceptance: all 571 JVM tests still pass; `reveal:test` (nbb) runs the same cases as `reveal_test.clj` for the pure functions.

**M2.3. Parity suite.** Turn the reveal and pack tests that are JVM-only into shared `.cljc` tests (or fixture-driven tests) that run on JVM and nbb. Add golden fixtures for reveal decisions (given a value and a clock, the page order and bytes are identical).
- Acceptance: one command runs the parity suite on both hosts and both report identical results; root hashes match across hosts (extends the existing `parity` example).

### Phase 3: Minimal nbb peer (Tue Nov 10 to Thu Nov 12)

**M3.1. Peer node with a transport abstraction.** `dacite.peer` in `.cljc`: store tiers (memory, local, peers, origin), a request handler for reveal (`have?` was removed from v1; see the peer spec, section 9), and an `ITransport` protocol. First transport is an in-process or loopback one, so protocol logic is tested without WebRTC.
- Acceptance: two peers in one process; B reads a value stored only in A through the peer protocol; entries verified; a corrupted entry ends the session (test).

**M3.2. WebRTC transport on nbb.** Adapter over `node-datachannel` (fallback `werift` if native prebuilds are a problem). Manual signaling (copy/paste or a local file) for now.
- Acceptance: two nbb processes exchange a 5 MB value over a data channel with flow control; transfer completes with correct hashes; runs on this box (Linux) and Jonathan's Mac.

**M3.3. Stores: file/LMDB and IndexedDB behind the same peer.** nbb peer serves from the nbb file or LMDB store; the same peer code runs against `idb.cljs` using `fake-indexeddb` in nbb tests.
- Acceptance: the shared store tests (get, put, has?, eviction, snapshot) pass for file, LMDB and IDB; a peer serves the library fixture from each store.

### Phase 4: Carry-over fixes from the evening checklist (Fri Nov 13 and Mon Nov 16)

Triage of `reveal-review-checklist-evening.md` (52 items). Items split by where the code lives after the new direction:
- **Fix in core or in the protocol (carry over):** N1 (stream identity), N2a/N2b (a reveal must end cleanly when paused or over budget), N3 (focused child gets its pages), N7/N8 (ack/release on the wire), N10 (`ack-pages` on unknown hashes), N11 (pages encoded twice), N12 (frame parser quadratic, trusts length), N14a-d (IDB), N15 (docs), and the open items on `:reveals`/`:sent`/`:owner` growth and decay parameters.
- **JVM-server-only (fix only if cheap, otherwise let retirement absorb):** N4 (thread reclamation), N5 (write lock held across a blocked write), N6 (unbounded request body), N9 (errors after the 200), N13 (10 ms polling), N16 (timing-dependent HTTP/3 tests). Exception: N6 and N16 get fixed if they make the baseline unreliable.

**M4.1. Core and protocol fixes, part 1.** N1, N2a, N2b, N3, N10, plus tests.
- Acceptance: new tests for duplicate request on the same hash, paused reveal ends without the 20 s timeout, lone reveal larger than `r_max * tau` completes, focused child receives pages already offered by the parent. Baseline still green.

**M4.2. Part 2.** N7/N8 ack/release implemented per M1.2, N11, N12, N14a-d, N15, bounded growth of `:reveals`/`:sent`/`:owner`.
- Acceptance: evicting-store test (get, evict, get again returns the node); failed-stream retry does not resend confirmed pages; frame parser linear-time test; docs agree with code.

### Phase 5: Browser peer (Tue Nov 17 to Thu Nov 19)

**M5.1. Browser build of the peer.** The same core and peer namespaces compiled for the browser, using `IndexedDBStore` as the local tier and the browser's `RTCPeerConnection` as an `ITransport`. Build tooling decision made here (see open questions).
- Acceptance: a static page loads, verifies a value from the origin into IndexedDB, and shows its root hash and entry count.

**M5.2. Browser to nbb peer end to end.** Manual signaling is acceptable.
- Acceptance: browser reads a value held only by the nbb peer; every entry verified in the browser; headless Chromium test in CI or on the box (Playwright), also run once in Safari and Firefox by hand.

**M5.3. Library reader through peers, with measurements.** The library app reads a chapter and an image via the nbb peer, with origin fallback on timeout.
- Acceptance: a measurements table (round trips, bytes from peer vs origin, time to first render) for origin-only vs peer-assisted, committed to `docs/plan/measurements.md`. This is the first proof point for the NSF pitch story.

### Phase 6: WebRTC signaling via the origin (Fri Nov 20 to Tue Nov 24)

**M6.1. Signaling protocol.** Offer/answer/ICE relay messages, peer announcement, network hints (public IP, subnet, site tag), authentication of the signaling channel. Added to `peer-v1.md`.
- Acceptance: spec section with sequence diagrams and golden messages; Jonathan decides open question 1 (peer lists by root or by locality).

**M6.2. Origin signaling endpoint.** Implemented where the origin lives today (JVM service) and, if the retirement plan needs it, mirrored in an nbb origin. WebSocket or long-poll.
- Acceptance: two browsers and one nbb peer find each other through the origin with no manual steps; a peer behind a different site tag is not suggested.

**M6.3. Peer selection and fallback.** Prefer Node over browser peers, LAN over WAN, low latency; cap concurrent peers; fall back to the origin after a short timeout.
- Acceptance: tests with simulated peers (slow, dead, corrupt, honest): the client always completes with correct data, bad peers are dropped, and the origin is used when no peer answers.

### Phase 7: Admin-configured peers and mDNS (Wed Nov 25, Mon Nov 30)

**M7.1. Configured peers.** Config file and origin-served config listing Node peers by address or site tag.
- Acceptance: a client with a config entry connects to that peer first and falls back correctly if it is down.

**M7.2. mDNS for Node peers.** Announce and browse `_dacite._udp`; verify with two Node peers on one LAN segment. Browsers still learn Node peers via origin or config.
- Acceptance: two nbb peers on the same LAN find each other with no origin; an announcement from another network is ignored. Document firewall and VLAN caveats.

### Phase 8: Demo and portal (Tue Dec 1 to Thu Dec 3)

**M8.1. Live demo.** A page on dacite.io that opens a large value (library) and shows, live, where each page came from (origin, Node peer, browser peer, local cache) and what it saved.
- Acceptance: loads on a clean browser profile, works with the origin only, and shows peer sources when a Node peer is running.

**M8.2. Portal restructure (with Doc/Spec bot).** dacite.io becomes: overview, live demo, spec index (wire-v1, peer-v1), quick start (run a peer in five commands), the Dacite Book.
- Acceptance: a new reader can run a Node peer from the quick start without help (Jonathan or a volunteer tries it cold).

**M8.3. AI-facing docs.** `llms.txt` and `llms-full.txt` at the site root, plus spec pages in plain markdown with stable URLs.
- Acceptance: a fresh AI agent given only `https://dacite.io/llms.txt` answers five scripted questions about peering correctly and can fetch a value through the demo.

### Phase 9: JVM retirement decision (Fri Dec 4 prep, Tue Dec 8 decide)

**M9.1. Parity report.** Run the full baseline against the nbb peer. For each of the 571 JVM tests: passes on nbb, ported to the parity suite, covered by another test, or intentionally dropped (with a reason).
- Acceptance: a table with no unexplained rows; the numbers are in `docs/plan/jvm-retirement.md`.

**M9.2. Decision meeting (design discussion on Tue Dec 8).** Retire when all hold:
1. nbb peer passes the parity suite and every baseline test that tests protocol behavior.
2. An nbb origin can do what the JVM service does for readers and root CAS (or the CAS authority is explicitly kept elsewhere).
3. Performance on the library benchmark is within an agreed margin (suggested: no worse than 1.5x JVM reveal time).
4. Peer-assisted demo and docs no longer reference the JVM server.
5. Jonathan accepts the loss of JVM-only features (LMDB v1 behavior, HTTP/3 server). Anything wanted is moved or filed as a non-goal.
- Outcomes: retire (archive `impl/clojure` JVM-only code, keep `.cljc` core and test fixtures), retire later with a dated checklist, or keep as a test oracle only.

## 6. Timeline at a glance

| Week | Mon | Tue | Wed | Thu | Fri |
|---|---|---|---|---|---|
| Nov 2 | M0 | M1.1 | M1.1a (right after M1.1 merges), M1.2 | M2.1 (Doc/Spec bot starts) | M2.2 |
| Nov 9 | M2.3 | M3.1 | M3.2 | M3.3 | M4.1 |
| Nov 16 | M4.2 | M5.1 | M5.2 | M5.3 | M6.1 |
| Nov 23 | M6.2 | M6.3 | M7.1 | holiday | buffer |
| Nov 30 | M7.2 | M8.1 | M8.2 | M8.3 | M9.1 prep |
| Dec 7 | week plan | **M9.2 decision** | | | |

About 24 working days, a decision on or near Dec 8. Phases 4 and 6 are the likeliest to slip. Buffer days: Nov 26 and 27, plus Fridays.

## 7. Acceptance tests that apply to every milestone

- Baseline stays green: JVM suite (571 tests), `reveal:test`, `idb:test`. A red baseline blocks merge unless the milestone explicitly retires that test.
- New code has tests that fail before the change and pass after.
- No wall-clock-dependent assertions in new tests (use the session clock; N16 is the cautionary example).
- **No slow tests.** A single test that takes longer than about 3 seconds is broken, unless it carries a written justification for why it needs that long (a comment on the test that starts with `JUSTIFICATION`). Setup and teardown harness time does not count; starting a server inside the test does. Measure with `clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -M:dev -i dev/time_tests.clj` (slowest first). Fix a slow test by building a smaller input, or by not paying for the same work twice, before reaching for a comment.
- The PR description lists tests run, milestone id, and checklist items closed.
- The 4 PM review finds no "blocking" items, or the open ones are on the next day's list.

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `node-datachannel` native build or prebuilds fail on some platforms | Node peer can't run everywhere | Try `werift` (pure JS) in M3.2; keep the transport behind `ITransport`; headless Chromium as an emergency fallback |
| nbb (SCI) performance is poor for hashing and encoding | Peer is too slow to be useful | Measure in M2.3 and M5.3; hot paths stay in `.cljc` so they can move to compiled CLJS or JS interop |
| Browser build tooling for `.cljc` isn't set up (nbb is interpreted, browsers need compiled CLJS) | M5.1 grows to several days | Decide the tool (shadow-cljs or similar) in M2.1; spike early |
| WebRTC flow control and message size limits differ across browsers | Stalls or dropped data | Cap message size in the spec (M1.1); test `bufferedAmount` handling in M3.2 and M5.2; test Chromium, Firefox, Safari |
| Reveal protocol is still moving (peer rates, token buckets, `R_max` agreement, decay parameters are open) | Spec churn after implementation | Spec only what's decided; mark the rest "experimental"; keep tuning parameters configurable |
| Retiring the JVM server removes the only server with root CAS, auth and HTTP/3 | Lose features | Retirement criteria in M9.2; an nbb origin is a prerequisite, not an afterthought; confirm what `docs/authorization.allium` requires |
| Malicious or buggy peers (bad data, resource exhaustion, probing) | Security, privacy | Verify every entry; drop on first bad entry; per-peer caps; read-only; privacy open question gets an answer before the demo is public |
| Browser peers are short-lived and quota-limited | Poor hit rate | Treat as opportunistic; prefer Node peers; opt-in default decision (open question 6) |
| Sunk cost: HTTP/3 reveal work (`POST /reveal`) | Wasted effort if the data channel diverges | Reuse the item stream and reveal engine; only the transport changes |
| Bot-driven development quality (Programmer bot cannot run cloud-agent coding loops under the current Cursor plan) | Slower, more review cycles | Small PRs, strict daily milestone, independent review; reassess after Phase 3 whether to upgrade the plan |
| Thin schedule around Thanksgiving and other commitments | Slips | Buffer on Nov 26 and 27; slip rule in section 4 |

## 9. When the Doc/Spec bot starts

- **Create it Thu Nov 5**, right after M1.2 produces the first `peer-v1.md` draft. Thursday is the docs day.
- **First assignments (Nov 5 to Nov 19):** clean the peer spec and wire-v1 cross-references, make the conformance list readable, set up the docs structure (`docs/spec`, `docs/design`, `docs/plan`), flag where docs and code disagree (e.g. N15a: the repo's `streaming-design-note.md` still has the old peering section).
- **Phase 8 (Dec 1 to 3):** owns the portal restructure, the demo page copy, the quick start, and `llms.txt`. It does not own protocol decisions; it asks Jonathan or Grok Bot.
- Until it exists, Grok Bot keeps the notes in sync during the morning discussion.

## 10. Open questions

For the next design discussions, roughly in the order they block work.

1. **Peer lists:** does the origin suggest peers by root or only by network locality? (Decide in M6.1.)
2. **Peer rates:** how do peer streams share the client's global rate budget and token buckets? (M1.2.)
3. **Privacy:** does asking a peer for a hash reveal what the client reads? Should a probing `get` be limited to known peers or a site? (Before M8.1 is public.)
4. **Access control:** may a peer serve any hash it holds, or only to clients authorized for that root? What does the authorization model (`docs/authorization.allium`) mean for peers?
5. **Browser peers:** serve by default, or opt-in? Quota policy?
6. **TURN:** direct-only, or an optional relay?
7. **Origin in the new world:** is the origin an nbb peer with an HTTP/WebSocket front end, with the JVM service as an optional alternate? Who is the CAS authority?
8. **Browser build tooling:** what compiles the `.cljc` core for the browser?
9. **Decay parameters, `R_max` agreement and reveal policy overrides:** carried from the streaming note; do we choose defaults now or measure first (M5.3)?
10. **Naming and packaging:** an npm package for the Node peer (`npx dacite-peer`)? A container image for admins?
11. **Projects Manager involvement:** track milestones in the task tracker, or keep it all in the repo?
12. **Security of the Programmer bot's access:** repo scope and branch protection for the bot's `gh` credentials (suggest: PRs only, `main` protected, Jonathan merges).

## 11. Immediate next steps

1. Jonathan confirms or edits this plan (including the Mon Nov 2 start).
2. Programmer bot checks out the repo and does M0.
3. Grok Bot prepares the M1.1 design discussion agenda (message set, framing, stream ids) for Tue Nov 3, 8:45 ET.
4. Decide whether Projects Manager mirrors the milestone list.
