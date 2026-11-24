# Packaging the Local Peer (Design Note, Draft)

Status: draft, Nov 12, 2026. Post-parity. Slice 1 (`npx dacite` on one port) is built. The plan for the rest of packaging was decided Nov 19, 2026; see "Plan (decided Nov 19)" and "Slices" below.

## The idea in three lines

1. `npx dacite` starts a local nbb peer on your machine.
2. The same process serves static app code (HTML, JS, CSS) at `http://localhost:<port>`.
3. The browser loads the app from that peer, then connects back to it over WebRTC. All data moves over peering.

HTTP carries static code only. There is no HTTP data API, so this fits the plan to remove the legacy HTTP code after parity.

## 1. `npx dacite`: one process, three jobs

| Job | How | Notes |
|---|---|---|
| Peer | nbb peer on `node-datachannel`, LMDB store | Today's `create-peer`, run as the answerer. |
| Signaling | WebSocket on the same port | The slice-3 room relay (`scripts/webrtc-signal-server.mjs`), folded in. Loopback only. |
| Static code | Plain file server on the same port | Serves the app bundle. Never serves values or entries. |

One port and one origin keep it simple. The page reads its own origin, opens `ws://localhost:<port>/signal`, and offers to the local peer. That is the M5.2 flow with the room token issued by the peer instead of pasted by hand.

Rough CLI:

- `npx dacite` starts the peer and prints the app URL.
- `npx dacite --port 8790 --store ~/somewhere` overrides the defaults (`--store` and `--memory`: Plan §P3).
- `npx dacite open <app>` opens a bundled app (the Value Browser first) in the default browser.

Bind to `127.0.0.1` by default. Listening on the LAN is a later, explicit flag.

## 2. Always-on service

Goal: the local peer is just there, like a print spooler.

- **macOS:** a launchd user agent at `~/Library/LaunchAgents/dev.dacite.peer.plist`.
- **Linux:** a systemd user unit at `~/.config/systemd/user/dacite.service`.
- **Windows:** later. A scheduled task at logon is the likely first step.

Commands: `dacite service install`, `dacite service uninstall`, `dacite service status`, `dacite service logs`. They are user-level, so no sudo.

| Topic | Proposal |
|---|---|
| Port | 8790 (slice 1). If our own peer holds it, reuse it; anything else fails loudly; never drift to a random port (Plan §P2, decided). |
| Store | The default data dir (Plan §P3, decided). |
| Config | `config.edn` beside the store: port, store path, log level. |
| Logs | macOS: a file in `~/Library/Logs/Dacite`. Linux: journald (`journalctl --user -u dacite`). |
| Auto-update | Not in v1. `dacite service install` pins the installed version. Updating is `npm i -g dacite@latest` and then a restart. Revisit when there are real users. |
| Uninstall | Removes the service and the config. Keeps the store unless `--purge`. |

## 3. Open questions

**Package layout: library or CLI?** Lean: one package `dacite` with a `bin` entry, and the portable `.cljc` core as its library half. Split into `dacite` (CLI) and `@dacite/core` (library) only when a second consumer needs the core without the peer.

**nbb at runtime.** nbb is the reference implementation, so the package depends on nbb and runs the sources directly. A compiled shadow-cljs `:node-script` build would start faster and drop the nbb dependency, but it adds a second runtime to test. Lean: ship on nbb; measure start time before deciding.

**Native deps.**
- `node-datachannel`: needs a prebuild for each OS and arch (macOS arm64 and x64, Linux x64 and arm64, Windows x64). The Linux x64 prebuild works today. `werift` (pure JS) is the documented fallback and is not wired. The 4096 frame clamp travels with it.
- `lmdb`: already an optional dependency with prebuilds. Without it, fall back to the file store with a warning at startup (decided Nov 19, Plan §P3).
- CI should install the package on each target OS from a packed tarball (`npm pack`) to catch missing prebuilds.

