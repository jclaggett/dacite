(ns dacite.json
  (:require [cheshire.core :as json]
            [dacite.convert :as convert]))

(defn json->dacite
  "Parse a JSON string into a Dacite value (builds tree in *store*)."
  [json-str]
  (-> json-str json/parse-string convert/clj->dac))

(defn dacite->json
  "Convert a Dacite value to a JSON string."
  ([dac-val]
   (dacite->json dac-val {}))
  ([dac-val opts]
   (-> dac-val convert/dac->clj (json/generate-string opts))))
