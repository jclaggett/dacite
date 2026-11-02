;; Leaf page size bench (M1.1a plan item 5). Builds the same values under
;; several `lit/page-budget` values and reports, per shape and budget:
;; the value hash (it must not change), the node count and bytes of a full
;; reveal, the largest node item, the peer item stream, and the bytes a
;; one-element edit or an append adds to a store that holds the old value.
;;
;;   clojure -M:dev -i dev/page_size_bench.clj
(require '[dacite.store :as store] '[dacite.value :as v] '[dacite.reveal :as reveal]
         '[dacite.store.pack :as pack] '[dacite.rooted.gc :as gc]
         '[dacite.peer.items :as items] '[dacite.value.lit :as lit]
         '[dacite.value.collections :as coll] '[dacite.wire.binary :as bin])

(def budgets [1024 2048 4096 8192 16384])

(defn text [n seed]
  (let [r (java.util.Random. seed)]
    (apply str (repeatedly n #(char (+ 97 (.nextInt r 26)))))))

(def shapes
  {"string 100 KB"        (fn [st] (v/string st (text 100000 1)))
   "blob 1 MB"            (fn [st] (v/blob st (let [b (byte-array 1000000)] (.nextBytes (java.util.Random. 2) b) b)))
   "vector 10k ints"      (fn [st] (apply v/vector st (map #(v/i64 st %) (range 10000))))
   "vector 1k x 1KB str"  (fn [st] (apply v/vector st (map #(v/string st (text 1000 (+ 10 %))) (range 1000))))
   "vector 5k x 12B str"  (fn [st] (apply v/vector st (map #(v/string st (text 12 (+ 10 %))) (range 5000))))
   "vector 20k i64"       (fn [st] (apply v/vector st (map #(v/i64 st (* 1000003 %)) (range 20000))))
   "map 3k int->int"      (fn [st] (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 7 i))]) (range 3000))))})

(def edits
  {"string 100 KB"       (fn [st] (v/string st (let [t (text 100000 1)] (str (subs t 0 50000) "#" (subs t 50001)))))
   "blob 1 MB"           (fn [st] (v/blob st (let [b (byte-array 1000000)] (.nextBytes (java.util.Random. 2) b) (aset-byte b 500000 (byte (inc (aget b 500000)))) b)))
   "vector 10k ints"     (fn [st] (apply v/vector st (map #(v/i64 st (if (= % 5000) -1 %)) (range 10000))))
   "vector 1k x 1KB str" (fn [st] (apply v/vector st (map #(v/string st (if (= % 500) (text 1000 99999) (text 1000 (+ 10 %)))) (range 1000))))
   "vector 5k x 12B str" (fn [st] (apply v/vector st (map #(v/string st (if (= % 2500) "changed!" (text 12 (+ 10 %)))) (range 5000))))
   "vector 20k i64"      (fn [st] (apply v/vector st (map #(v/i64 st (if (= % 10000) -1 (* 1000003 %))) (range 20000))))
   "map 3k int->int"     (fn [st] (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (if (= i 1500) -1 (* 7 i)))]) (range 3000))))})

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
              val (mk st)
              h (v/hash val)
              s (stats st h)
              d (delta st h (v/hash ((edits nm) st)))]
          (swap! hashes conj (gc/hash-key h))
          (println (format "%-20s %6d %-8s %6d %9d %8d | %6d %5d %9d %8d | %5d %8d"
                           nm b (subs (gc/hash-key h) 0 8) (:nodes s) (:node-bytes s) (:max-node s)
                           (:items s) (:lits s) (:stream-bytes s) (:max-item s)
                           (:new-nodes d) (:new-bytes d)))
          (flush))))
    (println "   hashes equal across budgets:" (= 1 (count @hashes)))))
(shutdown-agents)
