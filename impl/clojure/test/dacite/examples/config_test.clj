(ns dacite.examples.config-test
  "Config: the same domain against mem, file, and HTTP on the JVM.

   The bodies are `dacite.examples.config-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   `two-remote-clients-same-hash` stays JVM only: it starts the HTTP
   service and reads through `store/remote` (legacy HTTP, JVM code)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.config :as cfg]
            [dacite.examples.config-checks :as checks]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest domain-on-mem
  (run-check "domain-on-mem"))

(deftest file-reopen-same-hash
  (run-check "file-reopen-same-hash"))

(deftest parse-path-and-cli-value
  (run-check "parse-path-and-cli-value"))

(deftest two-remote-clients-same-hash
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root (store/remote base-url))
            reader (v/root (store/remote base-url))]
        (is (nil? (v/deref writer)))
        (let [[seeded seeded?] (cfg/load-or-seed! writer)]
          (is (true? seeded?))
          (is (= "dark" (cfg/theme seeded))))
        (v/swap! writer cfg/set-path ["timeout"] 90)
        (v/swap! writer cfg/add-feature "remote")
        (let [w (v/deref writer)
              r (v/deref reader)]
          (is (some? r) "reader materializes the remote root")
          (is (= (v/hash w) (v/hash r)))
          (is (= 90 (cfg/timeout r)))
          (is (= "remote" (v/native (v/nth (cfg/features r) 2))))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.config-test)))))
               "every-check-has-a-deftest"
               "two-remote-clients-same-hash"))
      "examples/config_checks.cljc and this file list the same tests"))
