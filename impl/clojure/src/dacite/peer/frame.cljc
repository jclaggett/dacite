(ns dacite.peer.frame
  "Peer protocol v1 framing: the envelope and the chunking of item streams.

   Spec: docs/spec/peer-v1.md. Fixtures: fixtures/peer-v1/.

   A frame is one data-channel message:

     type    u8
     flags   u8     bit 0 = FIN, bit 1 = PARTIAL, others zero
     stream  u32    big-endian; 0 is the session
     payload rest   at most `max-payload` bytes

   A control message is one frame whose payload is a wire-v1 `Lit`
   (see `dacite.peer.msg`). An item stream is a byte stream of wire-v1
   `Item`s (node or literal, from `dacite.wire.binary`) packed into
   frames. Nothing here encodes a value, hashes or stores anything; see
   `dacite.peer.items` for that.

   Bytes are Java `byte[]` on the JVM and `Uint8Array` on CLJS/nbb."
  (:require [dacite.wire.binary :as bin]))

;; =============================================================================
;; Constants
;; =============================================================================

(def max-frame
  "Largest frame in bytes, header included. 16 KiB is the size every
   WebRTC stack can send and receive without partial-message delivery."
  16384)

(def header-bytes 6)

(def max-payload (- max-frame header-bytes))

(def min-max-frame
  "Smallest `max-frame` a peer may advertise. A control message must fit,
   and so must the largest node item (the leaf page budget, 4096 wire bytes,
   plus the page's own fields: about 4.2 KB at most) plus the 37-byte item
   header and the 6-byte envelope (spec 3.2). `max-node-item-bytes` is that
   bound."
  8192)

(def max-node-item-bytes
  "What the sender expects the largest node item to be on the wire. A page
   is at most `lit/page-budget` (4096) bytes by the page size model, which
   counts a tag on every single element and a header on every run, so it
   follows the wire size; the page's own fields (its measure and type) are
   under 400 more. The largest seen, over strings, blobs, ints, mixes of
   one-byte kinds in groups of one to three, nested vectors and maps, is
   about 4 200. A spine or bitmap page holds at most 32 refs of 33 bytes
   and a measure. `min-max-frame` must leave room for this, its item header
   and the envelope. Tests keep it true (the property test, the one-byte
   mixes, and `(<= (+ page-budget 400) max-node-item-bytes)`)."
  5000)

(def default-max-item
  "Default bound on one wire-v1 item (header plus payload) in a stream."
  1048576)

(def item-header-bytes
  "enc u8 + hash 32 + plen u32."
  bin/item-header-bytes)

(defn max-literal-item
  "Largest literal Item (header included) a sender may put in a stream
   whose frames are at most `max-frame-bytes`: one frame payload. A
   literal never spans frames."
  ([] (max-literal-item max-frame))
  ([max-frame-bytes] (- max-frame-bytes header-bytes)))

(defn min-max-frame-fits-node?
  "True when a frame of `min-max-frame` carries the largest expected node item
   (`max-node-item-bytes`, header included) in one payload. Spec 3.2: the
   smallest frame a peer may ask for must hold the largest node item the
   sender will send. `test`ed rather than assumed: raise `min-max-frame` with
   the leaf page budget."
  []
  (>= (max-literal-item min-max-frame) max-node-item-bytes))

(def type->id
  {:hello 0x01
   :get 0x02
   :item-stream 0x03
   :ack 0x04
   :release 0x05
   :cancel 0x06
   :error 0x07})

(def id->type (into {} (map (fn [[k v]] [v k])) type->id))

(def stream-rule
  "Which streams a message type may use. `:session` is stream 0 only,
   `:request` is a request's id (1 or more), `:any` is either."
  {:hello :session
   :ack :session
   :release :session
   :get :request
   :item-stream :request
   :cancel :request
   :error :any})

(def flag-fin 0x01)
(def flag-partial 0x02)

(def max-stream-id 4294967295)

;; =============================================================================
;; Portable byte helpers
;; =============================================================================

(defn- make-bytes
  [n]
  #?(:clj (byte-array (int n))
     :cljs (js/Uint8Array. n)))

(defn- bget
  [bs i]
  #?(:clj (bit-and 0xff (aget ^bytes bs (int i)))
     :cljs (aget bs i)))