**WASM instead of native prebuilds?** One `.wasm` file runs on every OS and arch, in Node and in the browser, so it could replace per-platform prebuilds where it fits.
- **Fuse: good fit.** The kernel already sits behind five hex-in, hex-out functions with known-answer vectors (`dacite.hash/kernel`, see [the fuse note](fuse-sl2.md)). A Rust or Zig WASM kernel can drop in for nbb and the browser alike, and must pass the same vectors. Measure the per-call JS-to-WASM cost first; batch whole frames if it eats the gain.
- **Storage engine: maybe.** LMDB depends on memory-mapped files, which WASM can't do portably. Options: a WASM engine over plain file I/O in Node (for example SQLite's WASM build), with IndexedDB staying the browser store. This would remove the `lmdb` prebuild, at some speed cost. Benchmark against `npm run lmdb:bench` before choosing.
- **WebRTC: no.** Node has no built-in WebRTC, and WASM can't open UDP sockets. That leaves `node-datachannel` prebuilds or pure-JS `werift`. Lean: wire `werift` as the fallback so a missing prebuild never blocks `npx dacite`.

**Browser bundle build.** shadow-cljs already builds the browser peer (`npm run peer:build`). The library page still uses `cljs.build.api`. Lean: shadow-cljs for every app bundle, and ship the compiled JS in the npm package so users never need Java. The JVM build stays a dev-time tool.

**Pages from elsewhere (https) talking to localhost.** If an https site tries to load code or open a socket to `http://localhost`, it runs into mixed-content rules and Chrome's private/local network access checks (a permission prompt in recent Chrome). Safari is stricter. Behavior varies by browser and keeps changing. Lean: avoid the problem. The local peer serves the whole page, so the page and its peer share one localhost origin. An https-hosted app that wants the local peer is out of scope for v1.

**Security basics.** Loopback bind by default. Any local process could reach the peer, which is fine for public content-addressed data and not fine for anything private. Decided Nov 19: `/signal` takes a secret token (Plan §P1), which ships before `dacite service install`.

- **WebSockets skip CORS.** Any website the user visits can open `ws://localhost:<port>/signal`. The peer must reject a `/signal` upgrade whose `Origin` is not its own (`http://localhost:<port>` or `http://127.0.0.1:<port>`).
- **DNS rebinding.** Check the `Host` header on every request, both static and `/signal`, and accept only `localhost:<port>` and `127.0.0.1:<port>`.

## Plan (decided Nov 19, 2026)

Jonathan's call: finish packaging, then Value Browser rendering (M6.5). He decided the four items below (the `/signal` token, port reuse, a persistent store, and bundles built at pack time), and the details listed under "Details decided Nov 19". Nothing here is open. No code here; each item is one slice (see "Slices").

What exists today (slice 1): `bin/dacite.mjs` parses `--port` only, starts `startDacite` (`scripts/dacite-server.mjs`), makes a room `dacite-<12 random bytes, base64url>` for this launch, loads `bin/dacite-peer.cljs` (which returns `dacite.cli.peer/start!`), and prints `open http://127.0.0.1:<port>/app/browser/?signal=…&room=…`. `startDacite` checks `Host` on every request (`allowedHosts`) and `Origin` on an upgrade (`allowedOrigins`); an upgrade with no `Origin` is let in. The relay is `createRelay` (`scripts/webrtc-signal-server.mjs`) at `/signal/room/<token>`. `dacite.cli.peer/start!` holds one welcome value in a `store/mem-store`. The pages read `signal` and `room` from the query (`URLSearchParams` in `browser_web.cljs`, `peer_web.cljs`, `library_web.cljs`). On EADDRINUSE the bin prints `port <n> is in use; pass --port` and exits 1.

### P1. The `/signal` token (decided)

