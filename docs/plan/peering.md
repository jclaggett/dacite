# Dacite Peering Project Plan (Draft)

Status: draft, Oct 30, 2026. Owner: Jonathan Claggett (design lead). Prepared by Grok Bot.
Baseline: repo `jclaggett/dacite` at HEAD `a2b9638` (564 JVM tests green, nbb `reveal:test` and `idb:test` green).
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
- Tag the baseline (`regression-baseline-a2b9638`). Record test counts and run commands in `docs/plan/baseline.md`: `cd impl/clojure && clojure -M:dev:test` (JVM), `npm run reveal:test`, `npm run idb:test`.
- Programmer bot has a checkout, an authenticated `gh`, and can run all three suites. PR template with "milestone", "tests run", "checklist items closed".
- Review routine running at 4 PM ET weekdays.
- Acceptance: the Programmer bot opens a no-op PR and the 4 PM review produces a checklist for it. Baseline numbers written down.

### Phase 1: Peer protocol spec (Tue Nov 3 to Wed Nov 4)

**M1.1. Messages and framing.** `docs/spec/peer-v1.md` draft: roles, one data channel for control plus one per request stream, message set (`hello`, `have?`, `get`/reveal, `item-stream`, `ack`/`release`, `cancel`, `error`), framing on top of wire-v1 items, max message size (chunk to a safe SCTP size).
- Acceptance: every message has a byte layout or EDN shape, an example, and at least one golden fixture in `fixtures/peer-v1/`. Jonathan signs off on the message set.

**M1.2. Verification, rates and failure rules.** Per-entry hash verification before store, "bad entry ends the session", how peer rates share the client's `R_max` and token buckets, flow control (`bufferedAmount`), idle timeouts, explicit client ack and release (this closes the wire gap in N7/N8), read-only rule, stream ids that are not the hash (fixes the N1 design flaw).
- Acceptance: a conformance list of at least 20 numbered "MUST" statements, each mapped to a planned test. Open questions 1, 3 and 4 from the peering note get a decision or an explicit "deferred".

### Phase 2: `.cljc` core extraction (Thu Nov 5 to Mon Nov 9)

**M2.1. Boundary inventory (mechanical).** List every namespace as `core` (pure `.cljc`), `host` (needs interop) or `server-only`. Today `reveal.cljc`, `hash.cljc`, `store.cljc`, `wire/binary.cljc`, `store/pack.cljc` are already `.cljc`; `service/reveal.clj`, `store/reveal.clj`, `store/remote.clj`, `service.clj` are JVM-only.
- Acceptance: `docs/design/core-boundary.md` plus a lint test that fails if a `core` namespace requires a host-only namespace.

**M2.2. Move the reveal engine's host-neutral parts into core.** Page ordering, interest and share math, session clock, session state, ack/release. Replace any JVM interop with `dacite.host` calls. No behavior change.
- Acceptance: all 564 JVM tests still pass; `reveal:test` (nbb) runs the same cases as `reveal_test.clj` for the pure functions.

**M2.3. Parity suite.** Turn the reveal and pack tests that are JVM-only into shared `.cljc` tests (or fixture-driven tests) that run on JVM and nbb. Add golden fixtures for reveal decisions (given a value and a clock, the page order and bytes are identical).
- Acceptance: one command runs the parity suite on both hosts and both report identical results; root hashes match across hosts (extends the existing `parity` example).

### Phase 3: Minimal nbb peer (Tue Nov 10 to Thu Nov 12)

**M3.1. Peer node with a transport abstraction.** `dacite.peer` in `.cljc`: store tiers (memory, local, peers, origin), a request handler for `have?` and reveal, and an `ITransport` protocol. First transport is an in-process or loopback one, so protocol logic is tested without WebRTC.
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

**M9.1. Parity report.** Run the full baseline against the nbb peer. For each of the 564 JVM tests: passes on nbb, ported to the parity suite, covered by another test, or intentionally dropped (with a reason).
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
| Nov 2 | M0 | M1.1 | M1.2 | M2.1 (Doc/Spec bot starts) | M2.2 |
| Nov 9 | M2.3 | M3.1 | M3.2 | M3.3 | M4.1 |
| Nov 16 | M4.2 | M5.1 | M5.2 | M5.3 | M6.1 |
| Nov 23 | M6.2 | M6.3 | M7.1 | holiday | buffer |
| Nov 30 | M7.2 | M8.1 | M8.2 | M8.3 | M9.1 prep |
| Dec 7 | week plan | **M9.2 decision** | | | |

About 24 working days, a decision on or near Dec 8. Phases 4 and 6 are the likeliest to slip. Buffer days: Nov 26 and 27, plus Fridays.

## 7. Acceptance tests that apply to every milestone

- Baseline stays green: JVM suite (564 tests), `reveal:test`, `idb:test`. A red baseline blocks merge unless the milestone explicitly retires that test.
- New code has tests that fail before the change and pass after.
- No wall-clock-dependent assertions in new tests (use the session clock; N16 is the cautionary example).
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
3. **Privacy:** does asking a peer for a hash reveal what the client reads? Should `have?` be limited to known peers or a site? (Before M8.1 is public.)
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
