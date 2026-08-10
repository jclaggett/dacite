(ns dacite.examples.hello
  "Minimal Hello World for nbb — mem store, vector, map, content hash.

   Run from repo root:
     npm run hello
     npx nbb -m dacite.examples.hello"
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn -main
  [& _args]
  (let [st (store/mem-store)
        ;; Bootstrap with an explicit store; then use *-via from peers
        v  (v/vector-with-store st 1 2 3)
        m  (v/hash-map-via v
                           "hello" (v/i64-via v 42)
                           "vec" v)
        vh (v/dacite-hash v)
        mh (v/dacite-hash m)]
    (println "Dacite Hello World")
    (println "  vector count :" (v/count v))
    (println "  vector hash  :" (store/hash->hex vh))
    (println "  map count    :" (v/count m))
    (println "  map hash     :" (store/hash->hex mh))
    (println "  (get m \"hello\") realized:"
             (v/realize (v/get m "hello")))
    (println "Done.")))
