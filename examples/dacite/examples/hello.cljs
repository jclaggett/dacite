(ns dacite.examples.hello
  "Minimal Hello World for nbb — mem store, vector, map, content hash.

   Run from repo root:
     npm run hello
     npx nbb -m dacite.examples.hello"
  (:require [dacite.store :as store]
            [dacite.value.api :as d]
            [dacite.value.collections :as coll]
            [dacite.value.scalar :as scalar]
            [dacite.value.types :as types]))

(defn -main
  [& _args]
  (let [st (store/mem-store)
        ;; Build a small vector of ints (auto-coerced to Dacite scalars)
        v  (coll/vector-with-store st 1 2 3)
        ;; A map with a string key and nested value
        m  (coll/hash-map-with-store st
                                     "hello" (scalar/i64-with-store st 42)
                                     "vec" v)
        vh (types/dacite-hash v)
        mh (types/dacite-hash m)]
    (println "Dacite Hello World")
    (println "  vector count :" (d/count v))
    (println "  vector hash  :" (store/hash->hex vh))
    (println "  map count    :" (d/count m))
    (println "  map hash     :" (store/hash->hex mh))
    (println "  (get m \"hello\") realized:"
             (types/realize (d/get m "hello")))
    (println "Done.")))
