;; Leaf page size bench (M1.1a plan item 5). Builds the same values under
;; several `lit/page-budget` values and reports, per shape and budget:
;; the value hash (it must not change), the node count and bytes of a full
;; reveal, the largest node item, the peer item stream, and the bytes a
;; one-element edit or an append adds to a store that holds the old value.
;;
;;   clojure -M:dev -i dev/page_size_bench.clj
(require 'clojure.string '[dacite.store :as store] '[dacite.value :as v] '[dacite.reveal :as reveal]
         '[dacite.store.pack :as pack] '[dacite.rooted.gc :as gc]
         '[dacite.peer.items :as items] '[dacite.value.lit :as lit]
         '[dacite.value.collections :as coll] '[dacite.wire.binary :as bin])

(def budgets (mapv #(Long/parseLong %) (clojure.string/split (or (System/getenv "BUDGETS") "1024,4096,8192,16384") #",")))

(defn text [n seed]
  (let [r (java.util.Random. seed)]
    (apply str (repeatedly n #(char (+ 97 (.nextInt r 26)))))))

(def shapes
  {"string 100 KB"        (fn [st] (v/string st (text 100000 1)))
   "blob 100 KB"            (fn [st] (v/blob st (let [b (byte-array 100000)] (.nextBytes (java.util.Random. 3) b) b)))
   "blob 300 KB"            (fn [st] (v/blob st (let [b (byte-array 300000)] (.nextBytes (java.util.Random. 2) b) b)))
   "vector 4k ints"      (fn [st] (apply v/vector st (map #(v/i64 st %) (range 4000))))
   "vector 300 x 1KB str"  (fn [st] (apply v/vector st (map #(v/string st (text 1000 (+ 10 %))) (range 300))))
   "vector 2k x 12B str"  (fn [st] (apply v/vector st (map #(v/string st (text 12 (+ 10 %))) (range 2000))))
   "vector 8k i64"       (fn [st] (apply v/vector st (map #(v/i64 st (* 1000003 %)) (range 8000))))
   "map 1k int->int"      (fn [st] (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 7 i))]) (range 1000))))})

(def edits
  {"string 100 KB"       (fn [st] (v/string st (let [t (text 100000 1)] (str (subs t 0 50000) "#" (subs t 50001)))))
   "blob 100 KB"           (fn [st] (v/blob st (let [b (byte-array 100000)] (.nextBytes (java.util.Random. 3) b) (aset-byte b 50000 (byte (inc (aget b 50000)))) b)))
   "blob 300 KB"           (fn [st] (v/blob st (let [b (byte-array 300000)] (.nextBytes (java.util.Random. 2) b) (aset-byte b 150000 (byte (inc (aget b 150000)))) b)))
   "vector 4k ints"     (fn [st] (apply v/vector st (map #(v/i64 st (if (= % 2000) -1 %)) (range 4000))))
   "vector 300 x 1KB str" (fn [st] (apply v/vector st (map #(v/string st (if (= % 150) (text 1000 99999) (text 1000 (+ 10 %)))) (range 300))))
   "vector 2k x 12B str" (fn [st] (apply v/vector st (map #(v/string st (if (= % 1000) "changed!" (text 12 (+ 10 %)))) (range 2000))))
   "vector 8k i64"      (fn [st] (apply v/vector st (map #(v/i64 st (if (= % 4000) -1 (* 1000003 %))) (range 8000))))
   "map 1k int->int"     (fn [st] (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (if (= i 500) -1 (* 7 i)))]) (range 1000))))})

(defn node-sizes [st h]
  (let [order (reveal/page-order st h)]
    {:order order
     :bytes (mapv #(long (pack/wire-item-size (pack/node-item % (store/require-node st %)))) order)}))

(defn stats [st h]
  (let [{:keys [order bytes]} (node-sizes st h)
        s (items/encode-value st h)]
    {:nodes (count order) :node-bytes (reduce + bytes) :max-node (apply max bytes)
     :items (count (:items s)) :lits (count (filter #{:literal} (:encs s)))
     :stream-bytes (reduce + (map bin/byte-len (:items s)))
     :max-item (apply max (map bin/byte-len (:items s)))}))

(defn delta [st old-h new-h]
  (let [old (set (map gc/hash-key (:order (node-sizes st old-h))))
        {:keys [order bytes]} (node-sizes st new-h)
        fresh (keep-indexed (fn [i h] (when-not (contains? old (gc/hash-key h)) (nth bytes i))) order)]
    {:new-nodes (count fresh) :new-bytes (reduce + fresh)}))

(println (format "%-20s %6s %-8s %6s %9s %8s | %6s %5s %9s %8s | %5s %8s"
                 "shape" "budget" "hash" "nodes" "nodeBytes" "maxNode" "items" "lits" "stream" "maxItem" "edit+" "editB"))
(doseq [[nm mk] (sort-by key shapes)]
  (let [hashes (atom #{})]
    (doseq [b budgets]
      (binding [lit/page-budget b]
        (let [st (store/mem-store)
              t0 (System/nanoTime)
              val (mk st)
              h (v/hash val)
              t1 (System/nanoTime)
              s (stats st h)
              t2 (System/nanoTime)
              d (delta st h (v/hash ((edits nm) st)))]
          (swap! hashes conj (gc/hash-key h))
          (println (format "%-20s %6d %-8s %6d %9d %8d | %6d %5d %9d %8d | %5d %8d | build %5d ms encode %5d ms"
                           nm b (subs (gc/hash-key h) 0 8) (:nodes s) (:node-bytes s) (:max-node s)
                           (:items s) (:lits s) (:stream-bytes s) (:max-item s)
                           (:new-nodes d) (:new-bytes d)
                           (quot (- t1 t0) 1000000) (quot (- t2 t1) 1000000)))
          (flush))))
    (println "   hashes equal across budgets:" (= 1 (count @hashes)))))
(shutdown-agents)
