;; Degenerate leaf shapes (M1.1a item 5): the largest node item per budget, with
;; the +5 per string/blob accounting. Nulls, bools, empty and 1-char strings and
;; small blobs are where payload size and wire size differ most.
;;   clojure -M:dev -i dev/page_size_degenerate.clj
(require (quote [dacite.peer.frame :as frame]) (quote clojure.string) '[dacite.store :as store] '[dacite.value :as v] '[dacite.reveal :as reveal]
         '[dacite.store.pack :as pack] '[dacite.peer.items :as items] '[dacite.value.lit :as lit]
         '[dacite.wire.binary :as bin] '[dacite.rooted.gc :as gc])
(def shapes
  {"1-char strings x2000" (fn [st] (apply v/vector st (map #(v/string st (str (char (+ 97 (mod % 26))))) (range 2000))))
   "empty strings x2000"  (fn [st] (apply v/vector st (map (fn [_] (v/string st "")) (range 2000))))
   "nulls x4000"          (fn [st] (apply v/vector st (map (fn [_] (v/null st)) (range 4000))))
   "bools x4000"          (fn [st] (apply v/vector st (map #(v/bool st (even? %)) (range 4000))))
   "4-char strings x2000" (fn [st] (apply v/vector st (map #(v/string st (format "%04d" %)) (range 2000))))
   "200-B blobs x400"     (fn [st] (apply v/vector st (map #(v/blob st (byte-array 200 (unchecked-byte %))) (range 400))))})
(doseq [b [1024 2048 4096 8192]
        [nm mk] (sort-by key shapes)]
  (binding [lit/page-budget b]
    (let [st (store/mem-store) h (v/hash (mk st))
          order (reveal/page-order st h)
          sizes (map #(long (pack/wire-item-size (pack/node-item % (store/require-node st %)))) order)]
      (println (format "%-22s budget %5d nodes %4d max node item %6d  (%s)" nm b (count order) (apply max sizes)
                       (if (> (apply max sizes) frame/max-node-item-bytes) "OVER max-node-item-bytes" "ok")))
      (flush))))
(shutdown-agents)
