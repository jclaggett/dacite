(ns dacite.peer.conformance
  "Check fixtures/peer-v1 against the real codec. Portable: the JVM test
   (peer_v1_test.clj) and nbb (peer_cljs_test.cljs) both call `run-all`.

   Each case is checked by decoding its frames, comparing with the meaning
   in message.edn, and encoding again to the same bytes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.store :as store]
            [dacite.wire.binary :as bin]
            #?(:clj [clojure.java.io :as io]
               :cljs ["fs" :as fs])
            #?(:clj [cheshire.core :as json])))

(defn fixtures-root
  []
  #?(:clj (str (io/file (.getParentFile (io/file (bin/fixture-root))) "peer-v1"))
     :cljs "fixtures/peer-v1"))

(defn- read-text
  [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync fs path "utf8")))

(defn- case-text
  [id file]
  (read-text (str (fixtures-root) "/cases/" id "/" file)))

(defn manifest
  []
  (let [text (read-text (str (fixtures-root) "/manifest.json"))]
    #?(:clj (json/parse-string text)
       :cljs (js->clj (js/JSON.parse text)))))

(defn- case-meta
  [id]
  (edn/read-string (case-text id "message.edn")))

(defn- case-frames
  [id]
  (->> (str/split-lines (case-text id "frames.hex"))
       (remove str/blank?)
       (mapv bin/hex->bytes)))

(defn- hex
  [bs]
  (bin/bytes->hex bs))

(defn- reason-of
  "Run `f`. Return the :reason of the exception it throws, or :accepted."
  [f]
  (try (f) :accepted
       (catch #?(:clj Throwable :cljs :default) e
         (or (:reason (ex-data e)) (keyword (str "other: " (ex-message e)))))))

