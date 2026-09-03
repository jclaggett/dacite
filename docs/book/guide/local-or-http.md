# Same domain, local or HTTP

Point one namespace at a file store **or** at the HTTP service. Domain
functions do not change.

```clojure
(require '[dacite.store :as s])

(s/file "target/dacite-config")
(s/remote "http://127.0.0.1:8080")   ; JVM; write-back by default
```

Config, notes, event log, and sync all take `--url`. Example:

```bash
cd impl/clojure
clojure -M:service --port 8080 --store mem     # terminal 1
clojure -M:config -- --url http://127.0.0.1:8080 --reset show
clojure -M:config -- --url http://127.0.0.1:8080 set timeout 60
```

A second process `show`s the same root hash. Clients pull nodes they
lack; they do not download a serialized view of the whole map.

Write-back: `s-put` is local until commit. Flush is `POST /nodes` (Layer
1 literals, same as pack GET), then `POST /root/cas`. The domain still
requires only `dacite.value` + `dacite.store`.

nbb and babashka use the file store (`npm run config`, `bb config`).
HTTP clients in those examples are the JVM `--url` path today.

See [Persist and update a document](../tutorial/config.md) and
[Anatomy](../building/anatomy.md).
