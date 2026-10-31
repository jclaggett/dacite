;; Time every test in the repo, slowest first. Run from impl/clojure:
;;
;;   clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
;;           -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
;;           -M:dev -i dev/time_tests.clj
;;
;; Rule (CONTRIBUTING.md): a test over about 3 s is broken unless it has a
;; comment starting with JUSTIFICATION. Each line is "seconds ns/test assertions".
(require 'clojure.test 'clojure.java.io 'clojure.string)

(def nss
  (->> (file-seq (clojure.java.io/file "test"))
       (filter #(re-find #"_test\.cljc?$" (.getName %)))
       (remove #(re-find #"cljs" (.getName %)))
       (map #(-> (.getPath %)
                 (subs 5)
                 (clojure.string/replace #"\.cljc?$" "")
                 (clojure.string/replace "/" ".")
                 (clojure.string/replace "_" "-")
                 symbol))
       sort))

(doseq [n nss] (require n))

(let [rows (for [n nss
                 [nm var] (sort-by key (ns-interns n))
                 :when (:test (meta var))]
             (let [ctr (ref clojure.test/*initial-report-counters*)
                   t0 (System/nanoTime)]
               (binding [clojure.test/*report-counters* ctr
                         clojure.test/*test-out* (java.io.StringWriter.)]
                 (clojure.test/test-vars [var]))
               [(/ (- (System/nanoTime) t0) 1e9) (str n "/" nm)
                (+ (:pass @ctr) (:fail @ctr) (:error @ctr))]))]
  (println "\n=== slowest tests ===")
  (doseq [[secs nm n] (take 15 (sort-by first > rows))]
    (println (format "%8.2f s  %s  (%d assertions)" secs nm n)))
  (println (format "\ntests %d, assertions %d, sum %.1f s"
                   (count rows) (reduce + (map #(nth % 2) rows))
                   (reduce + (map first rows)))))
(shutdown-agents)
