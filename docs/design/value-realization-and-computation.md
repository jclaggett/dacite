# Value Realization, Partial Availability, and Scalar Computation

**Status:** Design discussion. Some points decided and implemented; others
proposed or held as future direction. See the per-section status markers.

> **🔖 Resolved (2026-06-19):** §2 collection realization is decided and
> implemented. Rather than *dropping* whole-collection `->clj`, we made it
> **lazy and deep**: `->clj` on a collection returns a lazy seq of `->clj`'d
> elements (a map returns a lazy seq of realized `[k v]` pairs), with empty
> collections yielding `nil`. Laziness is what reconciles the convenience of
> `->clj` with partial availability — only the consumed portion is fetched.
> See the updated §2 below. **Bounded `toString`** is now implemented too
> (default 32 elements / 64 chars; REPL via `print-method` + `*print-length*`).

## Context

This note captures a design discussion about how applications interact with
Dacite values once the value model (Chapter 3) exists:

1. How a Dacite value is converted back into a plain language value.
2. How that conversion interacts with Dacite's **partial availability**
   property — values may be very large and only partially resident locally.
3. What scalars need in order to be useful to an application: seeing their
   values, and computing with them.

The unifying constraint is partial availability. A Dacite collection is a tree
of content-addressed nodes; any node may be fetched on demand. We must not bake
in operations that quietly assume the *whole* value is local.

### Node locality is handled below the value layer

A point that bounds this discussion: **the value layer is oblivious to where
nodes live.** Local stores may act as caches over remote stores, so fetching
any node (`s-get`) may trigger a network fetch elsewhere — an on-demand mode of
operation. The flip side: a seemingly innocuous query of a Dacite value may
fail with an unexpected exception when the network is unavailable.

The value layer therefore is written as if nodes are always available
(eventually), and leaves the *how* (caching, fetching, failure) to the store
and authorization/sharing layers.

## 1. Values are values, not references (Decided, implemented)

Dacite values are immutable, content-addressed values — the opposite of mutable
references. So they do **not** implement `IDeref`; there is no `@value`.

Converting a value to a plain language value is an **explicit** call: `->clj`.

