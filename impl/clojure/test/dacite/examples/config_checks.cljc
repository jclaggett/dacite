(ns dacite.examples.config-checks
  "Config: the same domain on a mem store and a file store.
   On the JVM and on nbb.

   These were the bodies of `dacite.examples.config-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.config :as cfg]
            [dacite.value :as v]))

(defn domain-on-mem
  [_]
  (let [r (v/root (cfg/open-mem))
        [seeded seeded?] (cfg/load-or-seed! r)]
    (is (true? seeded?))
    (is (= "dark" (cfg/theme seeded)))
    (is (= 30 (cfg/timeout seeded)))
    (is (= "a" (v/native (v/nth (cfg/features seeded) 0))))
    (let [cfg' (v/swap! r cfg/set-path ["timeout"] 60)]
      (is (= 60 (cfg/timeout cfg')))
      (is (= (v/hash cfg') (v/hash (v/deref r)))))
    (let [cfg' (v/swap! r cfg/add-feature "c")]
      (is (= 3 (v/count (cfg/features cfg'))))
      (is (= "c" (v/native (v/nth (cfg/features cfg') 2)))))))

(defn file-reopen-same-hash
  [_]
  (let [dir (str "target/dacite-config-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (let [r1 (v/root (cfg/open-file dir))]
        (cfg/load-or-seed! r1)
        (v/swap! r1 cfg/set-path ["theme"] "light")
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (cfg/open-file dir))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= "light" (cfg/theme loaded)))))
      (finally
        (cfg/reset-store-dir! dir)))))

(defn parse-path-and-cli-value
  [_]
  (is (= ["theme"] (cfg/parse-path "theme")))
  (is (= ["features" 0] (cfg/parse-path "features.0")))
  (is (= 60 (cfg/parse-cli-value "60")))
  (is (= "light" (cfg/parse-cli-value "light"))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"domain-on-mem" domain-on-mem
   "file-reopen-same-hash" file-reopen-same-hash
   "parse-path-and-cli-value" parse-path-and-cli-value})
