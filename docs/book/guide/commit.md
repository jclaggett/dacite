# Commit loops

The store’s mutable cell is a **hash**. Wrap it once:

```clojure
(def r (v/root-ref rs))
```

| Situation | Op |
|-----------|-----|
| Read current value | `ref-deref` (nil if unset) |
| Local single writer | `ref-reset!` |
| Might race (HTTP, two processes) | `ref-swap!` |
| Seed empty remote | `ref-cas!` from `nil` |
| Show conflict cost | `ref-swap-info!` → `{:value :retries}` |

`ref-reset!` throws on a remote store. Seeding:

```clojure
(or (v/ref-deref r)
    (let [seed (v/hash-map-via r "theme" "dark")]
      (v/ref-cas! r nil seed)
      seed))
```

`ref-swap!` is read → apply `f` → CAS. If another writer landed first,
`f` runs again on the new current value. Domain functions must be
**retries-safe**: compute the next value from the argument, do not close
over a stale copy.

```clojure
(v/ref-swap! r add-todo "milk")
;; add-todo is (fn [todos title] (v/conj todos …))
```

Two HTTP clients appending an event log use this loop;
`:retries` is the UX when they collide. See
[Two writers, one CAS](../tutorial/two-client.md).

On write-back HTTP, `ref-swap!` / remote CAS flushes packed nodes, then
CAS the root. Domain code does not call `flush-from!`.
