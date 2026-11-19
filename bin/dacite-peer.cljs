;; Loaded by bin/dacite.mjs with nbb's loadFile, so npm requires resolve
;; from this package (not the caller's working directory). The value of
;; this file is what the bin calls: `start` (the peer) and `run` (the run
;; file and port reuse, `dacite.cli.run/bin-api`).
(ns dacite-peer
  (:require [dacite.cli.peer :as peer]
            [dacite.cli.run :as run]))

#js {:start peer/start! :run (run/bin-api)}
