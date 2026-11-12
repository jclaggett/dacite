# Packaging the Local Peer (Design Note, Draft)

Status: draft, Nov 12, 2026. Post-parity. Nothing here is scheduled yet.

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
- `npx dacite --port 8790 --store ~/somewhere` overrides the defaults.
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
| Port | A fixed default (pick one, e.g. 8790), recorded in a config file so apps can find it. If it's taken, fail loudly; don't drift to a random port. |
| Store | Platform data dir: `~/Library/Application Support/Dacite` (macOS), `$XDG_DATA_HOME/dacite` (Linux). |
| Config | `config.edn` beside the store: port, store path, log level. |
| Logs | macOS: a file in `~/Library/Logs/Dacite`. Linux: journald (`journalctl --user -u dacite`). |
| Auto-update | Not in v1. `dacite service install` pins the installed version. Updating is `npm i -g dacite@latest` and then a restart. Revisit when there are real users. |
| Uninstall | Removes the service and the config. Keeps the store unless `--purge`. |

## 3. Open questions

**Package layout: library or CLI?** Lean: one package `dacite` with a `bin` entry, and the portable `.cljc` core as its library half. Split into `dacite` (CLI) and `@dacite/core` (library) only when a second consumer needs the core without the peer.

**nbb at runtime.** nbb is the reference implementation, so the package depends on nbb and runs the sources directly. A compiled shadow-cljs `:node-script` build would start faster and drop the nbb dependency, but it adds a second runtime to test. Lean: ship on nbb; measure start time before deciding.

**Native deps.**
- `node-datachannel`: needs a prebuild for each OS and arch (macOS arm64 and x64, Linux x64 and arm64, Windows x64). The Linux x64 prebuild works today. `werift` (pure JS) is the documented fallback and is not wired. The 4096 frame clamp travels with it.
- `lmdb`: already an optional dependency with prebuilds. Without it, fall back to a file or memory store and say so at startup.
- CI should install the package on each target OS from a packed tarball (`npm pack`) to catch missing prebuilds.

**WASM instead of native prebuilds?** One `.wasm` file runs on every OS and arch, in Node and in the browser, so it could replace per-platform prebuilds where it fits.
- **Fuse: good fit.** The kernel already sits behind five hex-in, hex-out functions with known-answer vectors (`dacite.hash/kernel`, see [the fuse note](fuse-sl2.md)). A Rust or Zig WASM kernel can drop in for nbb and the browser alike, and must pass the same vectors. Measure the per-call JS-to-WASM cost first; batch whole frames if it eats the gain.
- **Storage engine: maybe.** LMDB depends on memory-mapped files, which WASM can't do portably. Options: a WASM engine over plain file I/O in Node (for example SQLite's WASM build), with IndexedDB staying the browser store. This would remove the `lmdb` prebuild, at some speed cost. Benchmark against `npm run lmdb:bench` before choosing.
- **WebRTC: no.** Node has no built-in WebRTC, and WASM can't open UDP sockets. That leaves `node-datachannel` prebuilds or pure-JS `werift`. Lean: wire `werift` as the fallback so a missing prebuild never blocks `npx dacite`.

**Browser bundle build.** shadow-cljs already builds the browser peer (`npm run peer:build`). The library page still uses `cljs.build.api`. Lean: shadow-cljs for every app bundle, and ship the compiled JS in the npm package so users never need Java. The JVM build stays a dev-time tool.

**Pages from elsewhere (https) talking to localhost.** If an https site tries to load code or open a socket to `http://localhost`, it runs into mixed-content rules and Chrome's private/local network access checks (a permission prompt in recent Chrome). Safari is stricter. Behavior varies by browser and keeps changing. Lean: avoid the problem. The local peer serves the whole page, so the page and its peer share one localhost origin. An https-hosted app that wants the local peer is out of scope for v1.

**Security basics.** Loopback bind by default. Any local process could reach the peer, which is fine for public content-addressed data and not fine for anything private. The room token, or a per-launch secret in the URL, is the minimum. Decide before the service is always-on.

## Not in this note

ICE discovery, TURN, peer lists, roots, and removing the HTTP data API. Those have their own plans.
