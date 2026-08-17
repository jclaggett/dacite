# Event log

An append-only vector of events plus a derived view. This is the third
application in the [roadmap](../../roadmap.md): **large sequences stay
cheap to append, and you need not realize the whole log.**

You will:

1. Seed **2000** events (not five).
2. Page the log with `v/subvec` — no `seq` of the whole vector.
3. Replay the view from a prefix via `nth`.
4. See that one more append adds a handful of nodes at n=100 and at n=2000.

## Prerequisites

- A clone of this repo (see [Install](../getting-started/install.md))
- JDK 17+ and the Clojure CLI, or Node.js 18+ / babashka for the file store

## The shape

```text
{"log"  [{"type" "credit"|"debit" "amount" n "note" s} …]
 "view" {"size" n "credits" n "debits" n "balance" n}}
```

Append updates the view incrementally. Replay rebuilds the view from
`log[0, end)` without walking the tail.

Domain code lives in `dacite.examples.event-log` and uses only
`dacite.value`. Pagination pulled `v/subvec`.

## Local file

```bash
cd impl/clojure
clojure -M:log -- --reset show
```

Or from the repo root (file store; seeding 2000 events takes a few seconds):

```bash
npm run log -- --reset show
bb log --reset show
```

`--n 200` seeds a smaller log. Expected shape:

```text
seeded new store at target/dacite-log (2000 events)
size:     2000
credits:  …
debits:   …
balance:  …
log:      2000 events
root:     <64 hex chars>
```

Page, append, replay:

```bash
clojure -M:log -- page 0
clojure -M:log -- page 3 10
clojure -M:log -- append credit 5 coffee
clojure -M:log -- replay 100
```

`page` prints a window and its content hash. `replay 100` replaces the
view with a fold of the first 100 events.

The same `--url` recipe as [Remote config](config.md) works against
`clojure -M:service`.

## Append bench

```bash
clojure -M:log -- bench
```

Typical output:

```text
append bench (nodes added by the last conj at each size)
  n=100   +N nodes
  n=500   +N nodes
  n=1000  +N nodes
  n=2000  +N nodes
```

The deltas stay small. They must not grow linearly with n — that would
mean each append rewrote the log.

A prefix slice has the same hash as a log built from those events
alone (hash fusion is shape-independent).

## What this pulled

| Why | Utility |
|---|---|
| Page without `seq` of the whole vector | `v/subvec` — O(k log n), shared leaves |
| Replay a prefix | `v/nth` in a range |
| Prove append is cheap | node-delta of one `conj` at several sizes |

`dac->clj` is not on this path. Missing-node errors stay on the shelf
until a remote page actually needs a catchable “not local” signal.

## Next

[Two-client live](two-client.md) — CAS retry and a watch across two
writers.