- **Generation.** `crypto.randomBytes(32).toString('base64url')`: 256 bits, 43 characters, no padding. Made in `bin/dacite.mjs` at startup, beside the room.
- **Storage.** In memory for the life of the process, and written to the run file `<data dir>/run/<port>.json` (mode 0600 on POSIX; mode bits do not apply on Windows, where the per-user ACL on `%LOCALAPPDATA%` is the equivalent and is enough; removed on a clean exit) so a second `npx dacite` on the same port can print the URL again (§P2). The token is per-run: it is not persisted across restarts, so a restart invalidates old tabs. The run file lives under the data dir even with `--store` or `--memory`: it is per-user state, not part of a store.
- **How a tab gets it.** The printed URL carries it in the fragment: `http://127.0.0.1:<port>/app/browser/#token=<t>&room=<r>`. A fragment is never sent in a request and never appears in a `Referer`, so it cannot reach a server log. The static server will also send `Referrer-Policy: no-referrer`. The page reads `location.hash` at startup, keeps the token in `sessionStorage` (so a reload works), and strips it from the address bar with `history.replaceState`. `signal` and `room` move to the fragment too; the query form stays for the dev relay (`npm run webrtc:signal`), which has no token.
- **How it is sent.** A browser `WebSocket` cannot set headers, so the token goes in the subprotocol list: `new WebSocket(url, ['dacite.v1', 'dacite.token.' + t])`. base64url characters are all valid in a subprotocol token. The server answers with `dacite.v1` only, so the token is never echoed. The in-process nbb peer passes the same list to its `ws` client (`webrtc.cljs`, `new WS (room-url …)`). Not the query string (it shows in devtools and proxies) and not a first message (the upgrade would already be accepted).
- **The check.** In the `upgrade` handler of `startDacite`, in this order: `Host` (403), `Origin` when present (403), path under `/signal/` (404), then the token: parse `Sec-WebSocket-Protocol`, take the `dacite.token.` entry, compare with `crypto.timingSafeEqual` (equal-length buffers; a length mismatch is a failure). A missing or wrong token is `401` and the socket is destroyed before `handleUpgrade`, so the relay does no work. An upgrade with no `Origin` now needs the token too, which closes the "any local process" gap. Static requests under `/app/` need no token: the code is public, and data moves only over peering.
- **Tests.** `dacite:server-test`: no token, a wrong token, a wrong-length token, and a right token with a foreign `Origin` are refused; a right token is accepted. `dacite:e2e`: Chromium opens the printed fragment URL, fetches, reloads, and fetches again.

### P2. Port reuse (decided)

