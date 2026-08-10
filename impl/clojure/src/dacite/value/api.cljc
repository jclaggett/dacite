(ns dacite.value.api
  "Deprecated alias of `dacite.value`.

   Prefer `(require '[dacite.value :as v])`. This namespace re-exports the
   portable collection surface for one alpha transition period."
  (:refer-clojure :exclude [count nth get assoc conj seq peek pop keys vals
                            contains? dissoc empty?])
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
