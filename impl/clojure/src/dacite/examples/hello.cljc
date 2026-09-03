(ns dacite.examples.hello
  "Minimal Hello World — mem store, vector, map, content hash.

   Run from repo root:
     npm run hello
     npx nbb -m dacite.examples.hello"
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn hello
  "Build the hello vector and map in `st` (a rooted store or value)."
  [st]
  (let [vec (v/vector st 1 2 3)
        m (v/map vec
                 "hello" (v/i64 vec 42)
                 "vec" vec)]
    {:vec vec :map m}))

(defn -main
  [& _args]
  (let [{:keys [vec map]} (hello (store/mem))
        vh (v/hash vec)
        mh (v/hash map)]
    (println "Dacite Hello World")
    (println "  vector count :" (v/count vec))
    (println "  vector hash  :" (store/hash->hex vh))
    (println "  map count    :" (v/count map))
    (println "  map hash     :" (store/hash->hex mh))
    (println "  (get m \"hello\") realized:"
             (v/realize (v/get map "hello")))
    (println "Done.")))
