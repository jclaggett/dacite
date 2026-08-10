(ns examples.config
  "Example config manager client using a local rooted Dacite store.

  Goal: Demonstrate a small client that works against a local in-memory
  store today, and can later be pointed at a remote store with minimal
  (ideally zero) changes to the client logic.

  Store section creates the rooted store; value section uses root-ref +
  collection ops only."
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn make-config-ref
  "Store wiring: mem rooted store wrapped as a value-level root-ref."
  []
  (v/root-ref (store/rooted-store (store/mem-store))))

(defn init-config!
  "Seed default config into the root-ref. Returns the config value."
  [cfg-ref]
  (let [cfg (v/hash-map-via cfg-ref
                            "theme"    "dark"
                            "timeout"  30
                            "features" (v/vector-via cfg-ref "a" "b"))]
    (v/ref-reset! cfg-ref cfg)
    cfg))

(defn get-config
  "Current config value, or nil if unset."
  [cfg-ref]
  (v/ref-deref cfg-ref))

(defn update-config!
  "Assoc k→val on the current config (CAS-retry via ref-swap!)."
  [cfg-ref k val]
  (v/ref-swap! cfg-ref v/assoc k val))

(comment
  (def cfg (make-config-ref))
  (init-config! cfg)
  (update-config! cfg "timeout" 60)
  (get-config cfg))
