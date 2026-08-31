# Update and share identity

Updates return **new** values. The old value is still in the store. Unchanged
children keep their hashes.

```clojure
(def v2 (v/conj todos (v/hash-map-via todos "title" "milk" "done" false)))
(v/dacite-hash todos)   ; unchanged
(v/dacite-hash v2)      ; new root of the vector
```

Nested documents:

```clojure
(v/assoc-in config ["features" 0] "c")
(v/update config "timeout" (fn [n] (v/i64-via config (inc (v/native n)))))
```

`assoc-in` creates intermediate maps as needed. Path ops were pulled by
the config app; use them instead of a hand-rolled walk.

Print hashes when you need to *see* sharing:

```clojure
(store/hash->hex (v/dacite-hash (v/get doc "body")))
```

A title-only edit in [notes](../tutorial/notes.md) leaves that body hash
put and adds fewer store nodes than rewriting the body. History is
another vector of document values: restore means `assoc` the notebook’s
`"doc"` back to a historical value (same hash), not replaying a diff.

New nodes go into the peer’s store via `*-via` / `conj` / `assoc`. You do
not call `s-put` from domain code.
