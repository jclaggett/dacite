(ns dacite.store.pack-test
  "Pack Layer-1 tests on the JVM.

   The bodies are `dacite.store.pack-checks`. nbb runs the same checks
   (`npm run store-units:test`). One deftest each, so the JVM suite still
   names the test it ran before. The seven deftests below the wrappers stay
   JVM only: they start the HTTP service (`dacite.service`) or read through
   `dacite.store.remote`, and both are JVM code."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.todo :as todo]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.pack :as pack]
            [dacite.store.pack-checks :as checks]
            [dacite.store.remote :as remote]
            [dacite.store.stats :as stats]
            [dacite.value :as v]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.wire :as wire]))

(defrecord CountingChunkTransport [inner counter]
  pack/IChunkTransport
  (send-chunk! [_ chunk]
    (swap! counter inc)
    (pack/send-chunk! inner chunk)))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest pack-items-soft-budget
  (run-check "pack-items-soft-budget"))

(deftest apply-chunk-puts-nodes
  (run-check "apply-chunk-puts-nodes"))

(deftest literal-of-and-materialize-scalars
  (run-check "literal-of-and-materialize-scalars"))

(deftest literal-of-and-materialize-string
  (run-check "literal-of-and-materialize-string"))

(deftest literal-of-medium-string
  (run-check "literal-of-medium-string"))

(deftest literal-of-vector-is-recursive-typed
  (run-check "literal-of-vector-is-recursive-typed"))

(deftest literal-of-nested-vector-of-strings
  (run-check "literal-of-nested-vector-of-strings"))

(deftest rle-lits-n1-stays-unwrapped
  (run-check "rle-lits-n1-stays-unwrapped"))

(deftest rle-lits-mixed-types-split-runs
  (run-check "rle-lits-mixed-types-split-runs"))

(deftest rle-char-run-on-string-spine
  (run-check "rle-char-run-on-string-spine"))

(deftest rle-all-equal-chars-are-repeat
  (run-check "rle-all-equal-chars-are-repeat"))

(deftest rle-mixed-chars-are-run-not-split-repeats
  (run-check "rle-mixed-chars-are-run-not-split-repeats"))

(deftest rle-bool-vector-all-false-is-repeat
  (run-check "rle-bool-vector-all-false-is-repeat"))

(deftest rle-char-run-wire-round-trip
  (run-check "rle-char-run-wire-round-trip"))

(deftest literal-of-map-and-set
  (run-check "literal-of-map-and-set"))

(deftest literal-of-empty-collections
  (run-check "literal-of-empty-collections"))

(deftest literal-of-spine-is-intermediate-form
  (run-check "literal-of-spine-is-intermediate-form"))

(deftest encode-and-apply-literal-scalars
  (run-check "encode-and-apply-literal-scalars"))

(deftest encode-and-apply-literal-string
  (run-check "encode-and-apply-literal-string"))

(deftest encode-and-apply-medium-string-literal
  (run-check "encode-and-apply-medium-string-literal"))

(deftest encode-and-apply-literal-vector-map-set
  (run-check "encode-and-apply-literal-vector-map-set"))

(deftest materialize-accepts-2b-flat-host-bodies
  (run-check "materialize-accepts-2b-flat-host-bodies"))

(deftest literal-hash-mismatch-throws
  (run-check "literal-hash-mismatch-throws"))

(deftest encode-reachable-prefers-literal-and-skips-children
  (run-check "encode-reachable-prefers-literal-and-skips-children"))

(deftest ft-internal-nodes-may-be-intermediate-literals
  (run-check "ft-internal-nodes-may-be-intermediate-literals"))

(deftest todo-seed-round-trips-as-literals
  (run-check "todo-seed-round-trips-as-literals"))

(deftest multi-page-blob-round-trips-as-byte-runs
  (run-check "multi-page-blob-round-trips-as-byte-runs"))

(deftest large-string-refuses-literal-at-default-budget
  (run-check "large-string-refuses-literal-at-default-budget"))

(deftest large-string-literal-when-budget-allows
  (run-check "large-string-literal-when-budget-allows"))

(deftest large-vector-mixed-node-and-child-literals
  (run-check "large-vector-mixed-node-and-child-literals"))

(deftest size-bytes-at-budget-is-literal-one-over-is-node
  (run-check "size-bytes-at-budget-is-literal-one-over-is-node"))

(deftest type-run-of-short-strings-fits-tight-budget
  (run-check "type-run-of-short-strings-fits-tight-budget"))

(deftest encode-summary-reports-mixed-stats
  (run-check "encode-summary-reports-mixed-stats"))

(deftest intermediate-ft-digit-round-trip
  (run-check "intermediate-ft-digit-round-trip"))

(deftest intermediate-ft-deep-round-trip
  (run-check "intermediate-ft-deep-round-trip"))

(deftest intermediate-literal-bottom-out-reduces-items
  (run-check "intermediate-literal-bottom-out-reduces-items"))

(deftest intermediate-hamt-entry-round-trip
  (run-check "intermediate-hamt-entry-round-trip"))

(deftest pack-under-bfs-single-chunk
  (run-check "pack-under-bfs-single-chunk"))

(deftest long-string-pack-covers-explorer-preview-prefix
  (run-check "long-string-pack-covers-explorer-preview-prefix"))

(deftest pack-under-budget-zero-is-single-item
  (run-check "pack-under-budget-zero-is-single-item"))

(deftest find-chunk-transport-outermost-wins
  (run-check "find-chunk-transport-outermost-wins"))