(defn- bset!
  [bs i v]
  #?(:clj (aset-byte ^bytes bs (int i) (unchecked-byte (bit-and 0xff v)))
     :cljs (aset bs i (bit-and 0xff v))))

(defn- blen
  [bs]
  (bin/byte-len bs))

(defn slice
  "Fresh copy of `bs[start, end)`."
  [bs start end]
  (let [n (- end start)
        out (make-bytes n)]
    #?(:clj (System/arraycopy ^bytes bs (int start) ^bytes out 0 (int n))
       :cljs (.set out (.subarray bs start end)))
    out))

(defn concat-bytes
  "Concatenate byte arrays."
  [bss]
  (let [n (reduce + 0 (map blen bss))
        out (make-bytes n)]
    (loop [bss (seq bss) at 0]
      (when bss
        (let [b (first bss)
              m (blen b)]
          #?(:clj (System/arraycopy ^bytes b 0 ^bytes out (int at) (int m))
             :cljs (.set out b at))
          (recur (next bss) (+ at m)))))
    out))

(defn- u32-at
  [bs i]
  (+ (* (bget bs i) 16777216)
     (* (bget bs (+ i 1)) 65536)
     (* (bget bs (+ i 2)) 256)
     (bget bs (+ i 3))))

(defn- put-u32!
  [bs i n]
  (bset! bs i (mod (quot n 16777216) 256))
  (bset! bs (+ i 1) (mod (quot n 65536) 256))
  (bset! bs (+ i 2) (mod (quot n 256) 256))
  (bset! bs (+ i 3) (mod n 256)))

(defn- fail!
  [reason msg data]
  (throw (ex-info msg (assoc data :reason reason))))

;; =============================================================================
;; Envelope
;; =============================================================================

(defn- check-stream!
  "A known type on a stream its rule allows. An unknown type is not checked."
  [type stream]
  (case (stream-rule type)
    :session (when-not (zero? stream)
               (fail! :wrong-stream "this message is for stream 0"
                      {:type type :stream stream}))
    :request (when (zero? stream)
               (fail! :wrong-stream "this message needs a request's stream id"
                      {:type type :stream stream}))
    nil))

(defn encode-frame
  "Frame bytes for {:type kw|u8, :stream n, :flags n, :payload bytes}.

   Throws when the frame would exceed `max-frame` (or `:max-frame`)."
  [{:keys [type stream flags payload max-frame-bytes]
    :or {flags 0 stream 0 max-frame-bytes max-frame}}]
  (let [tid (if (keyword? type)
              (or (type->id type)
                  (fail! :unknown-type "unknown message type" {:type type}))
              type)
        payload (bin/as-wire-bytes payload)
        n (+ header-bytes (blen payload))]
    (when-not (<= 1 tid 255)
      (fail! :unknown-type "bad type id" {:type type}))
    (when-not (<= 0 stream max-stream-id)
      (fail! :bad-stream "stream id out of range" {:stream stream}))
    (when (> n max-frame-bytes)
      (fail! :frame-too-large "frame exceeds max-frame" {:size n :max max-frame-bytes}))
    (check-stream! (id->type tid) stream)
    (let [out (make-bytes n)]
      (bset! out 0 tid)
      (bset! out 1 flags)
      (put-u32! out 2 stream)
      (dotimes [i (blen payload)]
        (bset! out (+ header-bytes i) (bget payload i)))
      out)))

(defn decode-frame
  "Parse one frame. Returns {:type kw|u8 :type-id n :flags n :fin? :partial?
   :stream n :payload bytes}.

   Rejects a short frame, one over `max-frame`, reserved flag bits,
   PARTIAL without FIN, type id 0, and a known type on a stream its
   `stream-rule` does not allow. An unknown type id above 0 is
   returned as is: the caller answers it with an `unsupported` error."
  ([bs] (decode-frame bs max-frame))
  ([bs max-frame-bytes]
   (let [bs (bin/as-wire-bytes bs)
         n (blen bs)]
     (when (< n header-bytes)
       (fail! :frame-too-short "frame shorter than the envelope" {:size n}))
     (when (> n max-frame-bytes)
       (fail! :frame-too-large "frame exceeds max-frame" {:size n :max max-frame-bytes}))
     (let [tid (bget bs 0)
           flags (bget bs 1)
           fin? (pos? (bit-and flags flag-fin))
           partial? (pos? (bit-and flags flag-partial))]
       (when (zero? tid)
         (fail! :unknown-type "type id 0 is reserved" {}))
       (when (pos? (bit-and flags 0xfc))
         (fail! :bad-flags "reserved flag bits set" {:flags flags}))
       (when (and partial? (not fin?))
         (fail! :partial-without-fin "PARTIAL needs FIN" {:flags flags}))
       (check-stream! (id->type tid) (u32-at bs 2))
       {:type (get id->type tid tid)
        :type-id tid
        :flags flags
        :fin? fin?
        :partial? partial?
        :stream (u32-at bs 2)
        :payload (slice bs header-bytes n)}))))

