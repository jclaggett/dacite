(ns examples.config
  "Example config manager client using a local rooted Dacite store.

  Goal: Demonstrate a small client that works against a local in-memory
  store today, and can later be pointed at a remote store with minimal
  (ideally zero) changes to the client logic."
  (:require [dacite.rooted-store :as rs]
            [dacite.store :as store]
            [dacite.core :as d]
            [dacite.value :as v]
            [dacite.value.types :as types]))

(defn make-config-store []
  (rs/rooted-store (store/mem-store)))

(defn init-config! [store]
  (let [cfg (d/hash-map-with-store store
                                   "theme"    "dark"
                                   "timeout"  30
                                   "features" (v/vector-with-store store "a" "b"))]
    (reset! store (types/dacite-hash cfg))
    cfg))

(defn get-config [store]
  (when-let [root @store]
    (v/get-value-with-store store root)))

(defn update-config! [store k v]
  (swap! store (fn [root]
                 (let [m (v/get-value-with-store store root)
                       m' (assoc m k v)]
                   (types/dacite-hash m')))))

(comment
  ;; Usage
  (def store (make-config-store))
  (init-config! store)
  ;; => #dacite/map{"theme" "dark", ...}

  (update-config! store "timeout" 60)
  (get-config store)
  ;; => updated map value (new hash, same store)
  )
