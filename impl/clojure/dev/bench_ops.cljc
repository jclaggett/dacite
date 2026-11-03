(ns bench-ops
  "End-to-end timings of real operations: build a value and take its hash. The
   numbers in docs/design/fuse-sl2.md come from this file. Warm up 3 runs, then
   report the median and the minimum of 7. Run, from impl/clojure:

       clojure -M:dev -m bench-ops                      # JVM
       cd ../.. && node_modules/.bin/nbb -cp impl/clojure/src:impl/clojure/dev -m bench-ops   # nbb

   Not a test: it takes about 15 s on the JVM and over a minute on nbb."
  (:require [dacite.store :as store]
            [dacite.value :as v]))

(defn now-ms [] #?(:clj (/ (System/nanoTime) 1e6) :cljs (js/performance.now)))

(defn blob-bytes [n]
  #?(:clj (let [b (byte-array n)] (dotimes [i n] (aset b i (unchecked-byte (* 31 i)))) b)
     :cljs (let [b (js/Uint8Array. n)] (dotimes [i n] (aset b i (mod (* 31 i) 256))) b)))

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
        (v/hash r)))]])

(defn run-op [f]
  (let [t (now-ms)] (f (store/mem-store)) (- (now-ms) t)))

(defn -main [& _]
  (doseq [[label f] ops]
    (dotimes [_ 3] (run-op f))          ; warm up
    (let [ts (sort (repeatedly 7 #(run-op f)))
          med (nth ts 3)]
      (println (str label ": median " (Math/round (double med)) " ms, min " (Math/round (double (first ts))) " ms")))))
