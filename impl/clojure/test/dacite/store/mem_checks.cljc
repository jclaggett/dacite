(ns dacite.store.mem-checks
  "The in-memory store tests from `dacite.store-test` that fail on nbb.

   `s-get`, `s-put`, `s-has?`, and `s-delete` look a hash up by `mkey`: the
   hash vector on the JVM, the canonical hex on ClojureScript. `s-merge` and
   the one-argument `mem-store` used to store the pair's own key. On nbb that
   key is a vector of BigInts, so the later get misses, and a sequence of
   pairs (the form a host uses when that vector cannot be a map key) is not a
   map `merge` can conj. These checks are that behavior. The JVM runs them
   from `dacite.store.mem-test`. nbb runs them from `dacite.store.mem-cljs-test`
   (`npm run mem:test`).

   Each check returns a vector of problem strings, empty when it holds.
   Nothing here waits. A check takes milliseconds."
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn- diff
  [label expected actual]
  (when-not (= expected actual)
    [(str label ": expected " (pr-str expected) ", got " (pr-str actual))]))

(defn mem-store-merge-test
  []
  (concat
   (let [s (store/mem-store)]
     (store/await (store/s-merge s {[1 2 3 4] :a [5 6 7 8] :b}))
     (concat
      (diff "merge map a" :a (store/await (store/s-get s [1 2 3 4])))
      (diff "merge map b" :b (store/await (store/s-get s [5 6 7 8])))))
   ;; A sequence of pairs, not a map. On nbb a hash is a vector of BigInts.
   ;; A small array map can hold one; a hash map cannot, and `merge` does not
   ;; walk a sequence. File, LMDB, and IndexedDB already walk pairs.
   (let [s (store/mem-store)]
     (store/await (store/s-put s [9 9 9 9] :keep))
     (store/await (store/s-merge s [[[1 2 3 4] :a] [[5 6 7 8] :b]]))
     (concat
      (diff "merge pairs a" :a (store/await (store/s-get s [1 2 3 4])))
      (diff "merge pairs b" :b (store/await (store/s-get s [5 6 7 8])))
      (diff "merge keeps what was already stored" :keep
            (store/await (store/s-get s [9 9 9 9])))))
   (let [src (store/mem-store)
         h (v/hash (v/i64 src 42))
         node (store/await (store/s-get src h))
         dest (store/mem-store)]
     (store/await (store/s-merge dest [[h node]]))
     (diff "merge a real hash as one pair" node
           (store/await (store/s-get dest h))))))

(defn mem-store-init-test
  []
  (let [s (store/mem-store {[1 2 3 4] :a})]
    (diff "init map" :a (store/await (store/s-get s [1 2 3 4])))))

(defn layered-store-read-through-test
  []
  (let [fast (store/mem-store)
        slow (store/mem-store {[1 2 3 4] :from-slow})
        s (store/layered-store fast slow)]
    (diff "read through" :from-slow (store/await (store/s-get s [1 2 3 4])))))

(defn layered-store-read-through-backfill-test
  []
  (let [fast (store/mem-store)
        slow (store/mem-store {[1 2 3 4] :from-slow})
        s (store/layered-store fast slow)]
    (concat
     (diff "layered get" :from-slow (store/await (store/s-get s [1 2 3 4])))
     (diff "backfill" :from-slow (store/await (store/s-get fast [1 2 3 4]))))))

(defn layered-store-fast-wins-test
  []
  (let [fast (store/mem-store {[1 2 3 4] :fast})
        slow (store/mem-store {[1 2 3 4] :slow})
        s (store/layered-store fast slow)]
    (diff "fast wins" :fast (store/await (store/s-get s [1 2 3 4])))))

(defn layered-store-has?-test
  []
  (let [fast (store/mem-store)
        slow (store/mem-store {[1 2 3 4] :v})
        s (store/layered-store fast slow)]
    (concat
     (diff "has the seeded hash" true (store/await (store/s-has? s [1 2 3 4])))
     (diff "misses an absent hash" false (store/await (store/s-has? s [9 9 9 9]))))))

(defn layered-store-reads-test
  []
  (let [fast (store/mem-store {[1 2 3 4] :fast})
        slow (store/mem-store {[1 2 3 4] :slow [5 6 7 8] :only-slow})
        s (store/layered-store fast slow)]
    (concat
     (diff "fast layer" :fast (store/await (store/s-get s [1 2 3 4])))
     (diff "slow only" :only-slow (store/await (store/s-get s [5 6 7 8])))
     (diff "has fast" true (store/await (store/s-has? s [1 2 3 4])))
     (diff "has slow" true (store/await (store/s-has? s [5 6 7 8]))))))

(defn layered-store-merge-test
  []
  (let [fast (store/mem-store)
        slow (store/mem-store)
        s (store/layered-store fast slow)]
    (store/await (store/s-merge s {[1 2 3 4] :a [5 6 7 8] :b}))
    (concat
     (diff "fast a" :a (store/await (store/s-get fast [1 2 3 4])))
     (diff "slow b" :b (store/await (store/s-get slow [5 6 7 8]))))))

(def checks
  {"mem-store-merge-test" mem-store-merge-test
   "mem-store-init-test" mem-store-init-test
   "layered-store-read-through-test" layered-store-read-through-test
   "layered-store-read-through-backfill-test" layered-store-read-through-backfill-test
   "layered-store-fast-wins-test" layered-store-fast-wins-test
   "layered-store-has?-test" layered-store-has?-test
   "layered-store-reads-test" layered-store-reads-test
   "layered-store-merge-test" layered-store-merge-test})

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-all
  "Run every check. Returns [{:name :ms :problems}]. A check that throws
   is a problem, and one over `max-ms` is a problem."
  []
  (mapv (fn [[name f]]
          (let [t0 (now-ms)
                problems (try (vec (f))
                              (catch #?(:clj Throwable :cljs :default) e
                                [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
                ms (- (now-ms) t0)]
            {:name name :ms ms
             :problems (cond-> problems
                         (> ms max-ms) (conj (str "took " ms " ms, over " max-ms)))}))
        checks))
