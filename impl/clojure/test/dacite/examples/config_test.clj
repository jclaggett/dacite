(ns dacite.examples.config-test
  "Remote config: same domain against mem, file, and HTTP.
   Done when two clients see the same config hash."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.config :as cfg]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest domain-on-mem
  (let [r (v/root-ref (cfg/open-mem))
        [seeded seeded?] (cfg/load-or-seed! r)]
    (is (true? seeded?))
    (is (= "dark" (cfg/theme seeded)))
    (is (= 30 (cfg/timeout seeded)))
    (is (= "a" (v/as-str (v/nth (cfg/features seeded) 0))))
    (let [cfg' (v/ref-swap! r cfg/set-path ["timeout"] 60)]
      (is (= 60 (cfg/timeout cfg')))
      (is (= (v/dacite-hash cfg') (v/dacite-hash (v/ref-deref r)))))
    (let [cfg' (v/ref-swap! r cfg/add-feature "c")]
      (is (= 3 (v/count (cfg/features cfg'))))
      (is (= "c" (v/as-str (v/nth (cfg/features cfg') 2)))))))

(deftest file-reopen-same-hash
  (let [dir (io/file (str "target/dacite-config-test-" (System/nanoTime)))]
    (try
      (let [r1 (v/root-ref (cfg/open-file (.getPath dir)))]
        (cfg/load-or-seed! r1)
        (v/ref-swap! r1 cfg/set-path ["theme"] "light")
        (let [h1 (v/dacite-hash (v/ref-deref r1))
              r2 (v/root-ref (cfg/open-file (.getPath dir)))
              loaded (v/ref-deref r2)]
          (is (= h1 (v/dacite-hash loaded)))
          (is (= "light" (cfg/theme loaded)))))
      (finally
        (cfg/reset-store-dir! (.getPath dir))))))

(deftest two-remote-clients-same-hash
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root-ref (store/remote-rooted-store base-url))
            reader (v/root-ref (store/remote-rooted-store base-url))]
        (is (nil? (v/ref-deref writer)))
        (let [[seeded seeded?] (cfg/load-or-seed! writer)]
          (is (true? seeded?))
          (is (= "dark" (cfg/theme seeded))))
        (v/ref-swap! writer cfg/set-path ["timeout"] 90)
        (v/ref-swap! writer cfg/add-feature "remote")
        (let [w (v/ref-deref writer)
              r (v/ref-deref reader)]
          (is (some? r) "reader materializes the remote root")
          (is (= (v/dacite-hash w) (v/dacite-hash r)))
          (is (= 90 (cfg/timeout r)))
          (is (= "remote" (v/as-str (v/nth (cfg/features r) 2)))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (v/ref-reset! writer (cfg/default-config writer)))
            "ref-reset! is local-only on a remote store"))
      (finally
        (stop!)))))

(deftest parse-path-and-cli-value
  (is (= ["theme"] (cfg/parse-path "theme")))
  (is (= ["features" 0] (cfg/parse-path "features.0")))
  (is (= 60 (cfg/parse-cli-value "60")))
  (is (= "light" (cfg/parse-cli-value "light"))))
