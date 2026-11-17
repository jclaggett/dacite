(ns dacite.wire.binary-cljs-test
  "nbb side of wire/binary_test. Run from the repo root:

     npm run wire:test

   The checks are `dacite.wire.binary-checks`; the JVM runs the same checks
   from dacite.wire.binary-test."
  (:require [clojure.string :as str]
            [dacite.store.check :as check]
            [dacite.wire.binary :as bin]
            [dacite.wire.binary-checks :as checks]))

(def ^:private fs (js/require "fs"))

(def ^:private root "fixtures/wire-v1")

(defn- slurp-trim [path] (str/trim (.readFileSync fs path "utf8")))

(defn- fixture-ids []
  (let [cases (str root "/cases")]
    (->> (.readdirSync fs cases)
         (filter #(.isDirectory (.statSync fs (str cases "/" %))))
         sort
         vec)))

(defn- load-fixture [case-id]
  (let [dir (str root "/cases/" case-id)
        msg-hex (slurp-trim (str dir "/message.hex"))
        hash-file (str dir "/hash.hex")
        desc-file (str dir "/description.json")]
    {:id case-id
     :description (when (.existsSync fs desc-file) (.readFileSync fs desc-file "utf8"))
     :message-hex msg-hex
     :message-bytes (bin/hex->bytes msg-hex)
     :hash-hex (when (.existsSync fs hash-file) (slurp-trim hash-file))}))

(def env
  "Host pieces the checks take (see dacite.wire.binary-checks). Paths are
   from the repo root."
  {:fixture-ids fixture-ids
   :load-fixture load-fixture
   :manifest #(js->clj (js/JSON.parse (.readFileSync fs (str root "/manifest.json") "utf8"))
                       :keywordize-keys true)})

(defn -main
  [& _]
  (let [rows (mapv #(check/run-one checks/checks env %) (sort (keys checks/checks)))
        bad (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) rows)
        slowest (check/slowest rows)]
    (println (str "wire checks: " (count rows) " tests, " (count bad)
                  " problems, slowest " slowest))
    (doseq [p bad] (println "  FAIL" p))
    (if (seq bad)
      (js/process.exit 1)
      (println "wire tests passed"))))