This was implemented: `IDeref` (and the scalar's pointless zero-arg `IFn`) were
removed; `->clj` was added to the `IDaciteValue` protocol and exported from the
`dacite.value2` facade.

## 2. Realization vs. partial availability (Decided, implemented)

### The core observation

Any operation that yields a **complete** language collection (a Clojure
vector/map/set/`String`/`byte[]`) is inherently a **full-traversal,
full-availability** operation — a finite language collection is fully realized
by definition. So the real question was not "shallow vs. deep" realization; it
was whether a whole-collection `->clj` that *forces full availability* should
exist at all.

A "shallow" *eager* collection `->clj` (leaves left as wrapped Dacite values)
still walks the entire spine and every leaf reference to build the result. That
is exactly the assumption we want to avoid for large/partial values.

### Resolution: lazy + deep `->clj`

The resolution (2026-06-19) keeps a single, uniform `->clj` but makes it **lazy
and deep** instead of eager:

- `->clj` on a **scalar** → its language value (atomic, one local node).
- `->clj` on a **collection** → a **lazy seq** of `->clj`'d elements. Because
  it is lazy, realizing *k* elements fetches ~O(k + path) nodes, not the whole
  tree — partial availability is preserved. A caller who consumes the whole seq
  owns that full-traversal cost explicitly (`(vec (->clj v))`, `(apply str
  (->clj s))`, etc.).
- Deep by construction: each element is itself `->clj`'d, so sub-collections
  become **nested lazy seqs**.
- Maps → a lazy seq of `[k v]` pairs with both key and value realized.
- Empty collections → `nil` (matching `clojure.core/seq`).

This is strictly better than the "drop whole-collection `->clj`" proposal that
preceded it: it is uniform (one function across scalars and collections),
deep (recursion is automatic), and partial-availability-safe (laziness), while
still letting the caller force a concrete language structure when they want one.

### What is already partial-friendly

The collection wrappers implement the standard language interfaces, and the
important ones touch only the nodes along a path:

- `count` → O(1) (root measure; one node)
- `nth` / `get` → O(log n) (one root-to-leaf path)
- `first` / `last` / `peek` → O(1) (the digits)
- `seq` → lazy; consuming *k* elements fetches ~O(k + path), not the whole tree

This is the real "manipulate a huge value in part" surface, and it is intact.
The only thing that forces full availability is asking for the entire value as
native data.

### Why a *lazy* `->clj` is not redundant

A `DaciteVector` already *behaves* as a language vector (lazily, partially) via
its interfaces, so an *eager* `->clj` would be both redundant and a forced
realization. A *lazy* `->clj` is different: it is the one operation that walks
the structure as a uniform, **deep** sequence (recursively realizing nested
collections and unwrapping leaves to language values) without committing to a
concrete container. The wrapper interfaces (`nth`/`get`/`seq`) hand back wrapped
elements; `->clj` hands back realized language values, lazily.

### Strings and blobs

Strings and blobs follow the same rule: `->clj` returns a lazy seq of their
items (chars for strings, byte values for blobs). The common "give me the whole
thing" case is then an explicit, call-site-owned materialization:

- String → `(apply str (->clj s))`
- Blob → `(byte-array (->clj b))`

`toString` still renders a `String` for a `DaciteString` (it reconstructs the
characters directly), so logging/printing a string is unaffected. Dedicated
convenience materializers (`string->str` / `blob->bytes`) remain a possible
future ergonomic but are not needed for correctness.

### Bounded `toString` (Decided, implemented)

`Object/toString` is always bounded and never throws. Defaults (overridable via
dynamic vars in `dacite.value2.render`):

- `*to-string-element-limit*` — 32 elements for vectors/maps/sets
- `*to-string-char-limit*` — 64 characters for strings
- `*to-string-byte-hex-limit*` — 16 bytes of hex preview for blobs

Small values render fully (e.g. `[1 2 3]`, unquoted `hello` for strings).
Larger values truncate with a summary (`… (N total)`, `"prefix…" (N chars)`,
`<blob N bytes 0x… …>`). Nested elements recurse with the same bounds.

REPL printing uses `print-method` on each deftype, delegating to
`print-dacite-value`, which honors `*print-length*` and `*print-level*`.

## 3. What scalars need to be useful

Two application needs drive scalar design.

### Need 1 — see the value (present / log) (Decided)

Showing a value to a user or writing it to a log is exactly `->clj` on a scalar:
atomic, one local node, no traversal. (`toString` gives the `[type data]` debug
form; `->clj` gives the bare value for UI/logs.)

### Need 2 — compute (Decided: option (a))

**Decision (2026-06-18):** adopt **(a) explicit extract → compute → rewrap** as
the supported approach now. Leave **(b)** and **(c)** as future work. Adopt
**(d)** as the natural north star. By choosing (a), the caller picks the result
constructor explicitly, so exact scalar-type fidelity stays in the caller's
hands and no lifting/promotion machinery is needed for now.

Calculations such as `(+ a b)` over Dacite scalars. The design spectrum, least
to most ambitious:

#### (a) Explicit extract → compute → rewrap (baseline) — Chosen

```clojure
(i64 store (+ (->clj a) (->clj b)))
```

Full control over result store and type; zero magic; verbose, and repeats the
store/type plumbing everywhere.

#### (b) Generic `lift` — a language fn → a Dacite fn (Future)

Wrap any function so it unwraps Dacite args, computes in plain language values,
and re-wraps the result:

```clojure
(defn lift [f]
  (fn [& args]
    (let [store (->> args (filter dacite-value?) first dacite-store)
          raw   (map #(if (dacite-value? %) (->clj %) %) args)]
      (coerce store (apply f raw)))))   ; coerce: language value -> Dacite value

(def dac+ (lift +))
(dac+ a b)   ;; => a Dacite scalar
```

Elegant, and reuses the existing coerce path. **But the result type is decided
by the language value, not the operand types**, which is the crux:

- `i8 + i8 → i64` (width is lost; this is widening, not wrapping)
- `u64` round-trips **unsafely**: `(->clj (u64 ... (dec (expt 2 64))))` is a
  bignum, and coerce's `integer? → i64` would overflow.

So generic lift cannot faithfully handle wide/unsigned types. It forces two
semantic decisions into the open:

1. **Result typing:** follow the language's value→type mapping (simple but
   lossy), or preserve operand types via a promotion rule?
