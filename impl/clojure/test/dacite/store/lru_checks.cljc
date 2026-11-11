(ns dacite.store.lru-checks
  "LRU store checks that run on the JVM and on nbb.

   `LruStore` used to key its map by the hash vector. The old `lru_test`
   uses small integers, and those are fine map keys on both hosts, so it
   passed on nbb. A real hash on nbb is a vector of BigInts. An array map
   holds eight of those. The ninth `s-put` promotes the map to a hash map,
   and that throws: SCI will not hash a BigInt. `MemStore` already looks
   a hash up by `mkey` (the vector on the JVM, the canonical hex on
   ClojureScript). These checks put nine real hashes, which is the put
   that used to throw, and they check eviction, delete, reset, and sweep
   on that map.

   The JVM runs them from `dacite.store.lru-test`. nbb runs them from
   `dacite.store.lru-cljs-test` (`npm run lru:test`).

   Each check returns a vector of problem strings, empty when it holds.
   Nothing here waits on a clock. A check takes milliseconds."
  (:require [dacite.store :as store]
            [dacite.store.lru :as lru]
            [dacite.value :as v]))

(defn- diff
  [label expected actual]
  (when-not (= expected actual)
    [(str label ": expected " (pr-str expected) ", got " (pr-str actual))]))

(def ^:private promote-at
  "Pairs an array map holds before the next assoc builds a hash map."
  8)

(defn- real-hashes
  "One more content hash than `promote-at`. The last of these is the put
   that promotes the map."
  []
  (let [src (store/mem-store)]
    (mapv (fn [n] (v/hash (v/i64 src n))) (range (inc promote-at)))))

(defn lru-eviction-test
  []
  (let [s (lru/lru-store 2)
        h1 [1 0 0 0]
        h2 [2 0 0 0]
        h3 [3 0 0 0]]
    (store/await (store/s-put s h1 :a))
    (store/await (store/s-put s h2 :b))
    (store/await (store/s-get s h1))
    (store/await (store/s-put s h3 :c))
    (concat
     (diff "keeps the touched hash" true (store/await (store/s-has? s h1)))
     (diff "evicts the least recent" false (store/await (store/s-has? s h2)))
     (diff "keeps the new hash" true (store/await (store/s-has? s h3))))))

(defn lru-read-through-touch-test
  []
  (let [s (lru/lru-store 2)
        h1 [1 0 0 0]
        h2 [2 0 0 0]
        h3 [3 0 0 0]]
    (store/await (store/s-put s h1 :a))
    (store/await (store/s-put s h2 :b))
    (concat
     (diff "get" :a (store/await (store/s-get s h1)))
     (do
       (store/await (store/s-put s h3 :c))
       (concat
        (diff "touched hash stays" true (store/await (store/s-has? s h1)))
        (diff "untouched hash is evicted" false (store/await (store/s-has? s h2))))))))

(defn lru-delete-test
  []
  (let [s (lru/lru-store 10)
        h [1 0 0 0]]
    (store/await (store/s-put s h :x))
    (store/await (store/s-delete s h))
    (diff "deleted" false (store/await (store/s-has? s h)))))

(defn lru-real-hash-roundtrip-test
  []
  (let [hs (real-hashes)
        s (lru/lru-store 0)]
    (doseq [[i h] (map-indexed vector hs)]
      (store/await (store/s-put s h i)))
    ;; Bindings run in order, so the gets finish before delete and reset.
    (let [stored (into []
                       (mapcat (fn [i]
                                 (let [h (nth hs i)]
                                   (concat
                                    (diff (str "get " i) i (store/await (store/s-get s h)))
                                    (diff (str "has " i) true (store/await (store/s-has? s h)))))))
                       (range (count hs)))
          missing (v/hash (v/i64 (store/mem-store) 99))
          missed (concat
                  (diff "miss" nil (store/await (store/s-get s missing)))
                  (diff "miss has" false (store/await (store/s-has? s missing))))
          gone (nth hs 3)
          _ (store/await (store/s-delete s gone))
          deleted (concat
                   (diff "deleted" false (store/await (store/s-has? s gone)))
                   (diff "neighbor" 4 (store/await (store/s-get s (nth hs 4)))))
          _ (store/await (store/s-reset s))
          reset (diff "reset" false (store/await (store/s-has? s (nth hs 0))))]
      (into [] cat [stored missed deleted reset]))))

(defn lru-real-hash-eviction-test
  []
  (let [hs (real-hashes)
        extra (v/hash (v/i64 (store/mem-store) 100))
        s (lru/lru-store (count hs))
        oldest (nth hs 0)
        second (nth hs 1)
        newest (peek hs)]
    (doseq [[i h] (map-indexed vector hs)]
      (store/await (store/s-put s h i)))
    (store/await (store/s-get s oldest))
    (store/await (store/s-put s extra :extra))
    (concat
     (diff "oldest stays" 0 (store/await (store/s-get s oldest)))
     (diff "second evicted" false (store/await (store/s-has? s second)))
     (diff "newest stays" (dec (count hs)) (store/await (store/s-get s newest)))
     (diff "extra" :extra (store/await (store/s-get s extra))))))

(defn lru-real-hash-sweep-test
  []
  (let [hs (real-hashes)
        s (lru/lru-store 0)
        keep (nth hs 0)
        gone (nth hs 1)]
    (doseq [h hs]
      (store/await (store/s-put s h :v)))
    (let [{:keys [removed]}
          (store/await (store/sweep-unmarked s #{(store/hash->hex keep)}))]
      (concat
       (diff "removed" (dec (count hs)) removed)
       (diff "kept" :v (store/await (store/s-get s keep)))
       (diff "dropped" false (store/await (store/s-has? s gone)))))))

(def checks
  {"lru-eviction-test" lru-eviction-test
   "lru-read-through-touch-test" lru-read-through-touch-test
   "lru-delete-test" lru-delete-test
   "lru-real-hash-roundtrip-test" lru-real-hash-roundtrip-test
   "lru-real-hash-eviction-test" lru-real-hash-eviction-test
   "lru-real-hash-sweep-test" lru-real-hash-sweep-test})

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
