# Commit loops

The store’s mutable cell is a **hash**. Wrap it once:

```clojure
(def r (v/root rs))
(def books (v/root (s/lens rs (v/vector rs "books"))))  ; same ops, nested path
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

A **lens** (`s/lens rs path`) is a rooted store whose `root` / `cas!` /
`swap!` talk about the nested value. `path` is a Dacite vector of keys
(or a host seq). The document cell is unchanged. This is client
convenience, not an HTTP path. See
[Rooted stores — lenses](../04-rooted-stores/chapter.md#48-lenses).

See [Two writers, one CAS](../tutorial/two-client.md).
