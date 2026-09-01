# Commit loops

The store’s mutable cell is a **hash**. Wrap it once:

```clojure
(def r (v/root rs))
```

| Situation | Op |
|-----------|-----|
| Read current value | `deref` (nil if unset) |
| Might race (always) | `swap!` |
| Seed empty root | `cas!` from `nil` |
| Show conflict cost | `swap-info!` → `{:value :retries}` |

```clojure
(or (v/deref r)
    (let [seed (v/map r "theme" "dark")]
      (v/cas! r nil seed)
      seed))
```

`swap!` is read → apply `f` → CAS. If another writer landed first, `f`
runs again on the new current value. Domain functions must be
**retries-safe**: compute the next value from the argument, do not close
over a stale copy.

```clojure
(v/swap! r add-todo "milk")
;; add-todo is (fn [todos title] (v/conj todos …))
```

See [Two writers, one CAS](../tutorial/two-client.md).
