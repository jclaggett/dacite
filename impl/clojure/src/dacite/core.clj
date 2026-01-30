(ns dacite.core
  "Dacite: Data citing with fused hashing.
   
   Core API for working with content-addressed immutable values."
  (:require [dacite.hash :as hash]))

;; TODO: Core API
;; - value->hash: compute hash of any Dacite value
;; - hash->value: retrieve value from storage by hash  
;; - sync: given two root hashes, compute diff
