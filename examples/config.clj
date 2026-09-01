(ns examples.config
  "Moved: portable config app is `dacite.examples.config`.

   clojure -M:config -- show
   clojure -M:config -- --url http://127.0.0.1:8080 set timeout 60"
  (:require [dacite.examples.config :as cfg]
            [dacite.value :as v]))

(defn make-config-ref
  "Mem rooted store wrapped as a value-level root-ref."
  []
  (v/root (cfg/open-mem)))

(def init-config! cfg/load-or-seed!)
(def get-config v/deref)
(defn update-config! [cfg-ref k val]
  (v/swap! cfg-ref cfg/set-path [k] val))