;; =============================================================================
;; Chunking an item stream
;; =============================================================================

(defn chunk-items
  "Reference sender: pack encoded wire-v1 items (`item-bytes`) into frames.

   Whole items go into a frame while they fit its payload; an item that
   does not fit starts the next frame. An item bigger than one payload (a
   hashed entry; a literal is never this big) starts a fresh frame and is
   cut into payload-sized pieces, and the next item follows its tail.
   The last frame carries FIN, and PARTIAL when `:partial?`. No items
   gives one empty FIN frame.

   A sender may flush shorter frames while it paces a stream. A receiver
   must not rely on this cut: it reassembles a byte stream."
  ([stream item-bytes] (chunk-items stream item-bytes {}))
  ([stream item-bytes {:keys [partial? max-frame-bytes]
                       :or {max-frame-bytes max-frame}}]
   (let [room (- max-frame-bytes header-bytes)
         seal (fn [pieces cur]
                (if (seq cur) (conj pieces cur) pieces))
         ;; state: [finished-frames, items-in-current-frame, bytes-in-current-frame]
         [pieces cur _]
         (reduce
          (fn [[pieces cur used] item]
            (let [n (blen item)]
              (cond
                (and (<= n room) (<= (+ used n) room))
                [pieces (conj cur item) (+ used n)]

                (<= n room)
                [(seal pieces cur) [item] n]

                :else
                (let [pieces (seal pieces cur)
                      cuts (range 0 n room)
                      full (butlast cuts)
                      tail (last cuts)]
                  [(into pieces (map (fn [from] [(slice item from (+ from room))])) full)
                   [(slice item tail n)]
                   (- n tail)]))))
          [[] [] 0]
          item-bytes)
         pieces (seal pieces cur)
         pieces (if (empty? pieces) [[]] pieces)
         nframes (count pieces)]
     (vec (map-indexed
           (fn [i parts]
             (let [last? (= i (dec nframes))]
               (encode-frame {:type :item-stream
                              :stream stream
                              :flags (if last?
                                       (bit-or flag-fin (if partial? flag-partial 0))
                                       0)
                              :payload (concat-bytes parts)
                              :max-frame-bytes max-frame-bytes})))
           pieces)))))

;; =============================================================================
;; Receiving an item stream
;; =============================================================================

(defn reader
  "Receiver state for one item stream."
  ([stream] (reader stream default-max-item (max-literal-item)))
  ([stream max-item] (reader stream max-item (max-literal-item)))
  ([stream max-item max-literal]
   {:stream stream
    :max-item max-item
    :max-literal max-literal
    :chunks []
    :buffered 0
    :need nil
    :fin? false
    :partial? false
    :frames 0
    :items 0}))

