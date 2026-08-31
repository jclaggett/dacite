# Pack transport

Packing is **wire-only**. Durable values stay finger trees and HAMTs.
Domain code does not import `dacite.store.pack`.

A **chunk** is a budgeted list of items. Each item is either:

- **`:literal`** — complete realized content at that hash (the receiver
  materializes through normal constructors; hash must match)
- **`:node`** — the stored cell, children fetched later

Layer 1 (`encode-item`) prefers a literal when cached `size-bytes` is ≤
the budget (default **1024**) and a dry-run hash matches. Sequence
bodies collapse contiguous same-type leaves to nested `run` / `repeat`.
Layer 2 seals on **sent** bytes (wire-v1 by default). The item that
crosses 1024 is kept (~2× possible). Budget **0** is a single item (the
asked hash).

## Where it runs

| Path | What |
|------|------|
| `GET /node/{hex}` | `pack-under`: one neighborhood under that hash |
| Write-back commit | `flush-from!`: all unflushed reachable nodes, one or more `POST /nodes` |
| Both | Same `encode-item` (literals, `run` / `repeat`) |

There is no `?raw=`, `?nodes=`, or `?near=` query opt-out. GET always
returns a pack chunk.

Implementors: [leaf-chunking.md](../../design/leaf-chunking.md),
[wire-v1.md](../../spec/wire-v1.md). Application authors can stop here.
