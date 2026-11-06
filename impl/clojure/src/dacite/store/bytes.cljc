(ns dacite.store.bytes
  "Byte sizes of one stored value. Internal to backends that report
   `local-stats`. Not a way to list a store."
  (:require [dacite.wire.binary :as bin]))

(defn edn-bytes
  "EDN size of `v`, matching `pr-str`."
  [v]
  (count (pr-str v)))

(defn wire-bytes
  "Wire size of a node or a pack item. Anything else is its EDN size."
  [v]
  (cond
    (and (map? v) (contains? v :encoding) (contains? v :hash))
    (bin/pack-item-wire-bytes v)

    (and (vector? v) (= 2 (count v)) (string? (first v)))
    (bin/byte-len (bin/encode-node-bytes v))

    :else
    (edn-bytes v)))
