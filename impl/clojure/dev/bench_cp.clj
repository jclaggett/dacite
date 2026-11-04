(ns bench-cp
  "Precise timing of host/cp-offset and host/cp-count against the JDK's own
   String.offsetByCodePoints / codePointCount, on a full 4 KiB page of ASCII,
   mixed BMP and astral text. Off by default (not a test); run with
     cd impl/clojure && clojure -M:dev -m bench-cp
   The regular suite only has a generous 5x guard
   (unicode-test/indexing-inside-a-page-is-not-a-hand-written-scan)."
  (:require [dacite.host :as host]))

(def pages
  {:ascii (apply str (take 4100 (cycle "abcdefghij klmnop")))
   :mixed-bmp (apply str (take 3000 (cycle "aé日本語€ x")))
   :astral (apply str (take 2000 (cycle "\uD83D\uDE00\uD834\uDD1Ea\uD83D\uDE00日")))})

(defn- sum-calls
  "Run (f i) for i < n and sum the results, so the JIT cannot drop the calls."
  [n f]
  (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (long (f i)))) acc)))

(defn- best-ms [f]
  (apply min (repeatedly 7 #(let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))))

(defn -main [& _]
  (doseq [[kind ^String page] pages
          :let [top (.codePointCount page 0 (.length page))
                n 200000]]
    (dotimes [_ 3]
      (dotimes [i 100000] (.offsetByCodePoints page 0 (mod i top)) (host/cp-offset page 0 (mod i top))))
    (let [jdk (best-ms #(sum-calls n (fn [i] (.offsetByCodePoints page 0 (mod i top)))))
          ours (best-ms #(sum-calls n (fn [i] (host/cp-offset page 0 (mod i top)))))
          cjdk (best-ms #(sum-calls n (fn [_] (.codePointCount page 0 (.length page)))))
          cours (best-ms #(sum-calls n (fn [_] (host/cp-count page))))]
      (println (format "%-10s offset: jdk %7.1f ms, ours %7.1f ms (x%.2f)   count: jdk %7.1f ms, ours %7.1f ms (x%.2f)   [%d calls]"
                       (name kind) jdk ours (/ ours jdk) cjdk cours (/ cours cjdk) n)))))
