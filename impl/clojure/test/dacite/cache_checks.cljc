(ns dacite.cache-checks
  "Portable checks of `dacite.cache` and of the `ref->state` cache built on it,
   shared by the JVM test (cache_test.clj) and the nbb test (peer-cljs-test).
   `problems` returns a vector of strings, empty when everything holds."
  (:require [dacite.cache :as cache]
            [dacite.hash :as hash]))

(defn- ks [c] (cache/lru-keys c))

(defn lru-problems []
  (let [bad (fn [what expected actual]
              (when-not (= expected actual)
                [(str "cache " what ": expected " (pr-str expected) ", got " (pr-str actual))]))
        thrown? (fn [f] (try (f) false (catch #?(:clj Throwable :cljs :default) _ true)))
        c (cache/lru 3)]
    (concat
     (bad "refuses limit 0" true (thrown? #(cache/lru 0)))
     (bad "refuses a non-integer limit" true (thrown? #(cache/lru "3")))
     (bad "empty" 0 (cache/size c))
     (bad "miss is not-found" :nf (cache/lookup c "a" :nf))
     (do (cache/put! c "a" 1) (cache/put! c "b" 2) (cache/put! c "c" 3) nil)
     (bad "holds up to the limit" ["a" "b" "c"] (ks c))
     ;; a 4th entry evicts exactly one: the least recent, "a"
     (do (cache/put! c "d" 4) nil)
     (bad "bound after one over" 3 (cache/size c))
     (bad "evicts the least recent only" ["b" "c" "d"] (ks c))
     (bad "evicted key is gone" :nf (cache/lookup c "a" :nf))
     (bad "kept keys survive" [2 3 4] (mapv #(cache/lookup c %) ["b" "c" "d"]))
     ;; reading "b" made it the most recent, so the next eviction takes "c"
     (do (cache/lookup c "b") (cache/put! c "e" 5) nil)
     (bad "a read protects its entry" ["d" "b" "e"] (ks c))
     ;; writing an existing key replaces and refreshes it: no growth, no eviction
     (do (cache/put! c "d" 40) nil)
     (bad "rewrite keeps size" 3 (cache/size c))
     (bad "rewrite replaces and refreshes" ["b" "e" "d"] (ks c))
     (bad "rewritten value" 40 (cache/lookup c "d"))
     ;; nil and false are values, not misses
     (do (cache/put! c "n" nil) (cache/put! c "f" false) nil)
     (bad "nil is cached (a hit, not not-found)" nil (cache/lookup c "n" :nf))
     (bad "false is cached" false (cache/lookup c "f" :nf))
     (do (cache/clear! c) nil)
     (bad "clear" 0 (cache/size c))
     ;; the bound holds over a long run
     (let [c (cache/lru 50)]
       (dotimes [i 1000] (cache/put! c (str "k" i) i))
       (concat (bad "bound over 1000 puts" 50 (cache/size c))
               (bad "newest 50 kept" (mapv #(str "k" %) (range 950 1000)) (ks c))))
     ;; memo computes once per resident key, and again only after eviction
     (let [c (cache/lru 2) n (atom 0)
           f (fn [k] (cache/memo c k (fn [] (swap! n inc) (str k "!"))))]
       (concat (bad "memo value" "x!" (f "x"))
               (bad "memo hit" "x!" (f "x"))
               (bad "memo computed once" 1 @n)
               (do (f "y") (f "z") nil)       ; evicts "x"
               (bad "memo recomputes an evicted key" "x!" (f "x"))
               (bad "memo count after eviction" 4 @n))))))

(defn ref-state-problems []
  (let [bad (fn [what expected actual]
              (when-not (= expected actual)
                [(str "ref->state " what ": expected " (pr-str expected) ", got " (pr-str actual))]))
        refs (mapv (fn [i] (hash/state->ref (hash/fuse-str (str "ref" i)))) (range 12))
        c (cache/lru 4)
        direct (fn [r] (hash/fuse-bytes (hash/longs->bytes r)))]
    (concat
     (bad "limit is the documented 65,536" 65536 hash/ref-state-cache-limit)
     ;; every answer equals the uncached product, hit or miss, at any cache size
     (mapcat (fn [r] (bad "equals fuse-bytes of the ref" (direct r) (hash/ref->state-in c r))) refs)
     (mapcat (fn [r] (bad "again, after evictions" (direct r) (hash/ref->state-in c r))) refs)
     (bad "bounded" 4 (cache/size c))
     (bad "holds the 4 most recent refs" 4 (count (ks c)))
     (mapcat (fn [r] (bad "global cache" (direct r) (hash/ref->state r))) refs)
     (when-not (<= (hash/ref-state-cache-size) hash/ref-state-cache-limit)
       ["ref->state: global cache over its limit"]))))

(defn problems []
  (vec (concat (lru-problems) (ref-state-problems))))
