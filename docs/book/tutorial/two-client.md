# Two-client live

Compare-and-set is the whole distributed update. Two writers append to
the [event log](event-log.md) on one HTTP root; a third process watches
`GET /events` and reprints when the root moves. This is the fourth
application in the [roadmap](../../roadmap.md).

You will:

1. Start `clojure -M:service`.
2. Append from two terminals without lost events.
3. Watch the root change without polling `GET /root` yourself.

## Prerequisites

- JDK 17+ and the Clojure CLI
- The event-log app from the previous tutorial

## Terminal 1 — the service

```bash
cd impl/clojure
clojure -M:service --port 8080 --store mem
```

## Terminal 2 and 3 — two writers

```bash
cd impl/clojure
clojure -M:log -- --url http://127.0.0.1:8080 --n 0 append credit 1 from-a
```

```bash
cd impl/clojure
clojure -M:log -- --url http://127.0.0.1:8080 append debit 1 from-b
```

Each `append` uses `v/ref-swap-info!`: read the current ledger, conj,
CAS the new root. If the other writer landed first, CAS fails, the fn
runs again on the new ledger, and both events stay in the log. When a
retry happened the CLI prints `cas retried N time(s)`.

To force collisions:

```bash
clojure -M:log -- --url http://127.0.0.1:8080 contend 10
```

Two remote clients each append 10 events. The final size is start+20.
`cas-retries` is how many times a writer rebuilt on a newer root.

## Terminal 4 — watch

```bash
clojure -M:log -- --url http://127.0.0.1:8080 watch
```

This is `GET /events` (SSE), not a sleep-and-poll loop. The first
frame is the current root; later frames fire after a successful CAS.
Append in another terminal and this one reprints.

## What this pulled

| Why | Utility |
|---|---|
| Notice a remote root without polling | `GET /events` + `dacite.store.remote/watch-root` |
| Apply a domain fn under contention | `v/ref-swap!` (already the rebase loop) |
| Show that a collision was recovered | `v/ref-swap-info!` → `:retries` |

Async browser networking stayed on the shelf. Two JVM remotes plus an
SSE watch prove the claim; sync XHR is still the browser demo.

## Next

[Directory / blob sync](sync.md) — pull only the files you open.
