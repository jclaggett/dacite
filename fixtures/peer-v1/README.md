# Peer protocol v1 fixtures

Golden frames for [docs/spec/peer-v1.md](../../docs/spec/peer-v1.md).

```text
fixtures/peer-v1/
  manifest.json            # {"format", "max_frame", "cases": [...]}
  cases/<name>/
    frames.hex             # one frame per line, lowercase hex
    message.edn            # what the frames mean; :expect says how to check
```

`:expect` is `:message`, `:item-stream`, `:reject` or `:unknown`. The spec
(section 8) says what a port must do for each.

Frames are made only by the shipped encoder (`dacite.peer.frame`,
`dacite.peer.msg`, `dacite.wire.binary`). Do not edit hex by hand:

```bash
cd impl/clojure && clojure -M:dev -m gen-peer-fixtures
```

Checked by `clojure -M:dev:test -n dacite.peer.peer-v1-test` (JVM) and
`npm run peer:test` (nbb).

Hashes and item bytes are hash version 2 (the SL(2, F_p) fuse, issue #14); the
known answers for the fuse itself are in `fixtures/fuse-sl2/`.
