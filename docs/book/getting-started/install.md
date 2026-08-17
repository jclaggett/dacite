# Install

This page is the supported on-ramp for **Dacite 0.1 alpha**. The library lives
in a monorepo under `impl/clojure`; for alpha we recommend a clone plus either
**nbb** (fastest) or **tools.deps** with `:local/root`.

Alpha means: useful for experiments; APIs may still change. Pin a git tag or SHA.

## Prerequisites

| Path | Need |
|------|------|
| **nbb (recommended)** | [Node.js](https://nodejs.org/) 18+ |
| **JVM** | JDK 17+ and [Clojure CLI](https://clojure.org/guides/install_clojure) |
| **Browser demo** | JDK + Clojure CLI (to build the CLJS bundle and run the service) |

## Clone

```bash
git clone https://github.com/jclaggett/dacite.git
cd dacite
# optional: check out a release tag when available
# git checkout v0.1.0-alpha
```

## nbb (Node / SCI)

From the repo root:

```bash
npm install
npm run hello          # Hello World
npm run config         # config CLI (file store)
npm run notes          # versioned notes (file store)
npm run todo:batch     # durable todo list (non-interactive)
npm run todo           # interactive todo UI
```

Sources are on the nbb path via `nbb.edn`:

```clojure
{:paths ["impl/clojure/src" "examples"]}
```

Ad-hoc scripts:

```bash
npx nbb -e "(require '[dacite.store :as store]
                     '[dacite.value :as v])
            (let [st (store/mem-store)
                  vec (v/vector-with-store st 1 2 3)]
              (println (v/count vec))
              (println (store/hash->hex (v/dacite-hash vec))))"
```

See [Hello World (nbb)](../tutorial/hello-nbb.md) for a guided walkthrough.

## JVM (tools.deps)

Point a dependency at the library root `impl/clojure` after cloning:

```clojure
;; deps.edn in your project
{:deps {dacite/dacite {:local/root "/absolute/path/to/dacite/impl/clojure"}}}
```

Then:

```clojure
(require '[dacite.value :as v]
         '[dacite.store :as store])

(let [st (store/mem-store)
      vec (v/vector-with-store st 1 2 3)]
  [(v/count vec)
   (store/hash->hex (v/dacite-hash vec))])
```

### Git dependency (optional)

If your tools.deps version supports monorepo `:deps/root`, you can try:

```clojure
{io.github.jclaggett/dacite
 {:git/tag "v0.1.0-alpha"
  :git/sha "REPLACE_WITH_TAG_SHA"
  :deps/root "impl/clojure"}}
```

If that fails on your tools.deps version, use clone + `:local/root` above — that
is the reliable alpha path.

From inside `impl/clojure` you can also run the test suite and service:

```bash
cd impl/clojure
clojure -M:dev:test
clojure -M:service --port 8080 --store mem   # API + browser todo static UI
```

## Browser todo demo

```bash
cd impl/clojure
clojure -M:cljs-web                    # once / after source changes
clojure -M:service --port 8080 --store mem
# open http://127.0.0.1:8080/app/
```

Details: [examples/web/README.md](https://github.com/jclaggett/dacite/blob/main/examples/web/README.md).
Pack GET/POST use **wire-v1 binary** by default.

## Next steps

- [Hello World (nbb)](../tutorial/hello-nbb.md)
- [Values API reference](../reference/values.md)
- [Stores API reference](../reference/stores.md)
- [The Dacite Book](../00-preface.md) — conceptual foundations
- [CHANGELOG](https://github.com/jclaggett/dacite/blob/main/CHANGELOG.md) — alpha scope and known limits
