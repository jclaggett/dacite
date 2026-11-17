(ns dacite.value.path-test
  "Field access and path updates on the JVM.

   The bodies are `dacite.value.path-checks`. nbb runs the same checks
   (`npm run value:test`). One deftest each, so the JVM suite still names
   the test it ran before. `pr-str-renders-long-strings` keeps one JVM-only
   assertion: `v/pr-str` of a vector renders its elements through
   `dacite.value.render`, which is .clj."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v]
            [dacite.value.path-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest native-field-access
  (run-check "native-field-access"))

(deftest string-char-limit
  (run-check "string-char-limit"))

(deftest pr-str-renders-long-strings
  (run-check "pr-str-renders-long-strings")
  ;; JVM only: the collection render is dacite.value.render (.clj).
  (is (re-find #"\[1 2 3\]" (v/pr-str (v/vector (store/mem-store) 1 2 3)))))

(deftest get-in-assoc-in
  (run-check "get-in-assoc-in"))

(deftest update-and-update-in
  (run-check "update-and-update-in"))

(deftest slice-shares-leaves-and-bounds
  (run-check "slice-shares-leaves-and-bounds"))

(deftest slice-string-preserves-type-and-hash
  (run-check "slice-string-preserves-type-and-hash"))

(deftest slice-blob-preserves-type-and-hash
  (run-check "slice-blob-preserves-type-and-hash"))

(deftest as-bytes-blob
  (run-check "as-bytes-blob"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.path-test)))))
               "every-check-has-a-deftest"))
      "value/path_checks.cljc and this file list the same tests"))
