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
   `Item`s cut into frames. Nothing here hashes or stores anything.

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
  "Smallest `max-frame` a peer may advertise. A control message must fit."
  1024)

(def default-max-item
  "Default bound on one wire-v1 item (header plus payload) in a stream."
  1048576)

(def item-header-bytes
  "enc u8 + hash 32 + plen u32."
  37)

(def type->id
  {:hello 0x01
   :have? 0x02
   :have 0x03
   :get 0x04
   :item-stream 0x05
   :ack 0x06
   :release 0x07
   :cancel 0x08
   :error 0x09})

(def id->type (into {} (map (fn [[k v]] [v k])) type->id))

(def stream-rule
  "Which streams a message type may use. `:session` is stream 0 only,
   `:request` is a request's id (1 or more), `:any` is either."
  {:hello :session
   :ack :session
   :release :session
   :have? :request
   :have :request
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
  "Reference sender: cut the concatenated `item-bytes` (a seq of encoded
   wire-v1 items) into frames of exactly `max-payload` bytes, the last
   one shorter. The last frame carries FIN, and PARTIAL when `:partial?`.
   No items gives one empty FIN frame.

   A sender may flush shorter frames while it paces a stream. A receiver
   must not rely on this cut."
  ([stream item-bytes] (chunk-items stream item-bytes {}))
  ([stream item-bytes {:keys [partial? max-frame-bytes]
                       :or {max-frame-bytes max-frame}}]
   (let [room (- max-frame-bytes header-bytes)
         all (concat-bytes item-bytes)
         total (blen all)
         nframes (max 1 (quot (+ total room -1) room))]
     (mapv (fn [i]
             (let [from (* i room)
                   to (min total (+ from room))
                   last? (= i (dec nframes))]
               (encode-frame {:type :item-stream
                              :stream stream
                              :flags (if last?
                                       (bit-or flag-fin (if partial? flag-partial 0))
                                       0)
                              :payload (slice all from to)
                              :max-frame-bytes max-frame-bytes})))
           (range nframes)))))

;; =============================================================================
;; Receiving an item stream
;; =============================================================================

(defn reader
  "Receiver state for one item stream."
  ([stream] (reader stream default-max-item))
  ([stream max-item]
   {:stream stream
    :max-item max-item
    :buf (make-bytes 0)
    :fin? false
    :partial? false
    :frames 0
    :items 0}))

(defn- split-items
  "Complete items at the front of `buf`, and the rest.
   Throws on an `enc` other than 0 (node) or an item over `max-item`."
  [buf max-item]
  (let [n (blen buf)]
    (loop [at 0 items []]
      (let [avail (- n at)]
        (if (< avail item-header-bytes)
          [items (slice buf at n)]
          (let [enc (bget buf at)
                plen (u32-at buf (+ at 33))
                total (+ item-header-bytes plen)]
            ;; v1 item streams carry node items only (enc 0).
            (when-not (zero? enc)
              (fail! :bad-item (if (= 1 enc)
                                 "a literal item is not allowed in v1"
                                 "unknown item enc")
                     {:enc enc}))
            (when (> total max-item)
              (fail! :item-too-large "item exceeds max-item"
                     {:size total :max max-item}))
            (if (< avail total)
              [items (slice buf at n)]
              (recur (+ at total) (conj items (slice buf at (+ at total)))))))))))

(defn feed
  "Add one decoded frame (from `decode-frame`) to the stream.

   Returns [state' item-byte-arrays], the items that the frame completed.
   Throws when the frame is not an item-stream frame for this stream, or
   arrives after FIN."
  [{:keys [stream max-item buf] :as st} {:keys [type] :as fr}]
  (when (:fin? st)
    (fail! :after-fin "frame after FIN" {:stream stream}))
  (when-not (= :item-stream type)
    (fail! :wrong-type "not an item-stream frame" {:type type}))
  (when-not (= stream (:stream fr))
    (fail! :wrong-stream "frame for another stream" {:expected stream :got (:stream fr)}))
  (let [[items rest-buf] (split-items (concat-bytes [buf (:payload fr)]) max-item)]
    (when (and (:fin? fr) (pos? (blen rest-buf)))
      (fail! :truncated-item "stream ended inside an item" {:left (blen rest-buf)}))
    [(assoc st
            :buf rest-buf
            :fin? (:fin? fr)
            :partial? (:partial? fr)
            :frames (inc (:frames st))
            :items (+ (:items st) (count items)))
     items]))

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
