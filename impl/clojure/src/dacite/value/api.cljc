(ns dacite.value.api
  "Deprecated alias of `dacite.value`.

   Prefer `(require '[dacite.value :as v])`. This namespace re-exports the
   portable collection surface for one alpha transition period."
  (:refer-clojure :exclude [count nth get assoc conj seq peek pop keys vals
                            contains? dissoc empty? get-in assoc-in update update-in
                            pr-str subvec])
  (:require [dacite.value :as v]))

(def dacite-value? v/dacite-value?)
(def value-type    v/value-type)
(def realize       v/realize)
(def dacite-hash   v/dacite-hash)
(def get-value     v/get-value)
(def count         v/count)
(def empty?        v/empty?)
(def seq           v/seq)
(def nth           v/nth)
(def get           v/get)
(def contains?     v/contains?)
(def assoc         v/assoc)
(def dissoc        v/dissoc)
(def conj          v/conj)
(def peek          v/peek)
(def pop           v/pop)
(def remove-nth    v/remove-nth)
(def keys          v/keys)
(def vals          v/vals)
(def native        v/native)
(def as-str        v/as-str)
(def as-bytes      v/as-bytes)
(def pr-str        v/pr-str)
(def get-in        v/get-in)
(def assoc-in      v/assoc-in)
(def update        v/update)
(def update-in     v/update-in)
(def subvec        v/subvec)
