(ns dacite.peer.client-checks
  "M1.2 slice 1, portable checks (JVM: client_test.clj, nbb: peer_cljs_test).
   Spec: docs/spec/peer-v1.md §12.4 and §12.8, test map in §13.

   Each check is a function that returns a vector of problem strings, empty
   when it holds; `checks` maps the test name (the name in the §13 table) to
   it. Nothing here waits or sleeps: peers are simulated, in the same thread,
   so a check takes milliseconds and no clock is needed.

   The simulated network: a map of peers, each a function from a `get`'s
   (stream id, hash) to the frames it answers with, as bytes. The client's
   `:send!` queues what it sends, and `pump!` hands each `get` to its peer and
   each answer frame to `client/on-frame!`."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.peer.client :as client]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.peer.verify :as verify]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.hamt :as hamt]
            [dacite.wire.binary :as bin]))

;; =============================================================================
;; Values and their items
;; =============================================================================

(defn- vector-value [st n]
  (apply v/vector st (map #(v/string st (str "line " % ": the quick brown fox")) (range n))))

(defn- map-of [st n]
  (apply v/map st (mapcat (fn [i] [(str "key " i) (str "value " i ": jumps over the lazy dog")])
                          (range n))))

(defn- items-of
  "Node items (decoded form) of a full reveal of `root` in `st`, header first."
  [st root]
  (mapv (fn [h] {:enc :node :hash h :entry (store/await (store/s-get st h))})
        (reveal/page-order st root)))

(defn- frames-of
  "Frames (bytes) for `item-bytes` on stream `sid`, small so a value takes
   several. FIN on the last; PARTIAL when `partial?`."
  ([sid item-bytes] (frames-of sid item-bytes false))
  ([sid item-bytes partial?]
   (frame/chunk-items sid item-bytes {:max-frame-bytes 1024 :partial? partial?})))

(defn- error-frame [sid code fatal?]
  (msg/encode-message {:type :error :stream sid
                       :value {"code" code "message" "simulated" "fatal" fatal?}}))

(defn- hex [h] (store/hash->hex h))

(defn- snapshot [st] (store/await (store/s-snapshot st)))

;; =============================================================================
;; Peers that lie
;; =============================================================================

(defn- flip-state-bit
  "`bs` with one bit of the first elements-fuse state changed (the lowest bit
   of cell a): a state outside SL(2, F_p), the wire-v1 `bad-state`."
  [bs item]
  (let [text (bin/bytes->hex bs)
        state (hash/state->hex (:elements-fuse (:measure (second (:entry item)))))
        at (str/index-of text state)
        digits "0123456789abcdef"
        pos (+ at 63)
        d (str/index-of digits (nth text pos))]
    (bin/hex->bytes (str (subs text 0 pos) (nth digits (bit-xor 1 d)) (subs text (inc pos))))))

(defn- leaf-page-index
  "Index of the first `ft/digit` or `ft/node` item."
  [items]
  (first (keep-indexed (fn [i it] (when (#{"ft/digit" "ft/node"} (first (:entry it))) i))
                       items)))

(def failure-kinds
  "How a peer lies. Each is {:make (fn [items] [k item-bytes]) with k the index of
   the first item the client must refuse, :absent (fn [items k] items that must
   not be stored), :root-had? whether the collection header ends up stored}.

   :root-had? is whether the header stays: a header whose root arrived before
   the bad item was checked (it verified, so it stays); in the other kinds the
   unchecked header is dropped with the session.

   :bad-hash       a page claims another item's hash
   :bad-root       the header's root page claims the header's hash (the header is still
                   unchecked when the session ends: it must be dropped)
   :bad-measure    a leaf page's claimed count is one too many
   :bad-state      a measure state is not in SL(2, F_p)
   :forged-header  a header's count is one too many (found when its root arrives)
   :no-root        the stream ends (FIN) after the header, root never sent"
  {:bad-root {:make (fn [items]
                      [1 (mapv bin/encode-item
                               (update items 1 assoc :hash (:hash (first items))))])
              :absent (fn [items _] items) :root-had? false}
   :bad-hash {:make (fn [items]
                      (let [k (leaf-page-index items)]
                        [k (mapv bin/encode-item
                                 (update items k assoc :hash (:hash (first items))))]))
              :absent (fn [items k] (drop k items)) :root-had? true}
   :bad-measure {:make (fn [items]
                         (let [k (leaf-page-index items)]
                           [k (mapv bin/encode-item
                                    (update-in items [k :entry 1 :measure :count] inc))]))
                 :absent (fn [items k] (drop k items)) :root-had? true}
   :bad-state {:make (fn [items]
                       (let [k (leaf-page-index items)]
                         [k (assoc (mapv bin/encode-item items)
                                   k (flip-state-bit (bin/encode-item (nth items k)) (nth items k)))]))
               :absent (fn [items k] (drop k items)) :root-had? true}
   :forged-header {:make (fn [items]
                           [1 (mapv bin/encode-item
                                    (update-in items [0 :entry 1 :count] inc))])
                   :absent (fn [items _] [(first items)]) :root-had? false}
   :no-root {:make (fn [items] [1 [(bin/encode-item (first items))]])
             :absent (fn [items _] [(first items)]) :root-had? false}})

;; =============================================================================
;; The simulated network
;; =============================================================================

(defn- honest
  "A peer that serves from `src`: the items of a full reveal, or `unknown-hash`."
  [src]
  (fn [sid h]
    (if (store/await (store/s-has? src h))
      (frames-of sid (mapv bin/encode-item (items-of src h)))
      [(error-frame sid "unknown-hash" false)])))

(defn- liar
  "A peer that serves `src` but `kind` of lie (see `failure-kinds`), once per hash."
  [src kind]
  (fn [sid h]
    (frames-of sid (second ((:make (failure-kinds kind)) (items-of src h))))))

(def ^:private silent (fn [_ _] []))

(defn- net
  "A client over simulated `peers` ({peer-id (fn [sid hash] frames)}), with
   sessions opened in the order given. `roots` are the hashes peers may be asked
   for (a `get` names its hash in hex)."
  [peers roots]
  (let [outbox (atom []) log (atom []) closed (atom [])
        c (client/client {:store (store/mem-store)
                          :send! (fn [p m] (swap! outbox conj [p m]) (swap! log conj [p m]))
                          :close! (fn [p] (swap! closed conj p))})
        by-hex (into {} (map (juxt hex identity)) roots)]
    (doseq [p (keys peers)] (client/open-session! c p))
    {:client c :peers peers :by-hex by-hex :outbox outbox :log log :closed closed}))

(defn- deliver!
  "Give `p`'s session frames the way the transport would."
  [{:keys [client]} p frames]
  (doseq [f frames] (client/on-frame! client p f)))

(defn- pump!
  "Answer every queued `get` from its peer, until nothing is queued: an answer
   can make the client send more."
  [{:keys [client peers by-hex outbox] :as n}]
  (loop [steps 0]
    (when (> steps 200) (throw (ex-info "the simulated network does not settle" {})))
    (when-let [[p m] (first @outbox)]
      (swap! outbox subvec 1)
      (when (= :get (:type m))
        (let [h (get by-hex (msg/hash-ref-hex (get-in m [:value "hash"])))]
          (deliver! n p ((get peers p) (:stream m) h))))
      (recur (inc steps))))
  n)

(defn- gets
  "[peer stream-id] of every `get` the client sent, in order."
  [{:keys [log]}]
  (vec (keep (fn [[p m]] (when (= :get (:type m)) [p (:stream m)])) @log)))

(defn- fatal-errors
  "[peer message] of every fatal `error` the client sent."
  [{:keys [log]}]
  (vec (filter (fn [[_ m]] (and (= :error (:type m)) (get-in m [:value "fatal"]))) @log)))

(defn- cut [x] (let [t (pr-str x)] (if (> (count t) 300) (str (subs t 0 300) "...") t)))

(defn- bad [what expected actual]
  (when-not (= expected actual)
    [(str what ": expected " (cut expected) ", got " (cut actual))]))

(defn- same-entry?
  "Two entries are the same when they have the same wire-v1 bytes. (Not `=`: a
   decoded u32 is a number on nbb where the builder had a BigInt.)"
  [h a b]
  (and (some? a) (some? b)
       (= (bin/bytes->hex (bin/encode-item {:enc :node :hash h :entry a}))
          (bin/bytes->hex (bin/encode-item {:enc :node :hash h :entry b})))))

(defn- nothing-bad-stored
  "Problems unless every entry in the client's store is exactly the source's
   entry under the same key: nothing forged and nothing altered is stored."
  [{:keys [client]} src]
  (let [mine (snapshot (:store client)) theirs (snapshot src)]
    (mapcat (fn [[k entry]]
              ;; the key is the hash on the JVM, its hex on nbb
              (let [h (if (string? k) (hash/hex->hash k) k)]
                (when-not (same-entry? h entry (get theirs k))
                  [(str "the store holds an entry that is not the source's: " (cut k))])))
            mine)))

(defn- stored? [st h] (some? (store/await (store/s-get st h))))

;; =============================================================================
;; The checks
;; =============================================================================

(defn bad-entry-ends-the-session
  "C13: a failed check ends the whole session: one fatal `bad-entry` on
   stream 0, the connection closed, and nothing that failed is stored. Each way
   of lying in `failure-kinds`."
  []
  (let [src (store/mem-store)
        root (v/hash (vector-value src 40))
        items (items-of src root)]
    (vec (mapcat
          (fn [kind]
            (let [n (net {"liar" (liar src kind)} [root])
                  _ (client/request! (:client n) root)
                  _ (pump! n)
                  st (:store (:client n))
                  [k _] ((:make (failure-kinds kind)) items)
                  [[peer m] :as errs] (fatal-errors n)
                  what (str kind ": ")]
              (concat
               (bad (str what "session") :ended (client/session-status (:client n) "liar"))
               (bad (str what "one fatal error") 1 (count errs))
               (bad (str what "sent to the liar") "liar" peer)
               (bad (str what "on stream 0") 0 (:stream m))
               (bad (str what "code") "bad-entry" (get-in m [:value "code"]))
               (bad (str what "connection closed") ["liar"] @(:closed n))
               (try (msg/encode-message m) nil
                    (catch #?(:clj Throwable :cljs :default) e
                      [(str what "the error does not encode: " (ex-message e))]))
               ;; nothing that failed, and no unchecked header, is in the store
               (mapcat (fn [it]
                         (when (stored? st (:hash it))
                           [(str what "stored " (store/hash->hex (:hash it)))]))
                       ((:absent (failure-kinds kind)) items k))
               (nothing-bad-stored n src)
               (bad (str what "the root is had") (:root-had? (failure-kinds kind))
                    (client/have? (:client n) root))
               (bad (str what "no peer left: unserved") [root] (client/unserved (:client n))))))
          (keys failure-kinds)))))

(defn bad-state-is-refused-as-bad-entry
  "C30: a state outside SL(2, F_p) is `bad-state` to the wire decoder, and the
   client sends it as `bad-entry` (spec 3.3)."
  []
  (let [src (store/mem-store)
        root (v/hash (vector-value src 40))
        items (items-of src root)
        k (leaf-page-index items)
        decoder-reason (try (bin/decode-item-stream
                             (flip-state-bit (bin/encode-item (nth items k)) (nth items k)))
                            :decoded
                            (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e))))
        n (net {"liar" (liar src :bad-state)} [root])]
    (client/request! (:client n) root)
    (pump! n)
    (concat
     (bad "the decoder says" :bad-state decoder-reason)
     (bad "the client sends" ["bad-entry"] (mapv (fn [[_ m]] (get-in m [:value "code"])) (fatal-errors n)))
     (bad "the message names the detail" true
          (boolean (re-find #"bad-state" (str (get-in (second (first (fatal-errors n))) [:value "message"]))))))))

(defn- item-frame
  "One item-stream frame on `sid` with the items' bytes, not the last (no FIN)."
  [sid item-bytes]
  (frame/encode-frame {:type :item-stream :stream sid :flags 0
                       :payload (frame/concat-bytes item-bytes)}))

(defn nothing-unverified-reaches-the-store
  "C27: an item is stored only after it verifies, and an unchecked header is
   not something a client may read or offer onward (`have?`). A good item
   before a bad one stays stored: it verified."
  []
  (let [src (store/mem-store)
        root (v/hash (vector-value src 40))
        items (items-of src root)
        [k bytes] ((:make (failure-kinds :bad-measure)) items)
        n (net {"p" silent} [root])
        c (:client n) st (:store c)]
    (client/request! c root)
    (let [[_ sid] (first (gets n))
          rx #(get-in @(:state c) [:sessions "p" :streams sid :rx])]
      (concat
       (when-not (= 2 k) [(str "the test expects the bad page at 2, not " k)])
       (do (client/on-frame! c "p" (item-frame sid (subvec bytes 0 1))) nil)
       (bad "the header is stored on arrival" true (stored? st root))
       (bad "but it is unchecked" [root] (verify/unchecked (rx)))
       (bad "so it is not had" false (client/have? c root))
       (do (client/on-frame! c "p" (item-frame sid (subvec bytes 1 k))) nil)
       (bad "its root arrived: checked, and had" [true []] [(client/have? c root) (verify/unchecked (rx))])
       (bad "the good page before the bad one is stored" true
            (every? #(stored? st (:hash %)) (subvec items 1 k)))
       (do (client/on-frame! c "p" (first (frames-of sid [(nth bytes k)]))) nil)
       (bad "the bad page is not stored" false (stored? st (:hash (nth items k))))
       (bad "the session ended" :ended (client/session-status c "p"))
       (bad "what verified before it stays" true (every? #(stored? st (:hash %)) (subvec items 0 k)))
       (nothing-bad-stored n src)))))

(defn every-page-verifies-in-any-order
  "C33: pages carry their own measure, so they verify in any order and nothing
   is parked. Forward, reversed, and interleaved: all of the value is stored."
  []
  (let [src (store/mem-store)
        results (for [[label root] [["vector" (v/hash (vector-value src 40))]
                                    ["map" (v/hash (map-of src 30))]]
                      :let [items (items-of src root)
                            [hd & pages] items
                            evens (take-nth 2 pages) odds (take-nth 2 (rest pages))]
                      [order items'] [["forward" items]
                                      ["reversed" (reverse items)]
                                      ["header last, pages interleaved" (concat evens odds [hd])]]]
                  (let [rx (verify/receiver (store/mem-store))
                        outcome (try (verify/finish! (verify/accept-items! rx items')) :ok
                                     (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e))))]
                    (concat
                     (bad (str label " " order ": accepted") :ok outcome)
                     (bad (str label " " order ": all stored") (set (map (comp hex :hash) items))
                          (set (map hex (filter #(stored? (:store rx) %) (map :hash items))))))))]
    (vec (apply concat results))))

(defn literal-hash-mismatch-is-refused
  "C28: a literal is rebuilt from its bytes and must hash to the item's hash;
   a literal that holds a `ref` is `bad-item`."
  []
  (let [st (store/mem-store)
        h (v/hash (v/string st "hello"))
        other (v/hash (v/string st "another"))
        lit {:enc :literal :hash h :literal {:type "string" :body "hello"}}
        reason (fn [item]
                 (let [rx (verify/receiver (store/mem-store))]
                   (try (verify/accept-item! rx item)
                        (if (stored? (:store rx) (:hash item)) :accepted :not-stored)
                        (catch #?(:clj Throwable :cljs :default) e
                          [(:reason (ex-data e)) (stored? (:store rx) (:hash item))]))))]
    (concat
     (bad "the true literal" :accepted (reason lit))
     (bad "one letter changed" [:bad-hash false] (reason (assoc-in lit [:literal :body] "jello")))
     (bad "another value's hash" [:bad-hash false] (reason (assoc lit :hash other)))
     (bad "a literal that names a child" [:bad-item false]
          (reason {:enc :literal :hash h
                   :literal {:type "vector" :body [{:type "ref" :body h}]}})))))

(defn hamt-bitmap-shape-refusals
  "C29: a `hamt/bitmap` is checked for its shape: a split of 0..51, two or more
   children, one per bit, and at split 51 exactly bits 0 and 16. Each forged
   node carries a hash that matches its bitmap and split, so only the shape is
   wrong. The untouched node verifies."
  []
  (let [src (store/mem-store)
        root (v/hash (map-of src 30))
        node (first (filter #(= "hamt/bitmap" (first (:entry %))) (items-of src root)))
        data (second (:entry node))
        ef (:elements-fuse (:measure data))
        forge (fn [changes]
                (let [d (merge data changes)]
                  {:enc :node
                   :hash (hamt/bitmap-node-hash ef (:bitmap d) (:split d))
                   :entry ["hamt/bitmap" d]}))
        reason (fn [item]
                 (try (verify/verify-item! (store/mem-store) item) :accepted
                      (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e)))))
        one-bit 4]
    (concat
     (when-not node ["no hamt/bitmap node in the test value"])
     (bad "the untouched node" :accepted (reason node))
     (bad "split above 51" :bad-entry (reason (forge {:split 52})))
     (bad "a child missing (n is not the popcount)" :bad-entry
          (reason (assoc-in node [:entry 1 :children] (vec (rest (:children data))))))
     (bad "one child, one bit" :bad-entry
          (reason (forge {:bitmap one-bit :children [(first (:children data))]})))
     (bad "split 51 with other bits" :bad-entry
          (reason (forge {:split 51 :bitmap 0x30001
                          :children (vec (take 3 (cycle (:children data))))}))))))

(defn one-session-ending-leaves-others-untouched
  "C15: a failure ends only the session it came from. A holds the liar's
   stream; B has a stream half read, with an unchecked header. A is ended: B's
   status, stream ids, reader and unchecked header are as they were, B is
   not closed or sent an error, and B's stream goes on and completes."
  []
  (let [src (store/mem-store)
        x (v/hash (vector-value src 40))
        y (v/hash (map-of src 30))
        y-bytes (mapv bin/encode-item (items-of src y))
        ;; B answers Y with its header alone (unchecked); the rest comes by hand
        y-first (fn [sid] [(item-frame sid (subvec y-bytes 0 1))])
        y-rest (fn [sid] (frames-of sid (subvec y-bytes 1)))
        b (fn [sid h] (if (= h y) (y-first sid) ((honest src) sid h)))
        n (net {"A" (liar src :bad-measure) "B" b} [x y])
        c (:client n)
        session #(get-in @(:state c) [:sessions "B"])]
    (client/request! c x)                                   ; fewest streams, oldest: A
    (client/request! c y)                                   ; A has one stream now: B
    ;; pump A's liar answer last: first only B's half answer
    (let [[[pa m1] [pb m2]] (vec @(:outbox n))
          _ (reset! (:outbox n) [])
          y-sid (:stream m2)
          _ (deliver! n pb ((get (:peers n) pb) y-sid y))
          before (session)
          unchecked-before (verify/unchecked (get-in before [:streams y-sid :rx]))]
      (deliver! n pa ((get (:peers n) pa) (:stream m1) x))   ; A lies: A ends, x is re-asked
      (let [after (session)
            sent-to-b (filter (fn [[p _]] (= p "B")) @(:log n))
            b-errors (filter (fn [[p m]] (and (= p "B") (= :error (:type m)))) @(:log n))]
        (concat
         (bad "A is ended" :ended (client/session-status c "A"))
         (bad "B is open" :open (client/session-status c "B"))
         (bad "A was closed, B was not" ["A"] @(:closed n))
         (bad "B was sent no error" [] (vec b-errors))
         (bad "B's ids: the same two, plus 3 for the re-asked X"
              [(:used before) (:open before)]
              [(disj (:used after) 3) (disj (:open after) 3)])
         (bad "B's next id" 5 (:next (:table after)))
         (bad "B's stream for Y is the same stream, same reader" (get-in before [:streams y-sid :reader])
              (get-in after [:streams y-sid :reader]))
         (bad "B's header is still unchecked" unchecked-before
              (verify/unchecked (get-in after [:streams y-sid :rx])))
         (bad "the header is still unchecked, not had" false (client/have? c y))
         (bad "B got only the re-asked X as a new stream" 1
              (- (count (:streams after)) (count (:streams before))))
         (do (pump! n) nil)
         ;; B finishes Y by hand and serves X
         (do (deliver! n "B" (y-rest y-sid)) nil)
         (bad "Y is done through B" true (client/done? c y))
         (bad "X is done through B" true (client/done? c x))
         (nothing-bad-stored n src)
         (bad "B was never ended" :open (client/session-status c "B"))
         (when (empty? sent-to-b) ["B was sent nothing"]))))))

(defn bad-peer-streams-are-reasked-from-others
  "C14: what the ended session was serving is asked of another peer that can
   serve it. Peers: a liar, one that is lost, one without the value, and an
   honest one; two hashes outstanding on the liar. Each goes on to the
   next peer, and a peer is never asked twice for a hash."
  []
  (let [src (store/mem-store)
        x (v/hash (vector-value src 40))
        y (v/hash (map-of src 30))
        empty-src (store/mem-store)
        n (net (array-map "liar" (liar src :bad-measure)
                          "lost" silent
                          "bare" (honest empty-src)
                          "good" (honest src))
               [x y])
        c (:client n)]
    ;; make the liar the only one with streams: close the others' sessions? No:
    ;; the least-loaded rule spreads, so ask both of the liar by order of sessions
    (client/request! c x)
    (client/request! c y)
    (let [first-gets (gets n)]
      (pump! n)
      ;; "lost" never answers, and then the connection drops
      (client/close-session! c "lost")
      (pump! n)
      (let [by-peer (group-by first (gets n))
            asked (fn [p h] (count (filter (fn [[p' m]] (and (= p p') (= :get (:type m))
                                                             (= (hex h) (msg/hash-ref-hex (get-in m [:value "hash"])))))
                                           @(:log n))))]
        (concat
         (bad "the first two gets went to different peers (fewest streams)" ["liar" "lost"]
              (mapv first first-gets))
         (bad "X is done" true (client/done? c x))
         (bad "Y is done" true (client/done? c y))
         (bad "the liar ended" :ended (client/session-status c "liar"))
         (bad "the lost peer ended" :ended (client/session-status c "lost"))
         (bad "the peer without the value stays open" :open (client/session-status c "bare"))
         (bad "no hash is asked of a peer twice"
              [] (vec (for [p ["liar" "lost" "bare" "good"] h [x y]
                            :when (> (asked p h) 1)] [p (hex h)])))
         (bad "the honest peer served both" 2 (count (get by-peer "good")))
         (bad "every wanted hash is served" [] (client/unserved c))
         (nothing-bad-stored n src))))))

(defn lying-peer-and-honest-peer
  "Integration: a liar sends good items, then a forged one, for a value it is
   asked for; an honest peer holds the same value. The client ends with
   the correct data (every node of the value, byte for byte the source's),
   stores nothing bad, ends the lying session with one fatal `bad-entry` and keeps the
   honest one."
  []
  (let [src (store/mem-store)
        root (v/hash (map-of src 30))
        items (items-of src root)
        ;; the 20th item, a hamt/entry: its key-hash is not its key-ref (the hash
        ;; does not cover key-hash, so only the shape check can see it)
        k (first (filter #(= "hamt/entry" (first (:entry (nth items %)))) (range 20 (count items))))
        forged (assoc-in items [k :entry 1 :key-hash] (:hash (first items)))
        lie (fn [sid _] (frames-of sid (mapv bin/encode-item forged)))
        n (net (array-map "liar" lie "honest" (honest src)) [root])
        c (:client n)]
    (client/request! c root)
    (pump! n)
    (concat
     (when-not (< 15 k (count items)) [(str "the test value is too small: bad item at " k)])
     (bad "the first get went to the liar" "liar" (first (first (gets n))))
     (bad "the liar's session ended" :ended (client/session-status c "liar"))
     (bad "the honest session is open" :open (client/session-status c "honest"))
     (bad "one fatal bad-entry, to the liar" [["liar" "bad-entry"]]
          (mapv (fn [[p m]] [p (get-in m [:value "code"])]) (fatal-errors n)))
     (bad "the root is done" true (client/done? c root))
     (bad "the root is had" true (client/have? c root))
     (bad "every node of the value is stored, equal to the source's" []
          (vec (map (comp hex :hash)
                    (remove (fn [it] (same-entry? (:hash it) (:entry it)
                                                  (store/await (store/s-get (:store c) (:hash it)))))
                            items))))
     (bad "walking the client's store gives the source's reveal order"
          (vec (reveal/page-order src root))
          (vec (reveal/page-order (:store c) root)))
     (nothing-bad-stored n src)
     (bad "nothing is waiting" [] (client/unserved c))
     (bad "the liar was asked once, then the honest peer once" [["liar" 1] ["honest" 1]]
          (gets n)))))

(defn a-session-whose-stream-ends-early-leaves-no-unchecked-header
  "C31 at the session: a PARTIAL stream that ends without a header's root drops
   the header (the session goes on); a FIN without PARTIAL and without the root
   is `bad-entry` and ends the session."
  []
  (let [src (store/mem-store)
        root (v/hash (vector-value src 40))
        header (bin/encode-item (first (items-of src root)))
        run (fn [partial?]
              (let [n (net {"p" silent} [root])
                    c (:client n)]
                (client/request! c root)
                (let [[_ sid] (first (gets n))]
                  (client/on-frame! c "p" (first (frame/chunk-items sid [header] {:partial? partial?})))
                  [n c])))
        [pn pc] (run true)
        [fn' fc] (run false)]
    (concat
     (bad "PARTIAL: the session goes on" :open (client/session-status pc "p"))
     (bad "PARTIAL: the header is gone" false (stored? (:store pc) root))
     (bad "PARTIAL: no fatal error" [] (fatal-errors pn))
     (bad "FIN without the root: the session ends" :ended (client/session-status fc "p"))
     (bad "FIN without the root: bad-entry" ["bad-entry"]
          (mapv (fn [[_ m]] (get-in m [:value "code"])) (fatal-errors fn')))
     (bad "FIN without the root: no header kept" false (stored? (:store fc) root)))))

(defn client-stream-ids-are-its-own-counter
  "C37 at the client: a stream id is the client's counter (odd for an offerer),
   never derived from the hash; a re-asked hash gets a new id."
  []
  (let [src (store/mem-store)
        x (v/hash (vector-value src 40))
        y (v/hash (map-of src 30))
        n (net {"liar" (liar src :bad-measure) "good" (honest src)} [x y])
        c (:client n)]
    (client/request! c x) (client/request! c y)
    (client/request! c x)                                    ; already wanted: no new get
    (pump! n)
    (concat
     (bad "ids are odd and rise by two on each session, from 1"
          [["liar" 1] ["good" 1] ["good" 3]] (gets n))
     (bad "an id is not the hash" false
          (boolean (some (fn [[_ sid]] (or (= sid (hex x)) (= sid (hex y)))) (gets n)))))))

(def checks
  "Test name (spec §13) -> check. Run order is this order."
  (array-map
   "bad-entry-ends-the-session" bad-entry-ends-the-session
   "bad-state-is-refused-as-bad-entry" bad-state-is-refused-as-bad-entry
   "nothing-unverified-reaches-the-store" nothing-unverified-reaches-the-store
   "every-page-verifies-in-any-order" every-page-verifies-in-any-order
   "literal-hash-mismatch-is-refused" literal-hash-mismatch-is-refused
   "hamt-bitmap-shape-refusals" hamt-bitmap-shape-refusals
   "a-session-whose-stream-ends-early-leaves-no-unchecked-header" a-session-whose-stream-ends-early-leaves-no-unchecked-header
   "one-session-ending-leaves-others-untouched" one-session-ending-leaves-others-untouched
   "bad-peer-streams-are-reasked-from-others" bad-peer-streams-are-reasked-from-others
   "lying-peer-and-honest-peer" lying-peer-and-honest-peer
   "client-stream-ids-are-its-own-counter" client-stream-ids-are-its-own-counter))

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
