# HTTP service

Production endpoints for a dedicated content store plus one root. Full
design: [service.md](../../design/service.md).

The demo server: `clojure -M:service --port 8080 --store mem` (or
`file` / `lmdb`).

| Method | Path | Role |
|--------|------|------|
| GET | `/node/{64-hex}` | Pack-filled chunk (literals under the hash) |
| PUT | `/node/{64-hex}` | Single-node put (EDN) → novelty |
| HEAD / DELETE | `/node/{64-hex}` | Existence / optional delete |
| POST | `/nodes` | Apply one pack chunk (write) |
| POST | `/nodes/get` | Bulk pack (admin/sync; demoted) |
| GET | `/root` | `{:root hex-or-nil}` |
| POST | `/root/cas` | `{:expected hex-or-nil :new hex}` → 200 / 409 |
| GET | `/events` | SSE `event: root` on CAS |

Pack GET/POST prefer `application/vnd.dacite.chunk.v1` (wire-v1). Root
CAS and novelty stay EDN.

Write-back clients: `GET /node` on miss, `POST /nodes` on flush, then
`POST /root/cas`. They do not PUT every node.

No query opt-outs on GET (`?raw=`, `?nodes=`, `?near=` are gone).

Throttle: empty bucket is 429 (`Retry-After`); oversized body is 413.
`remote-store` retries 429/503. See [service.md](../../design/service.md)
inbound throttle.

Static demos: `/app/` (todo), `/app/explorer/` (trailing slash).