(defn- check-header!
  "Check the Item header at `at` in `bs` against the stream's limits and
   return the item's total size. Throws on an `enc` other than 0 (node) or
   1 (literal), on an item over `max-item`, and on a literal item over
   `max-literal` (one frame payload: a literal never spans frames). A
   literal is checked from its header, so an oversized one is refused
   before its bytes arrive."
  [bs at max-item max-literal]
  (let [{:keys [enc total]} (bin/item-header bs at)]
    (when-not (contains? #{0 1} enc)
      (fail! :bad-item "unknown item enc" {:enc enc}))
    (when (> total max-item)
      (fail! :item-too-large "item exceeds max-item"
             {:size total :max max-item}))
    (when (and (= 1 enc) (> total max-literal))
      (fail! :literal-too-large "a literal item must fit one frame payload"
             {:size total :max max-literal}))
    total))

(defn feed
  "Add one decoded frame (from `decode-frame`) to the stream.

   Returns [state' item-byte-arrays], the items that the frame completed.
   Throws when the frame is not an item-stream frame for this stream, or
   arrives after FIN.

   Bytes are kept as a list of chunks and joined only when an item can be
   cut, so an item that spans many frames is copied once, not once per
   frame."
  [{:keys [stream max-item max-literal chunks buffered need] :as st} {:keys [type] :as fr}]
  (when (:fin? st)
    (fail! :after-fin "frame after FIN" {:stream stream}))
  (when-not (= :item-stream type)
    (fail! :wrong-type "not an item-stream frame" {:type type}))
  (when-not (= stream (:stream fr))
    (fail! :wrong-stream "frame for another stream" {:expected stream :got (:stream fr)}))
  (let [chunks (conj chunks (:payload fr))
        buffered (+ buffered (blen (:payload fr)))
        finish (fn [chunks buffered need items]
                 (when (and (:fin? fr) (pos? buffered))
                   (fail! :truncated-item "stream ended inside an item" {:left buffered}))
                 [(assoc st
                         :chunks chunks
                         :buffered buffered
                         :need need
                         :fin? (:fin? fr)
                         :partial? (:partial? fr)
                         :frames (inc (:frames st))
                         :items (+ (:items st) (count items)))
                  items])]
    (if (and need (< buffered need))
      (finish chunks buffered need [])
      (let [bs (concat-bytes chunks)
            n (blen bs)
            [items at need]
            (loop [at 0 need need items []]
              (let [avail (- n at)
                    need (or need
                             (when (>= avail item-header-bytes)
                               (check-header! bs at max-item max-literal)))]
                (cond
                  (nil? need) [items at nil]
                  (< avail need) [items at need]
                  :else (recur (+ at need) nil (conj items (slice bs at (+ at need)))))))]
        (finish (if (< at n) [(slice bs at n)] [])
                (- n at)
                need
                items)))))

;; =============================================================================
;; Stream ids of one connection
;; =============================================================================

(defn stream-table
  "Stream-id state for one side of a connection. `role` is `:offerer`
   (the peer that sent the WebRTC offer: it opens odd ids) or `:answerer`
   (even ids). `max-streams` bounds the requests the *remote* may have
   open at once.

   Pure: each function returns a new table."
  [role max-streams]
  {:role role
   :max-streams max-streams
   :next (if (= role :offerer) 1 2)
   :used #{}
   :open #{}})

(defn open-local
  "Allocate the next id for a request this side sends. Returns [table id].
   Ids rise by 2 and are never reused."
  [{:keys [next used] :as t}]
  (when (> next max-stream-id)
    (fail! :streams-exhausted "no stream ids left on this connection" {}))
  [(-> t
       (assoc :next (+ next 2))
       (update :used conj next)
       (update :open conj next))
   next])

(defn open-remote
  "Accept a request id the remote chose. Returns the new table, or throws:

     :wrong-stream      id 0 (a request is 1 or more)
     :bad-stream-parity the id has this side's parity, not the remote's
     :duplicate-stream  the id was already used on this connection
     :too-many-streams  the remote already has `max-streams` open"
  [{:keys [role used open max-streams] :as t} id]
  (let [remote-odd? (= role :answerer)]
    (when-not (<= 1 id max-stream-id)
      (fail! :wrong-stream "a request id is 1 or more" {:stream id}))
    (when-not (= remote-odd? (odd? id))
      (fail! :bad-stream-parity "id has the wrong parity for the peer"
             {:stream id :role role}))
    (when (contains? used id)
      (fail! :duplicate-stream "stream id already used" {:stream id}))
    (let [theirs (count (filter #(= remote-odd? (odd? %)) open))]
      (when (>= theirs max-streams)
        (fail! :too-many-streams "too many open requests" {:max max-streams})))
    (-> t (update :used conj id) (update :open conj id))))

(defn live?
  "True while `id` is open. Frames for a closed id are ignored."
  [{:keys [open]} id]
  (contains? open id))

(defn close-stream
  "Close `id` (its answer ended, or it was cancelled). The id stays used."
  [t id]
  (update t :open disj id))
