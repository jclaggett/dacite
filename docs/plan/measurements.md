# M5.3 measurements

The library reader (`/app/library/`) loads chapter 0 of the seed catalog and the plate on that page. Two ways: origin only, and the same read from the nbb peer with the origin naming the root. Numbers below are from `npm run library:e2e` on this Linux box, 8 Nov 2026. Round trips and byte counts were the same on both runs. Times were not. Nothing here is a guess filled in after the fact.

## What was read

The seed work in `dacite.examples.library`, title "A Walk Through the Catalog". Chapter 0 is "CHAPTER I. The Shelf". The page is `chapter-page` at the viewer's default size (800). The chapter is shorter than that, so the page is the chapter. The plate is `seed-plate`: a 16×16 RGBA PNG drawn for this catalog (a shelf and four spines), 100 bytes, public domain, stored as a blob on the book. The page shows it in an `<img>`. The browser decoded it (`naturalWidth` 16). It is not a second text page.

Both sides hold that value. The JVM origin (`dacite.examples.library-origin`) publishes it and serves HTTP. The nbb peer (`dacite.examples.library-nbb`) builds it in its own store. The root hash matched: `060ba0eae5b8f85e2deae44fbd54567b4d496fdf61d57dcde5a37e1dc427bcf3`. Reachable entries: 87.

The navigation is `/app/library/?chapter=0`. Peer-assisted adds `signal` and `room` for the slice-3 relay. The offer starts while `GET /root` is in flight. The peer timeout (15 s by default) starts once that hash is known and covers the rest of the handshake and the transfer. This run used loopback, no STUN, Chromium launched with `--disable-features=WebRtcHideLocalIpsWithMdns`. The origin's throttle was on (service defaults).

## How the counts were taken

The script is `scripts/library-peer-e2e.mjs`. It starts the relay, the origin (`clojure -M:library-origin --port 0`), and the nbb peer, then drives Chromium.

- **Round trip.** One completed origin store-protocol HTTP request (`dacite.store.stats` `:requests`: `GET /root` and pack `GET /node`) plus one peer `get`. Static `/app/` assets are not counted. Peer frames are not round trips. One `get` is answered by many frames.
- **Bytes from the origin.** Response body sizes of those HTTP requests (`:bytes-recv`). The root body is the EDN `{:root "<64 hex>"}`, 74 bytes.
- **Bytes from the peer.** The sum of `dacite-peer` message lengths the browser received (the peer's hello and the item frames).
- **Time to first render.** `performance.now()` when the chapter article and the decoded plate are in the document, milliseconds since navigation. **Read time** is from `init!` (the script is running) to that same moment, so it leaves HTML and script parse out.

Origin-only is loaded twice in the same run. Round trips and byte counts have to match or the script fails. Times are printed and not required to match.

## Table

| mode | round trips | bytes from peer | bytes from origin | time to first render |
|---|---:|---:|---:|---:|
| origin-only | 3 | 0 | 11 136 | 493 ms, then 548 ms |
| peer-assisted | 2 | 19 470 | 74 | 619 ms, then 682 ms |

The two numbers in the last column are the two runs, in order. A second origin-only load in the same run (warm script, same bytes) rendered in 354 ms and 371 ms. Read time, same order: origin 313 ms then 356 ms (the warm reload 238 ms then 254 ms); peer 503 ms then 566 ms.

Peer-assisted is 1 `GET /root` (74 bytes) and 1 `get`. The channel carried 7 frames. Origin-only is 3 HTTP requests: that same 74-byte root body and 11 062 bytes of pack responses. The peer reveal is the whole catalog (87 entries, 19 470 bytes on the channel). The origin path pack-fills only what the chapter and the plate touch, so it moves fewer bytes in total and more of them come from the origin.

On this catalog the origin is spared 11 062 bytes. First render is slower with the peer: the handshake and the nbb build sit on the path, and the value is small. That is the measurement.

## Fallback

A room with no answerer and `peer-timeout=400`. The reader still showed the chapter and the plate. Mode `origin-fallback`. Round trips 4, bytes from the peer 0, bytes from the origin 11 210 (the 74-byte root GET, then the same 11 136-byte origin read). No `get` was sent. Time to first render 771 ms, then 803 ms, which includes the 400 ms wait.

## tau, hashing, recv-max

Default interest `tau` is 10 000 ms (`dacite.peer.rates/default-interest`). It was not changed.

The nbb process builds the catalog before it answers. That build hashes every node as it is written. The two runs took 173 823 µs and 207 331 µs. After the first item frame, the responder's streams were empty in 2 496 µs and 3 002 µs. Both are far under `tau`, so this read does not exercise interest decay. The rate cap does not bind either: `recv-max` on the one `get` was 10 000 000 (the client's `R_max`, one peer at peak interest), and the catalog is about 20 KB.

C45's stale-`recv-max` gap does not come up. There is one peer and one `get`, so there is no earlier `recv-max` left outstanding. No protocol change.

## Reproduce

From the repo root, with Java 21, Clojure CLI, and Node:

```bash
npm ci
npm run library:build
npx playwright install chromium
npm run library:e2e
```

The script prints a `measure` JSON object. The same page by hand:

```bash
npm run library:build
npm run webrtc:signal
nbb -m dacite.examples.library-nbb --ws ws://127.0.0.1:8787 --room m53
cd impl/clojure && clojure -M:library-origin
```

Open the printed page URL (it carries `chapter=0`, `signal`, and `room`). The readout under the bandwidth line is the same counters. Drop `signal` and `room` for origin-only.
