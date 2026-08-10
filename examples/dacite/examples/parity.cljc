(ns dacite.examples.parity
  "Canonical value whose root hash must be identical on every host (JVM,
   babashka, nbb) and every future language port. Run it on each host and
   compare the printed hex — that is the cross-host hash-parity check.

   Uses only the public portable namespaces: dacite.store + dacite.value."
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn canonical-value
  "Build a fixed, representative nested value in the given store."
  [st]
  (store/bind-store st
                    (v/vector 0 1 -1 255 256
                              (v/string "hello")
                              (v/hash-map "k" 42 "nested" (v/vector 7 8 9))
                              (v/set 1 2 3))))

(defn canonical-hex
  "Root hash (64-char hex) of the canonical value."
  []
  (store/hash->hex (v/dacite-hash (canonical-value (store/mem-store)))))

(defn -main [& _]
  (println (canonical-hex)))