(deftest intermediate-hamt-bitmap-round-trip-when-assured
  (run-check "intermediate-hamt-bitmap-round-trip-when-assured"))

(deftest host-string-is-not-truncated
  (run-check "host-string-is-not-truncated"))

;; JVM only. See the namespace docstring.

(deftest service-post-nodes-chunk
  (let [rooted (svc/make-demo-rooted)
        h (store/hex->hash (apply str (repeat 64 "a")))
        chunk (pack/make-chunk 1024 [(pack/node-item h ["i64" 99])])
        resp (svc/handle-request rooted "POST" "/nodes" (wire/write-edn chunk))]
    (is (= 200 (:status resp)))
    (is (true? (:ok (wire/read-edn (:body resp)))))
    (is (= 1 (:applied (wire/read-edn (:body resp)))))
    (is (= ["i64" 99] (store/await (store/s-get rooted h))))))

(deftest service-post-nodes-literal
  (let [rooted (svc/make-demo-rooted)
        src (store/mem-store)
        s (coll/string-with-store src "todo")
        h (types/dacite-hash s)
        item (pack/encode-item src h (store/await (store/s-get src h)))
        chunk (pack/make-chunk 1024 [item])
        resp (svc/handle-request rooted "POST" "/nodes" (wire/write-edn chunk))]
    (is (= :literal (:encoding item)))
    (is (= 200 (:status resp)))
    (is (= 1 (:literals (wire/read-edn (:body resp)))))
    (is (store/await (store/s-has? rooted h)))))

(deftest write-back-flush-uses-fewer-http-requests-than-per-node
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (stats/reset-stats!)
      (let [raw (remote/remote-store base-url)
            wb (client-cache/wrap raw :write-back)
            t (todo/build wb (todo/seed-items))
            h (types/dacite-hash t)
            before (stats/get-stats)
            _ (remote/remote-cas-root! wb nil h)
            after (stats/get-stats)
            d (stats/stats-diff before after)
            kinds (:by-kind after {})]
        (is (true? (store/await (store/s-has? raw h))))
        (is (pos? (:requests d)))
        (is (pos? (get kinds :nodes-put 0)))
        (is (< (get kinds :node-put 0) 5)
            "seed flush should not issue many single-node PUTs")
        (is (< (:requests d) 40)
            "literal+chunked write-back seed should be far fewer than 2a ~35"))
      (finally
        (stop!)))))

(deftest remote-s-get-pack-fill
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            t (todo/build raw (todo/seed-items))
            h (types/dacite-hash t)
            _ (remote/remote-cas-root! raw nil h)
            cold (remote/remote-store base-url)]
        (stats/reset-stats!)
        (let [before (stats/get-stats)
              node (store/await (store/s-get cold h))
              after (stats/get-stats)
              d (stats/stats-diff before after)
              kinds (:by-kind after {})]
          (is (some? node))
          (is (= "vector" (types/entry-type node)))
          (is (pos? (get kinds :node-get 0)))
          (is (< (:requests d) 5)
              "pack-filled root get should not fan out many requests")
          ;; Further access hits pack-local
          (stats/reset-stats!)
          (store/await (store/s-get cold h))
          (is (zero? (:requests (stats/get-stats))))))
      (finally
        (stop!)))))

(deftest flush-from-and-middleware-see-every-chunk
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            n (atom 0)
            counted (->CountingChunkTransport raw n)
            local (store/mem-store)
            t (todo/build local (todo/seed-items))
            h (types/dacite-hash t)
            result (pack/flush-from! counted local h #{})]
        (is (pos? (:items result)))
        (is (pos? (:chunks result)))
        (is (= (:chunks result) @n)
            "every sealed chunk hits outermost send-chunk!")
        (is (store/await (store/s-has? raw h)))
        ;; Second flush skips covered hashes
        (reset! n 0)
        (let [r2 (pack/flush-from! counted local h (:covered result))]
          (is (zero? (:items r2)))
          (is (zero? @n))))
      (finally
        (stop!)))))

(deftest write-back-flush-uses-flush-from
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            n (atom 0)
            counted (->CountingChunkTransport raw n)
            wb (client-cache/wrap counted :write-back)
            t (todo/build wb (todo/seed-items))
            h (types/dacite-hash t)
            uploaded (client-cache/flush-reachable! wb h)]
        (is (pos? uploaded))
        (is (pos? @n) "write-back flush sends via IChunkTransport middleware")
        (is (store/await (store/s-has? raw h)))
        (is (zero? (client-cache/flush-reachable! wb h))
            "second flush is a no-op"))
      (finally
        (stop!)))))

(deftest long-todo-title-flushes-over-http
  (let [title (apply str (repeat 400 "and going "))
        rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [r (v/root (store/remote-rooted-store base-url {:policy :write-back}))]
        (v/cas! r nil (todo/empty-todos r))
        (v/swap! r todo/add-todo title false)
        (let [cold (v/root (store/remote-rooted-store base-url {:policy :none}))
              t (v/deref cold)]
          (is (= 1 (v/count t)))
          (is (= (count title) (count (todo/title-str (v/nth t 0)))))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.pack-test)))))
               "every-check-has-a-deftest"
               "service-post-nodes-chunk"
               "service-post-nodes-literal"
               "write-back-flush-uses-fewer-http-requests-than-per-node"
               "remote-s-get-pack-fill"
               "flush-from-and-middleware-see-every-chunk"
               "write-back-flush-uses-flush-from"
               "long-todo-title-flushes-over-http"))
      "pack_checks.cljc and this file list the same tests"))
