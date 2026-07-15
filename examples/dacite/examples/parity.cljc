(ns dacite.examples.parity
  "Canonical value whose root hash must be identical on every host (JVM,
   babashka, nbb) and every future language port. Run it on each host and
   compare the printed hex — that is the cross-host hash-parity check.

   It only touches the portable core (store / collections / api / hash),
   never the JVM-only namespaces (value, core, convert, json, render)."
  (:require [dacite.store :as store]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.hash :as hash]))

(defn canonical-value
  "Build a fixed, representative nested value in the given store."
  [st]
  (store/bind-store st
                    (coll/vector 0 1 -1 255 256
                                 (coll/string "hello")
                                 (coll/hash-map "k" 42 "nested" (coll/vector 7 8 9))
                                 (coll/dacite-set 1 2 3))))

(defn canonical-hex
  "Root hash (64-char hex) of the canonical value."
  []
  (hash/hash->hex (types/dacite-hash (canonical-value (store/mem-store)))))

(defn -main [& _]
  (println (canonical-hex)))
