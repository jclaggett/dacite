# Read without dumping

Name the piece you need. Do not convert the value to a host map “so the
rest of the program can use it.”

| You need | Call |
|----------|------|
| Type name | `(v/type x)` |
| How many | `(v/count x)` — O(1) |
| Map field / set member / vector index | `(v/get x k)` — still a Dacite value |
| Vector/string/blob index | `(v/nth x i)` |
| Scalar or short string as host | `(v/native x)` |
| Blob bytes | `(v/as-bytes x)` |
| A page of a vector, string, or blob | `(v/slice v start end)` |
| To walk | `(v/seq x)` — lazy; elements are values |

`native` takes an optional char limit (or
`v/*string-char-limit*`). They throw if a string is longer than the
limit — that is the point: field-sized text, not a 3k title dumped into
RAM. `pr-str` never throws; long strings print as `"prefix…" (n chars)`.

`realize` on a scalar is a host atom. `realize` on a collection is a
**lazy** seq of realized elements. `(into [] (v/realize big-vector))` is
an explicit full traversal. Prefer `slice` / `nth` / `seq`.

Missing blobs from `as-bytes` throw `ex-info` with `:dacite/missing`.
Other missing nodes still surface as store exceptions — a library hole
until an app pulls a uniform error.

See [config](../tutorial/config.md) (`native` / `get-in`),
[event log](../tutorial/event-log.md) (`slice` / `nth`),
[sync](../tutorial/sync.md) (`as-bytes`).