- **Probe.** The server answers `GET /dacite/version` (Host-checked, no token, no CORS headers) with `{"dacite": "<package version>", "proto": 1, "pid": <pid>}`.
- **On EADDRINUSE**, `bin/dacite.mjs` probes `http://127.0.0.1:<port>/dacite/version` with a 500 ms timeout:
  - Our peer, same version: reuse it. Read `<data dir>/run/<port>.json`, print the same `open` URL, and exit 0 without starting a second peer. If the run file is missing (another user's peer, or a stale file), say so and exit 1.
  - Our peer, another version: exit 1 with `dacite <theirs> (pid <pid>) is running on port <port>; this is <ours>. Stop it or pass --port.`
  - No answer, or not ours: exit 1 with `port <port> is in use by another program; pass --port.`
- **Tests.** In `dacite:server-test`: start one server, then run the bin on the same port three ways (same version, faked other version, a plain HTTP server on the port) and check the exit code and the line printed.

### P3. A persistent store (decided)

- **Data dir.** `DACITE_HOME` when it is set; otherwise macOS `~/Library/Application Support/Dacite`, Linux `${XDG_DATA_HOME:-~/.local/share}/dacite`, Windows `%LOCALAPPDATA%\Dacite` (local, not the roaming `%APPDATA%`). The store is `<data dir>/store`; the run files are `<data dir>/run`.
- **`--store <dir>`.** Any directory, created if missing.
- **In memory.** `--memory`: today's behavior (`store/mem-store` plus the welcome value). Nothing is written except the run file.
- **Engine.** LMDB (`dacite.store.nbb.lmdb/lmdb-store` with `lmdb-root-cell`) when the optional `lmdb` loads; otherwise the file store (`dacite.store.nbb/file-store` with `dacite.rooted/file-root-cell` at the same base path), with a warning that `lmdb` did not load. The startup line names the engine and the path. A store dir records its engine in a marker file, and opening it with the other engine is an error, not a silent second store.
- **One peer per store.** A `LOCK` file with the pid, created with `fs.openSync(path, 'wx')` (fails if it exists). If `LOCK` exists, check its pid with `process.kill(pid, 0)`: no error or `EPERM` means alive, and the peer exits 1 naming the pid; `ESRCH` means dead (a crash left it). A stale lock is taken over with a warning (`taking over a stale LOCK from pid <pid>`): write `LOCK.<own pid>.tmp`, `fs.renameSync` it over `LOCK` (atomic on one filesystem), then read `LOCK` back. If it does not hold our pid, another peer took it first, and this one exits 1. A recycled pid looks alive, so the error says to delete `LOCK` if no dacite is running.
- **Contents.** The welcome value is put once, into an empty store. `dacite.cli.peer/start!` takes the store options from the bin (`{ws, room, token, store}`), and the printed `value` line becomes the root when a root is set.
- **Tests.** An nbb check: start on a temp `--store`, put, stop, start again, and the value is still there; a wrong-engine marker and a live `LOCK` are refused; a `LOCK` with a dead pid is taken over with the warning.

### P4. Bundles built at pack time, not committed (decided)

- **Before slice 5.** Three `cljs.build.api` whitespace bundles were committed: `examples/web/js/main.js` (todo), `examples/web/explorer/js/main.js`, and `examples/web/library/js/main.js`, 2.8 to 3.3 MB each, built by the `:cljs-web`, `:cljs-explorer`, and `:cljs-library` aliases. A rebuild only reorders namespaces, so it is a 40k-line diff (checklist Nov 17, #3). The shadow-cljs bundles (`examples/web/peer/js`, `examples/web/browser/js`) are already ignored and built in CI (`peer:build`, `browser:build`). The page `npx dacite` prints needs `examples/web/browser/js/main.js`, so from a clone it needs `npm run build` (or `npm run browser:build`) first.
- **Build.** One `npm run build` builds every bundle. `"prepack": "npm run build"` runs it on `npm pack` and `npm publish`; `prepublishOnly` runs the tests. Users of the package get compiled JS and never need Java; the build itself needs Java (shadow-cljs and `cljs.build.api` both run on the JVM), which is fine on CI and on the publisher's machine. Not `prepare`: it runs on every `npm ci` in a clone and would make Java a requirement to install.
- **Stop committing.** `git rm --cached` the three bundles and add them to `.gitignore`. Moving those three pages to shadow-cljs (§3, "Browser bundle build") is a separate later change.
- **`files`.** `package.json` lists what ships: `bin/`, `scripts/dacite-server.mjs`, `scripts/dacite-args.mjs` (slice 3), `scripts/static-server.mjs`, `scripts/webrtc-signal-server.mjs`, `impl/clojure/src`, `examples/web` (with the built `js/`), `README.md`, `LICENSE`. The package stays `private` until the publishing slice.
- **From a git checkout.** `npx dacite` still runs from a clone. If `examples/web/browser/js/main.js` is missing, the bin exits 1 with `no app bundle: run npm run build (needs Java 21)` instead of serving a page that cannot load.
- **CI.** `test.yml` already builds the bundles before the e2e steps. Add one step: `npm pack`, install the tarball into a temp dir, run its `npx dacite --port 0 --memory`, and run `dacite:e2e` against it. That catches a file missing from `files`. Of the committed bundles, CI reads only the library one, and `library:e2e` already runs after `library:build`; no script reads the todo or explorer bundle.

### P5. Safari and Firefox hand runs against the packaged peer

Run by Jonathan on a Mac, after P1 to P4, with the packed tarball (not a clone), on the same computer as the peer. Browser settings are the M5.2 ones in `docs/plan/peering.md` (Firefox `media.peerconnection.ice.obfuscate_host_addresses` = `false`; Safari Hide IP address Off). For each of Safari and Firefox:

- [ ] `npm pack`, install the tarball in a temp dir, run `npx dacite`, and open the printed URL. The address bar no longer shows the token.
- [ ] Paste the printed `dacite://local#urn:dacite:<hex>` and fetch: every entry verifies.
- [ ] Reload and fetch again (token from `sessionStorage`).
- [ ] In a new tab, open the printed URL with `&token=…` removed and fetch: the status is `failed: no-token`. (With no fragment at all, the page points at the dev relay on port 8787, not its own host, so that is not this check; and the peer logs no 401, only `session …` lines.)
- [ ] The library and peer pages with the token in the fragment (`/app/library/?root=<hex>#signal=…&room=…&token=…`, likewise `/app/peer/`): the token leaves the address bar, the peer logs `session N connected`, and a reload connects again. Without `&token=…` both show `failed: signaling socket error (signaling-error)`. Against `npx dacite` the library page then shows `failed: peer value is not a library` (the welcome value is not a catalog), and the peer page reaches `reading…` and times out (`npx dacite` does not serve its read; `peer:e2e` covers it).
- [ ] Run `npx dacite` again in a second terminal: it prints the same URL and exits 0 (P2).
- [ ] Stop and restart with the default store: the value is still there; the old tab's token is refused (per-run token).

### Details decided Nov 19

Jonathan took all six recommendations:

1. Token lifetime: per-run. A restart invalidates old tabs (§P1).
2. Data dir: `~/Library/Application Support/Dacite`, `${XDG_DATA_HOME:-~/.local/share}/dacite`, and `%LOCALAPPDATA%\Dacite`, with a `DACITE_HOME` override (§P3).
3. In-memory flag: `--memory` (§P3).
4. Probe endpoint: `GET /dacite/version` (§P2).
5. Run file: under the data dir, `<data dir>/run/<port>.json` (§P1).
6. Without `lmdb`: fall back to the file store with a warning (§P3).

## Slices

- [x] **Slice 1: one port** (Nov 17, 2026). `npx dacite [--port 8790]` (`bin/dacite.mjs`, the package `bin`) binds 127.0.0.1 and serves, on one port: the static app code (`examples/web` at `/app/`), the room relay at `ws://127.0.0.1:<port>/signal` (`/signal/room/<token>`), and an nbb peer (`dacite.cli.peer`) that answers in a room token made for this launch. It prints the Value Browser URL with the relay and the room in the query (in the fragment since slice 2), and the hash of the one welcome value it holds. Server: `scripts/dacite-server.mjs`.
  - Host is checked on every request and upgrade (`localhost:<port>` and `127.0.0.1:<port>` only, else 403). A `/signal` upgrade with a foreign `Origin` is 403. An upgrade with no `Origin` is let in: browsers always send one, and the in-process peer's `ws` client sends none.
  - The static server checks the real path (`fs.realpath`) too, so a symlink cannot serve a file outside the root (403).
  - Choices made for this slice, open for review: the default port is 8790 (from the Port row); the store is in memory with one welcome value (no `--store` yet); the only flag is `--port`; one browser session at a time (the peer rejoins the room when a session ends); the package is still the private `dacite-sci`, so `npx dacite` works from a clone, not from the npm registry; `nbb` moved to `dependencies` because the bin runs on it.
  - Tests: `npm run dacite:server-test` (Host, Origin, one-port relay, symlink) and `npm run dacite:e2e` (Chromium fetches the welcome value through `npx dacite`, reloads, and fetches it again). Both run in CI.
- [x] **Slice 2: the `/signal` token** (Plan §P1, Nov 19, 2026). `bin/dacite.mjs` makes the token per run and prints `…/app/browser/#signal=…&room=…&token=…`. `startDacite` requires a `token`, checks it after Host, Origin, and the path (`offeredToken`, `tokenOk`: `crypto.timingSafeEqual`, another length fails), answers 401 before the relay, answers the subprotocol `dacite.v1` only, and sends `Referrer-Policy: no-referrer` on every response. `dacite.peer.transport/signal-protocols` builds the subprotocol list for both clients (`transport/browser.cljs` and the nbb `transport/webrtc.cljs`); `dacite.cli.peer/start!` takes `{ws, room, token}`. The Value Browser (`browser_web.cljs`) reads `signal`, `room`, and `token` from the fragment (the query still works for the dev relay), keeps the token in `sessionStorage`, strips it with `history.replaceState`, sends it only to a relay on its own host, and shows `no-token` when its own relay refuses it.
  - Held in memory only in this slice. The run file (`<data dir>/run/<port>.json`) comes with slice 4, its only reader, after the data dir in slice 3.
  - Only the Value Browser, the page `npx dacite` prints, reads the token. The library and peer pages (`library_web.cljs`, `peer_web.cljs`) still take `signal` and `room` from the query for the dev relay; against `npx dacite` they are now refused (401).
  - Tests: `npm run dacite:server-test` (no subprotocol, `dacite.v1` only, a wrong, short, long, empty, or unframed token, each with our Origin and with none, are 401; a foreign Origin with the right token is 403; the right token joins and the answer header is `dacite.v1`; `Referrer-Policy` on 200 and 403) and `npm run dacite:e2e` (the fragment URL, the token gone from the address bar and from every request, a reload, and a fresh tab without the token shown `no-token`).
- [x] **Slice 3: a persistent store** (Plan §P3, Nov 19, 2026). `dacite.cli.store` resolves the data dir (`data-dir`, a pure function of env, platform, and home), takes `LOCK`, reads or writes the `ENGINE` marker, and opens LMDB or the file store; `dacite.cli.peer/start!` takes `{ws, room, token, store}` and returns the store's engine and dir for the startup line `store <engine> <dir>`. `bin/dacite.mjs` parses `--store <dir>` and `--memory` with `scripts/dacite-args.mjs`. Details this slice chose where the plan left them open:
  - Layout: `<store dir>/ENGINE` (one line, the engine, a space, and its store format version: `lmdb 2` or `file 1`), `<store dir>/LOCK`, the LMDB env in `<store dir>/lmdb` (LMDB will not open an existing empty directory), the file store and its `ROOT` in `<store dir>/file`. An unmarked dir with an `lmdb` or `file` subdir is marked for that engine.
  - A marked store keeps its engine: a `file` store stays file when `lmdb` loads later (no warning), and an `lmdb` store is refused when `lmdb` does not load. The marker carries a format version so a later layout change is a version bump, not a guess (Grok's review of #82). `lmdb 2` is LMDB data format 2, what the `lmdb` npm prebuilds write; that settles the v1/v2 question for `npx dacite` stores (the JVM and the `npm run lmdb:v1` build read format 1, and a store marked `lmdb 2` is not theirs). An unknown engine, or a version other than `lmdb 2` / `file 1` (newer or older), is refused, naming both: `store <dir> uses lmdb format 3; this dacite supports lmdb 2`. A marker that is not `<engine> <version>`, including a bare `lmdb` or `file`, is refused as invalid: the layout was never released, so there is nothing to migrate.
  - `--store` and `--memory` together are a usage error (exit 2). `--store` resolves against the working directory. An empty `DACITE_HOME`, `XDG_DATA_HOME`, or `LOCALAPPDATA` counts as unset, and a relative `XDG_DATA_HOME` is ignored (XDG spec). Without `LOCALAPPDATA`, Windows uses `<home>\AppData\Local`.
  - A `LOCK` with no pid in it (a crash while writing it) is stale and taken over (`from pid unknown`). A clean exit (SIGINT, SIGTERM) removes `LOCK` if it still holds our pid. A refused store exits 1 before the URL is printed.
  - "Empty" means no entries (one LMDB cursor step, or no `xx/` dir in the file store). A store with entries and no root prints the welcome value's hash and does not put it again.
  - Tests: `npm run dacite:store-test` (arguments; the data dir; the versioned marker (written, parsed, an unknown engine, an older or newer version, and a bare or garbage marker refused) and the fallback warning; `LOCK` fresh with mode 0600, live, `EPERM`, dead with the warning, no pid, a lost takeover; a value and root kept across a stop and a start on the file store and on LMDB) and `npm run dacite:e2e` (the default store in a temp `DACITE_HOME`, a second launch refused naming the first pid, a SIGKILLed peer's `LOCK` taken over, the same value and entries after the restart, fetched in a fresh tab).
- [x] **Slice 4: port reuse** (Plan §P2, Nov 19, 2026). `startDacite` answers `GET /dacite/version` with `{"dacite": "<package version>", "proto": 1, "pid": <pid>}` (`versionInfo`; `probeDacite` asks it with a 500 ms timeout). On EADDRINUSE, `bin/dacite.mjs` probes the port: no answer or not ours prints `port <port> is in use by another program; pass --port.`; another version or proto prints `dacite <theirs> (pid <pid>) is running on port <port>; this is <ours>. Stop it or pass --port.`; both exit 1 before nbb loads. Our version loads `dacite.cli.run/reuse`, which prints the run file's URL and exits 0, or exits 1 saying why. A peer that starts writes `<data dir>/run/<port>.json` and prints `run <path>`; a clean exit (SIGINT, SIGTERM) removes it when it still holds our pid.
  - On POSIX, the default data dir and `run/` are created mode 0700 (the run file holds the token). A user's `--store` dir is left as it is.
  - Details this slice chose where the plan left them open:
    - The run file is JSON, keys in this order: `dacite`, `proto`, `pid`, `port`, `url`, `signal`, `room`, `token`, `open`, `store` (the store dir, `null` with `--memory`), `engine` (`lmdb`, `file`, `memory`), `value` (the hash printed at start). Written to `<port>.json.<pid>.tmp` (0600 on POSIX) and renamed over it. If it cannot be written, the peer warns and keeps running.
    - Reuse also needs the same store: the run file's `store` must be the store this launch asks for (`--store <dir>`, resolved, or the default store dir), and an in-memory peer matches only `--memory`. Otherwise exit 1 (`dacite (pid N) on port P serves the store X, not the store Y. Stop it or pass --port.`), so a second launch never prints a URL for a store it did not ask for.
    - Reuse prints `dacite already running <url> (pid N)`, `store …`, `value …`, `open …`, and `paste …` from the run file. It opens no store and starts no peer.
    - A run file is stale when its pid is not running, or is not the pid `/dacite/version` reported; reuse then exits 1 naming both pids and does not delete it. A missing or unreadable run file exits 1 too. A peer that binds the port overwrites any old run file for it; run files of other ports are not swept.
    - The data dir (`DACITE_HOME` included) is made 0700 only when the peer creates it; an existing one is left as it is. `run/` is set to 0700 even when it exists. With `--store`, the data dir is created only for `run/`.
    - `/dacite/version` answers GET and HEAD (405 otherwise), ignores the query, and sends `cache-control: no-store`. The probe treats an answer as ours when it is a JSON object with a string `dacite`, an integer `proto`, and a positive integer `pid`; "same version" is the same `dacite` string and `proto`.
  - Tests: `npm run dacite:server-test` (the version endpoint: JSON, Host check, no CORS header, HEAD, 405; `probeDacite` against our server, another JSON shape, a server that never answers, non-JSON, and a closed port; the bin on a taken port three ways: a plain HTTP server and another version exit 1 with their line, our version with its run file prints the run file's URL and exits 0), `npm run dacite:store-test` (`dacite.cli.run-checks`: 0700 data dir and `run/`, an existing data dir left alone, `--store` makes no data dir; the run file's fields, key order, mode 0600, no temp file left, rewrite, removal only when ours, unreadable files; reuse printing the URL, the same dir with a trailing separator, the default store through `DACITE_HOME`, another store or memory refused, a dead pid and another live pid stale), and `npm run dacite:e2e` (the run file's URL, token, pid, store and modes; `npx dacite --port <same>` prints the same URL and exits 0; a clean exit removes the run file, a killed peer's stays).
- [x] **Slice 5: bundles at pack time** (Plan §P4, Nov 19, 2026). `npm run build` runs `peer:build`, `browser:build`, `web:build` (new, `:cljs-web`), `explorer:build` (new, `:cljs-explorer`), and `library:build`; `prepack` runs it and `prepublishOnly` runs `dacite:server-test`, `dacite:store-test`, and `dacite:e2e`. The todo, explorer, and library bundles are untracked (`git rm --cached`) and in `.gitignore`. `files` ships `bin/dacite.mjs`, `bin/dacite-peer.cljs`, `scripts/dacite-server.mjs`, `scripts/dacite-args.mjs`, `scripts/static-server.mjs`, `scripts/webrtc-signal-server.mjs`, `impl/clojure/src/`, `examples/web/` without `**/js/out/`, `README.md`, and `LICENSE` (484 files, 5.5 MB packed, 32.6 MB unpacked). From a checkout with no `examples/web/browser/js/main.js` the bin exits 1 with `no app bundle: run npm run build (needs Java 21)`. CI's last step packs, installs the tarball into an empty project, and runs `dacite:e2e` against the installed `bin/dacite.mjs` (`DACITE_E2E_BIN`). The same slice folds in the slice 2 to 4 review items:
  - The peer page (`/app/peer/`) and the library reader (`/app/library/`) read `signal`, `room`, and `token` from the fragment, as the Value Browser does (`dacite.examples.page-token`, shared by all three pages): the token is kept in `sessionStorage`, stripped from the address bar, and sent as the `dacite.token.<t>` subprotocol only to the page's own host.
  - Own host: `dacite.examples.browser/same-host?` treats `localhost`, `127.0.0.1`, and `[::1]` as one host. The port must match; the scheme only gives the default port (80 for http/ws, 443 for https/wss).
  - Data dirs: a missing data dir's parents are made with the default mode and only the data dir itself gets 0700. On POSIX, the platform default data dir is tightened to 0700 when it exists and we own it (its uid is ours), so a dir made before slice 4 gets fixed. `run/` is tightened the same way. A `--store` dir is never chmodded.
  - Reuse compares stores by real path (`fs.realpathSync` on the nearest existing ancestor, then the rest of the path), so `/tmp/x` and `/private/tmp/x` or a symlinked dir match.
  - Details this slice chose where the plan left them open:
    - An existing `DACITE_HOME` dir is not tightened: the user chose it. It is made 0700 only when the peer creates it. A dir owned by another uid is never chmodded.
    - The shadow-cljs `compile` bundles (peer, browser) ship with their `cljs-runtime/` dirs (about 11 MB each unpacked). A `shadow-cljs release` build would be smaller; it is left for later.
    - The pages keep `root`, `chapter`, and `peer-timeout` in the query. `signal` and `room` come from the fragment, else the query (the dev relay). `token` comes only from the fragment or `sessionStorage`.
    - The CI step runs the whole `dacite:e2e` against the packed bin, not only `--port 0 --memory`.
    - The missing-bundle check runs after the port check, so reusing a running peer still works without a bundle (the early CI suite runs the reuse tests before any build).
    - `dacite:e2e` opens the peer page as `localhost` against the `127.0.0.1` relay and checks that the relay accepted its token and that the token left the address bar. The peer page then reads over the example peer's roots channel, which `npx dacite` does not serve, so the read itself is covered by `peer:e2e`.
  - Tests: `npm run examples:test` and the JVM suite (`loopback-names-are-one-host`), `npm run dacite:store-test` (parents keep the default mode; `tighten-dir!` with another uid, our uid, already 0700, and a missing dir; the platform default tightened, an existing `DACITE_HOME` and `--store` left alone; reuse through a symlink and a nonexistent path under one), `npm run dacite:server-test` (no app bundle), `npm run dacite:e2e` (the peer page's token), and the CI pack step.
- [x] **Slice 6: Safari hand run** (Plan §P5, Nov 20, 2026). An informal pass: Jonathan ran `npx dacite` in Safari on his Mac and reported it looks okay; the per-check boxes in §P5 were not filled in one by one. The checklist he had covered setup (`npm ci`, `npm run build`, or the packed tarball), the printed lines, the Value Browser (token stripped, fetch `verified`, reload, `failed: no-token` without it, the `dacite.token.<t>` subprotocol only to its own host, the page opened as `localhost` against the `127.0.0.1` relay), the library and peer pages' token path, and port reuse and restart. This slice also fixed the §P5 no-token step above.
  - Firefox is deferred (Jonathan, Nov 20).
  - Not checkable against `npx dacite` yet: the library page's full read (the peer holds no catalog), and the peer page's read (`npx dacite` does not serve it). Their token path was the check.
- [ ] Later: `dacite service install` (§2), `npx dacite open <app>`, moving the three `cljs.build.api` pages to shadow-cljs, and publishing (dropping `private`).

## Follow-ups

Hand runs moved here from parity (Jonathan, Nov 17, 2026). Run them against the packaged peer (`npx dacite`, page served by the peer), on the same computer. Steps and browser settings: `docs/plan/peering.md` M6.4 and the M5.2 Safari/Firefox notes (host-address setting so ICE does not stay on a `.local` name). Mac + Brave already passed on the `parity` branch (Nov 11).

- [x] Safari: the M6.4 Value Browser (paste a peer, fetch its hash) over peering only. Informal pass, slice 6 (Nov 20, 2026).
- [ ] Firefox: the same run. Deferred (Nov 20, 2026).

## Not in this note

ICE discovery, TURN, peer lists, roots, and removing the HTTP data API. Those have their own plans.
