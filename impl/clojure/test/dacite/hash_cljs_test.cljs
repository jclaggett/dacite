(ns dacite.hash-cljs-test
  "nbb side of hash_test. Run from the repo root:

     npm run hash:test

   The checks are `dacite.hash-checks`; the JVM runs the same checks from
   dacite.hash-test."
  (:require [cljs.reader :as reader]
            [dacite.hash-checks :as checks]
            [dacite.store.check :as check]))

(def ^:private fs (js/require "fs"))

(defn- read-edn [path] (reader/read-string (.readFileSync fs path "utf8")))

(def env
  "Host pieces the checks take (see dacite.hash-checks)."
  {:read-vectors #(read-edn "fixtures/fuse-sl2/vectors.edn")
   :read-small-prime-collision #(read-edn "fixtures/fuse-sl2/small-prime-collision.edn")})

(defn -main
  [& _]
  (let [rows (mapv #(check/run-one checks/checks env % (get checks/slow-ms % check/max-ms))
                   (sort (keys checks/checks)))
        bad (mapcat (fn [{:keys [name problems]}] (map #(str name ": " %) problems)) rows)
        slowest (take 3 (sort-by (comp - :ms) rows))]
    (println (str "hash checks: " (count rows) " tests, " (count bad) " problems, slowest "
                  (apply str (interpose ", " (map #(str (:ms %) " ms (" (:name %) ")") slowest)))))
    (doseq [p bad] (println "  FAIL" p))
    (if (seq bad)
      (js/process.exit 1)
      (println "hash tests passed"))))