(defn- read-stream
  "Decode frames in order into a reader on `stream`. Returns
   {:state :items} (items are byte arrays)."
  [stream frames]
  (reduce (fn [{:keys [state items]} bs]
            (let [fr (frame/decode-frame bs)
                  [st' more] (frame/feed state fr)]
              {:state st' :items (into items more)}))
          {:state (frame/reader stream) :items []}
          frames))

;; Each check returns a vector of problem strings.

(defn- check-message
  [id {:keys [type stream value reads answer]} frames]
  (let [fr (first frames)
        got (msg/decode-message fr)
        expected (msg/<-edn value)]
    (cond-> []
      (and (contains? reads :error-code)
           (not= (:error-code reads) (msg/error-code (:value got))))
      (conj (str id ": error code reads as " (msg/error-code (:value got))))
      (and (contains? reads :peer-kind)
           (not= (:peer-kind reads) (msg/peer-kind (:value got))))
      (conj (str id ": peer kind reads as " (msg/peer-kind (:value got))))
      (and answer (not= answer (msg/->edn (msg/answer-error got))))
      (conj (str id ": responder answer differs"))
      (and (nil? answer) (msg/answer-error got))
      (conj (str id ": a message that needs no answer got one"))
      (not= 1 (count frames)) (conj (str id ": a control message is one frame"))
      (not= type (:type got)) (conj (str id ": type " (:type got)))
      (not= stream (:stream got)) (conj (str id ": stream " (:stream got)))
      (not (msg/equal-values? expected (:value got))) (conj (str id ": value differs"))
      (not= (hex fr) (hex (frame/encode-frame
                           {:type type :stream stream :flags frame/flag-fin
                            :payload (msg/encode-value expected)})))
      (conj (str id ": re-encoded bytes differ")))))

(defn- check-item-stream
  [id {:keys [stream partial?] :as m} frames]
  (let [{:keys [state items] :as r} (read-stream stream frames)
        all (frame/concat-bytes (:items r))
        decoded (bin/decode-item-stream all)
        again (frame/chunk-items stream (:items r) {:partial? partial?})]
    (cond-> []
      (not (:fin? state)) (conj (str id ": stream did not end with FIN"))
      (not= partial? (:partial? state)) (conj (str id ": PARTIAL flag"))
      (not= (:frames m) (count frames)) (conj (str id ": frame count"))
      (not= (count (:items m)) (count decoded)) (conj (str id ": item count"))
      (not= (mapv :bytes (:items m)) (mapv bin/byte-len (:items r)))
      (conj (str id ": item sizes"))
      (not= (mapv :hash (:items m)) (mapv #(store/hash->hex (:hash %)) decoded))
      (conj (str id ": item hashes"))
      (not-every? #(= :node (:enc %)) decoded) (conj (str id ": items are nodes"))
      (not= (mapv hex frames) (mapv hex again)) (conj (str id ": re-chunked frames differ"))
      (not= (mapv hex (:items r)) (mapv #(hex (bin/encode-item %)) decoded))
      (conj (str id ": an item does not re-encode to its bytes")))))

(defn- check-reject
  [id {:keys [reason type stream as-control]} frames]
  (let [got (reason-of
             (fn []
               (if (and (= :item-stream type) (not as-control))
                 (read-stream stream frames)
                 (msg/decode-message (first frames)))))]
    (if (= reason got)
      []
      [(str id ": expected reject " reason ", got " got)])))

(defn- check-unknown
  [id {:keys [type-id stream]} frames]
  (let [got (msg/decode-message (first frames))]
    (cond-> []
      (not (:unknown? got)) (conj (str id ": not flagged unknown"))
      (not= type-id (:type got)) (conj (str id ": type id"))
      (not= stream (:stream got)) (conj (str id ": stream")))))

(defn run-case
  "Problems found in one fixture case. Empty when it passes."
  [id]
  (let [m (case-meta id)
        frames (case-frames id)]
    (try
      (case (:expect m)
        :message (check-message id m frames)
        :item-stream (check-item-stream id m frames)
        :reject (check-reject id m frames)
        :unknown (check-unknown id m frames)
        [(str id ": unknown :expect " (:expect m))])
      (catch #?(:clj Throwable :cljs :default) e
        [(str id ": threw " (ex-message e) " " (pr-str (ex-data e)))]))))

(defn run-all
  "Run every case in manifest.json. Returns {:cases n :problems [..]}."
  []
  (let [ids (get (manifest) "cases")]
    {:cases (count ids)
     :problems (into [] (mapcat run-case) ids)}))

;; Checks that need no fixture files.

(defn- items-of
  [n]
  (mapv (fn [i]
          (let [bs (bin/as-wire-bytes (vec (concat [0] (repeat 32 i) [0 0 0 3] [i i i])))]
            bs))
        (range 1 (inc n))))

(defn run-framing
  "Chunking and interleaving checks. Returns problems."
  []
  (let [items (items-of 3)
        room frame/max-payload
        ;; an item stream that is exactly two full frames
        pad (bin/as-wire-bytes (vec (concat [0] (repeat 32 9)
                                            [0 0 (quot (- (* 2 room) 37) 256) (rem (- (* 2 room) 37) 256)]
                                            (repeat (- (* 2 room) 37) 7))))
        exact (frame/chunk-items 5 [pad])
        empty (frame/chunk-items 5 [])
        a (frame/chunk-items 5 items)
        b (frame/chunk-items 9 items)
        mixed (interleave (map frame/decode-frame a) (map frame/decode-frame b))
        run (reduce (fn [acc fr]
                      (let [sid (:stream fr)
                            [st more] (frame/feed (get-in acc [:readers sid]) fr)]
                        (-> acc
                            (assoc-in [:readers sid] st)
                            (update-in [:got sid] (fnil into []) more))))
                    {:readers {5 (frame/reader 5) 9 (frame/reader 9)}}
                    mixed)]
    (cond-> []
      (not= 2 (count exact)) (conj "two full frames need exactly two frames")
      (not= 1 (count empty)) (conj "an empty stream is one frame")
      (not (:fin? (frame/decode-frame (last exact)))) (conj "last frame has FIN")
      (not= 3 (count (get-in run [:got 5]))) (conj "stream 5 gets its three items")
      (not= 3 (count (get-in run [:got 9]))) (conj "stream 9 gets its three items")
      (not= (mapv hex (get-in run [:got 5])) (mapv hex items)) (conj "items intact on 5")
      (not= (mapv hex (get-in run [:got 9])) (mapv hex items)) (conj "items intact on 9")
      (not= [true true] (mapv #(:fin? (get-in run [:readers %])) [5 9])) (conj "both end"))))
