(ns bench-ops
  "End-to-end timings of real operations: build a value and take its hash. The
   numbers in docs/design/fuse-sl2.md come from this file. Warm up 3 runs, then
   report the median and the minimum of 7. Run, from impl/clojure:

       clojure -M:dev -m bench-ops                      # JVM
       cd ../.. && node_modules/.bin/nbb -cp impl/clojure/src:impl/clojure/dev -m bench-ops   # nbb

   Pass row-name substrings to run only matching rows:
       clojure -M:dev -m bench-ops shared

   Not a test: it takes about 30 s on the JVM and several minutes on nbb."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn now-ms [] #?(:clj (/ (System/nanoTime) 1e6) :cljs (js/performance.now)))

(defn blob-bytes [n]
  #?(:clj (let [b (byte-array n)] (dotimes [i n] (aset b i (unchecked-byte (* 31 i)))) b)
     :cljs (let [b (js/Uint8Array. n)] (dotimes [i n] (aset b i (mod (* 31 i) 256))) b)))

(def ref-sets
  "Distinct refs for the ref->state row, made once, outside the timing."
  (delay
    (let [mk (fn [tag n]
               (mapv (fn [i] (hash/state->ref (hash/fuse-str (str tag i)))) (range n)))]
      {:hot (mk "h" 20000) :cold (mk "c" 100000)})))

(def ops
  [["map 3,000 i64 entries, build"
    (fn [st] (v/hash (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 7 i))]) (range 3000)))))]
   ["vector 10,000 i64, build"
    (fn [st] (v/hash (apply v/vector st (map #(v/i64 st %) (range 10000)))))]
   ["string 100,000 chars"
    (fn [st] (v/hash (v/string st (apply str (take 100000 (cycle "abcdefghij"))))))]
   ["blob 500 KB"
    (fn [st] (v/hash (v/blob st (blob-bytes 512000))))]
   ["500 assocs on a 1,000-entry map"
    (fn [st]
      (let [m (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st i)]) (range 1000)))
            t (now-ms)
            r (reduce (fn [m i] (v/assoc m (v/i64 st (* 2 i)) (v/i64 st (- i)))) m (range 500))]
        (v/hash r)))]
   ;; Structure shared across builds (issue #17): one store, so a node built
   ;; once is built again by a later build.
   ["shared: 20 maps, each 1 assoc on one 2,000-entry map"
    (fn [st]
      (let [m (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st i)]) (range 2000)))]
        (doseq [j (range 20)]
          (v/hash (v/assoc m (v/i64 st (+ 5000 j)) (v/i64 st j))))))]
   ["shared: one 1,500-entry map built 5 times"
    (fn [st]
      (dotimes [_ 5]
        (v/hash (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 3 i))]) (range 1500))))))]
   ["shared: 400 assocs, hashing after each, on a 1,000-entry map"
    (fn [st]
      (let [m (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st i)]) (range 1000)))]
        (reduce (fn [m i] (let [m' (v/assoc m (v/i64 st (* 2 i)) (v/i64 st (- i)))]
                            (v/hash m') m'))
                m (range 400))))]
   ["shared: 300 conj on a 3,000-element vector, hashing after each"
    (fn [st]
      (let [vec0 (apply v/vector st (map #(v/i64 st %) (range 3000)))]
        (reduce (fn [x i] (let [x' (v/conj x (v/i64 st (+ 10000 i)))] (v/hash x') x'))
                vec0 (range 300))))]
   ;; The ref->state cache (issue #17): 20,000 hot refs read between the 100,000
   ;; once-seen refs of a stream, with a limit of 65,536 entries. A cache that
   ;; empties wholesale loses the hot set each time it fills.
   ["ref->state: 20,000 hot refs + 100,000-ref cold stream"
    (fn [_]
      (let [{:keys [hot cold]} @ref-sets]
        (doseq [i (range (count cold))]
          (hash/ref->state (nth cold i))
          (hash/ref->state (nth hot (mod (* 7 i) (count hot)))))))]])

(defn run-op [f]
  (let [t (now-ms)] (f (store/mem-store)) (- (now-ms) t)))

(defn -main [& args]
  (when (seq args) (println "rows matching" (pr-str args)))
  (when (some #(re-find #"ref->state" (first %)) (filter (fn [[l _]] (or (empty? args) (some #(str/includes? l %) args))) ops))
    @ref-sets)
  (doseq [[label f] (if (seq args)
                      (filter (fn [[l _]] (some #(str/includes? l %) args)) ops)
                      ops)]
    (dotimes [_ 3] (run-op f))          ; warm up
    (let [ts (sort (repeatedly 7 #(run-op f)))
          med (nth ts 3)]
      (println (str label ": median " (Math/round (double med)) " ms, min " (Math/round (double (first ts))) " ms")))))
