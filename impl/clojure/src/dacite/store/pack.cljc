(ns dacite.store.pack
  "Layer 1 (:node) encodings + Layer 2 soft-budget chunking (leaf-chunking.md 2a).

   Layer 1 items (2a): {:encoding :node :hash hex :body store-entry}
   Layer 2: accumulate items until estimated size ≥ budget, then seal a chunk.
   Chunks may exceed budget by one item (soft budget, up to ~2× budget)."
  (:require [dacite.store :as store]
            [dacite.wire :as wire]))

(def default-budget
  "Default soft pack budget in bytes (logical EDN length)."
  1024)

(defn node-item
  "Layer-1 encoding: hash + store content (no literal expansion)."
  [h body]
  {:encoding :node
   :hash (store/hash->hex h)
   :body body})

(defn item-size
  "Approx wire size of a Layer-1 item (EDN string length)."
  [item]
  (count (wire/write-edn item)))

(defn chunk-size
  "Approx wire size of a chunk envelope."
  [chunk]
  (count (wire/write-edn chunk)))

(defn make-chunk
  "Build a chunk-v1 envelope map."
  [budget items]
  {:dacite.wire/chunk-v1 true
   :budget budget
   :items (vec items)})

(defn pack-items
  "Split Layer-1 items into chunks using a soft budget.

   Append until estimated chunk size ≥ budget, then seal (including the
   item that crossed the threshold). Flushes a final partial chunk."
  ([items] (pack-items items default-budget))
  ([items budget]
   (let [budget (long (or budget default-budget))]
     (if (empty? items)
       []
       (loop [remaining (seq items)
              cur []
              out []]
         (if-let [item (first remaining)]
           (let [cur' (conj cur item)
                 ch (make-chunk budget cur')
                 sz (chunk-size ch)]
             (if (>= sz budget)
               (recur (next remaining) [] (conj out ch))
               (recur (next remaining) cur' out)))
           (if (seq cur)
             (conj out (make-chunk budget cur))
             out)))))))

(defn chunk?
  "True if m looks like a chunk-v1 envelope."
  [m]
  (and (map? m)
       (or (true? (:dacite.wire/chunk-v1 m))
           (vector? (:items m)))))

(defn apply-chunk!
  "Apply a chunk to an IStore. Returns {:applied n}.
   2a: only :node encoding (s-put body at hash)."
  [st chunk]
  (let [items (:items chunk)
        n (atom 0)]
    (doseq [item items]
      (let [enc (keyword (:encoding item))
            hex (:hash item)
            h (store/hex->hash hex)]
        (case enc
          :node
          (do (store/s-put st h (:body item))
              (swap! n inc))
          (throw (ex-info "unsupported chunk item encoding (2a supports :node only)"
                          {:encoding enc :hash hex})))))
    {:applied @n}))

(defprotocol IChunkTransport
  "HTTP (or other) sink for sealed chunks."
  (send-chunk! [this chunk]
    "POST one chunk envelope. Throws on failure. Returns nil or response map."))

(defn put-items-chunked!
  "Layer 2: pack items and send-chunk! each. Returns {:chunks n :items n}."
  ([transport items]
   (put-items-chunked! transport items default-budget))
  ([transport items budget]
   (let [chunks (pack-items items budget)]
     (doseq [ch chunks]
       (send-chunk! transport ch))
     {:chunks (count chunks)
      :items (count items)})))
