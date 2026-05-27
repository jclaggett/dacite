(ns examples.config
  "Example config manager client using an in-memory Dacite store.

  Goal: Demonstrate a small client that works against a local in-memory
  store today, and can later be pointed at a remote store with minimal
  (ideally zero) changes to the client logic.

  This file is intentionally written against the *desired* public API
  so it can serve as a foil for implementation work.")
(:require [dacite.store :as store]
          [dacite.core :as d]))

(defn make-config-store []
  (store/mem-store))

(defn init-config! [store]
  (let [cfg (d/hash-map store
              "theme"    "dark"
              "timeout"  30
              "features" (d/vector store "a" "b"))]
    (reset! store cfg)
    cfg))

(defn get-config [store]
  @store)

(defn update-config! [store k v]
  (swap! store assoc k v))

(comment
  ;; Usage
  (def store (make-config-store))
  (init-config! store)
  ;; => #dacite/map{"theme" "dark", ...}

  (update-config! store "timeout" 60)
  (get-config store)
  ;; => updated map value (new hash, same store)
  )