2. **Fixed-width arithmetic:** do `i8`/`u64` **wrap** (hardware/modular) or
   **promote** (no overflow)? Lifting to language numerics gives promotion for
   free; faithful fixed-width needs explicit wrap.

Predicates (`<`, `=`) are a separate case — they likely want to return a *plain*
boolean for use in control flow, not a Dacite `bool`. So a `lift` (re-wrap) vs.
`lift-pred` (return raw) split may be warranted. (Equality is already free:
content-hash equality means `=` on two scalars works via `hashCode`/`equals`.)

#### (c) Type-aware lift (promotion table) (Future)

Same shape as (b), but choose the result type from the operand types — a small
numeric tower (`f64` if any float; else widest int; preserve unsigned if all
unsigned; …), implemented as a multimethod on operand types. Preserves fidelity
but is a real type system to design and maintain.

#### (d) Content-addressed computation (north star — adopted as direction)

Because values are immutable and content-addressed, a **pure** computation has a
deterministic identity: `result_hash` is a function of `(operation_id, hash(a),
hash(b), …)`. This enables:

- **Memoization** — store `(op, arg-hashes) → result-hash`; never recompute,
  share results across machines ("the answer is already in the cache").
- **Deferred / distributed evaluation** — represent the expression itself as
  Dacite values (a content-addressed computation graph), evaluated lazily or
  remotely, under the same partial-availability / on-demand-fetch model as data.

This is Unison-flavored (hashing computation, not just data) and the most
on-brand with Dacite's premise — and the largest piece of work. It is a layer
of its own ("content-addressed evaluation"), above the value model, deserving
its own design pass.

## Decision (2026-06-18)

- Adopt **(a)** as the supported approach for scalar computation now: extract
  with `->clj`, compute in the host language, re-wrap with the desired scalar
  constructor. **No new machinery is required** — the existing scalar `->clj`
  plus the constructors already cover it.
- Defer **(b)** (generic `lift`) and **(c)** (type-aware promotion) to future
  work.
- Adopt **(d)** (content-addressed evaluation) as the stated north star, to be
  taken up as its own design pass.

## The gating decision — resolved by choosing (a)

The open question was *how much Dacite cares about exact scalar-type fidelity
through arithmetic.* Choosing (a) **defers it**: the caller selects the result
constructor, so exact widths / unsigned / wrapping are entirely
caller-controlled and no promotion system is needed now. The question returns
only if a generic `lift` ((b)/(c)) is taken up later.

## Status summary

| Topic | Status |
|-------|--------|
| No `IDeref`; explicit `->clj` | Decided, implemented |
| `->clj` on scalars → language value | Decided, implemented |
| `->clj` on collections → lazy, deep seq (map → `[k v]` pairs); empty → `nil` | Decided, implemented |
| String/blob materialization via `(apply str ...)` / `(byte-array ...)` | Decided, implemented |
| Bounded collection `toString` | Decided, implemented (32 el / 64 char defaults) |
| REPL `print-method` + `*print-length*` / `*print-level*` | Decided, implemented |
| Dedicated `string->str` / `blob->bytes` helpers | Future (optional ergonomic) |
| Scalar presentation via `->clj` | Decided |
| Scalar computation: (a) explicit | Decided — supported approach (no new code) |
| Scalar computation: (b) generic `lift` | Future |
| Scalar computation: (c) type-aware promotion | Future |
| Scalar computation: (d) content-addressed evaluation | Future — adopted north star |
| Exact-width arithmetic fidelity | Deferred — caller-controlled under (a) |
