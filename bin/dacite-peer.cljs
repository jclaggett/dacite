;; Loaded by bin/dacite.mjs with nbb's loadFile, so npm requires resolve
;; from this package (not the caller's working directory). The value of
;; this file is the peer's start function.
(ns dacite-peer
  (:require [dacite.cli.peer :as peer]))

peer/start!
