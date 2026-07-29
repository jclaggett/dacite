(ns dacite.wire.binary
  "Dacite wire format v1 — chunk-only binary codec.

   Spec: docs/spec/wire-v1.md
   Fixtures: fixtures/wire-v1/

   Encodes/decodes ChunkMessage with items as node or literal payloads.
   EDN transport remains in dacite.wire for debug/transition."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value.types :as types])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(defn- magic-bytes
  ^bytes []
  (byte-array [(unchecked-byte 0x44) (unchecked-byte 0x41)
               (unchecked-byte 0x43) (unchecked-byte 0x31)])) ; "DAC1"

(def version 1)
(def msg-type-chunk 0x01)

(def enc-node 0x00)
(def enc-literal 0x01)

(def kind-scalar 0x00)
(def kind-ft 0x01)
(def kind-hamt 0x02)
(def kind-collection 0x03)

;; Literal / scalar type ids (wire-v1.md + intermediate spine literals 2c′)
(def type-id->name
  {0x00 "null"
   0x01 "bool"
   0x02 "char"
   0x03 "i64"
   0x04 "f64"
   0x10 "string"
   0x11 "blob"
   0x20 "vector"
   0x21 "map"
   0x22 "set"
   ;; Intermediate pack literals: body = ordered leaf lits (or entry pair)
   0x30 "ft/empty"
   0x31 "ft/digit"
   0x32 "ft/node"
   0x33 "ft/deep"
   0x40 "hamt/empty"
   0x41 "hamt/entry"
   0x42 "hamt/bitmap"})

(def name->type-id
  (into {} (map (fn [[k v]] [v k]) type-id->name)))

(def coll-id->name
  {0x00 "vector"
   0x01 "string"
   0x02 "blob"
   0x03 "map"
   0x04 "set"})

(def name->coll-id
  (into {} (map (fn [[k v]] [v k]) coll-id->name)))

(def ft-subtype->name
  {0x00 "ft/empty"
   0x02 "ft/digit"
   0x03 "ft/node"
   0x04 "ft/deep"})

(def name->ft-subtype
  (into {} (map (fn [[k v]] [v k]) ft-subtype->name)))

(def hamt-subtype->name
  {0x00 "hamt/empty"
   0x01 "hamt/entry"
   0x02 "hamt/bitmap"})

(def name->hamt-subtype
  (into {} (map (fn [[k v]] [v k]) hamt-subtype->name)))

;; =============================================================================
;; Buffer helpers
;; =============================================================================

(defn- bb ^ByteBuffer [n]
  (doto (ByteBuffer/allocate (int n))
    (.order ByteOrder/BIG_ENDIAN)))

(defn- wrap-bytes ^ByteBuffer [^bytes bs]
  (doto (ByteBuffer/wrap bs)
    (.order ByteOrder/BIG_ENDIAN)))

(defn- put-u8 [^ByteBuffer buf n]
  (.put buf (unchecked-byte (bit-and n 0xff))))

(defn- put-u32 [^ByteBuffer buf n]
  (.putInt buf (unchecked-int n)))

(defn- put-u64 [^ByteBuffer buf n]
  (.putLong buf (long n)))

(defn- put-i64 [^ByteBuffer buf n]
  (.putLong buf (long n)))

(defn- get-u8 [^ByteBuffer buf]
  (Byte/toUnsignedInt (.get buf)))

(defn- get-u32 [^ByteBuffer buf]
  (Integer/toUnsignedLong (.getInt buf)))

(defn- get-u64 [^ByteBuffer buf]
  (.getLong buf))

(defn- get-i64 [^ByteBuffer buf]
  (.getLong buf))

(defn- put-hash [^ByteBuffer buf [a b c d]]
  (put-u64 buf a)
  (put-u64 buf b)
  (put-u64 buf c)
  (put-u64 buf d))

(defn- get-hash [^ByteBuffer buf]
  [(get-u64 buf) (get-u64 buf) (get-u64 buf) (get-u64 buf)])

(defn- put-bytes [^ByteBuffer buf ^bytes bs]
  (.put buf bs))

(defn- get-bytes [^ByteBuffer buf n]
  (let [a (byte-array (int n))]
    (.get buf a)
    a))

(defn- remaining ^long [^ByteBuffer buf]
  (.remaining buf))

(defn- ensure-remaining [^ByteBuffer buf n]
  (when (< (remaining buf) n)
    (throw (ex-info "truncated wire message" {:need n :have (remaining buf)}))))

(defn bytes->hex
  "Lowercase hex of a byte array (no whitespace)."
  [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (str sb)))

(defn hex->bytes
  "Parse lowercase/uppercase hex string to byte array."
  [^String hex]
  (let [s (str/replace hex #"\s" "")
        n (count s)]
    (when-not (even? n)
      (throw (ex-info "hex length must be even" {:n n})))
    (byte-array
     (for [i (range 0 n 2)]
       (unchecked-byte (Integer/parseInt (subs s i (+ i 2)) 16))))))

(defn hash->bytes
  "32-byte BE encoding of a hash vector."
  [h]
  (let [buf (bb 32)]
    (put-hash buf h)
    (.array buf)))

(defn bytes->hash
  [^bytes bs]
  (when-not (= 32 (alength bs))
    (throw (ex-info "hash must be 32 bytes" {:n (alength bs)})))
  (get-hash (wrap-bytes bs)))

;; =============================================================================
;; Measure
;; =============================================================================

(defn- put-measure [^ByteBuffer buf {:keys [count size-bytes elements-fuse]}]
  (put-u64 buf (or count 0))
  (put-u64 buf (or size-bytes 0))
  (put-hash buf elements-fuse))

(defn- get-measure [^ByteBuffer buf]
  {:count (get-u64 buf)
   :size-bytes (get-u64 buf)
   :elements-fuse (get-hash buf)})

;; =============================================================================
;; Literal encode / decode
;; =============================================================================

(declare encode-lit-bytes decode-lit)

(defn- utf8-bytes ^bytes [s]
  (.getBytes (str s) StandardCharsets/UTF_8))

(defn- utf8-str [^bytes bs]
  (String. bs StandardCharsets/UTF_8))

(defn encode-lit-bytes
  "Encode a recursive literal form {:type t :body b} to payload bytes.
   body for collections is a vector of nested forms (map: pairs as [k v])."
  [{:keys [type body] :as form}]
  (let [tid (or (name->type-id (str type))
                (throw (ex-info "unknown literal type" {:type type})))]
    (case (str type)
      "null"
      (byte-array [tid])

      "bool"
      (byte-array [tid (if body 1 0)])

      "char"
      (let [bs (utf8-bytes (str body))
            buf (bb (+ 2 (alength bs)))]
        (put-u8 buf tid)
        (put-u8 buf (alength bs))
        (put-bytes buf bs)
        (.array buf))

      "i64"
      (let [buf (bb 9)]
        (put-u8 buf tid)
        (put-i64 buf body)
        (.array buf))

      "f64"
      (let [buf (bb 9)]
        (put-u8 buf tid)
        (.putDouble buf (double body))
        (.array buf))

      "string"
      (let [bs (utf8-bytes body)
            buf (bb (+ 5 (alength bs)))]
        (put-u8 buf tid)
        (put-u32 buf (alength bs))
        (put-bytes buf bs)
        (.array buf))

      "blob"
      (let [bs (if (bytes? body) body (byte-array (map unchecked-byte body)))
            buf (bb (+ 5 (alength bs)))]
        (put-u8 buf tid)
        (put-u32 buf (alength bs))
        (put-bytes buf bs)
        (.array buf))

      ("vector" "set" "ft/empty" "ft/digit" "ft/node" "ft/deep"
                "hamt/empty" "hamt/bitmap")
      ;; Ordered sequence of nested lits (ft/hamt leaf payloads share this shape)
      (let [els (or body [])
            children (mapv encode-lit-bytes els)
            total (reduce + 5 (map alength children))
            buf (bb total)]
        (put-u8 buf tid)
        (put-u32 buf (count children))
        (doseq [c children] (put-bytes buf c))
        (.array buf))

      "map"
      (let [pairs (mapv (fn [[k v]]
                          (let [kb (encode-lit-bytes k)
                                vb (encode-lit-bytes v)]
                            [kb vb]))
                        body)
            total (reduce + 5 (map (fn [[k v]] (+ (alength k) (alength v))) pairs))
            buf (bb total)]
        (put-u8 buf tid)
        (put-u32 buf (count pairs))
        (doseq [[k v] pairs]
          (put-bytes buf k)
          (put-bytes buf v))
        (.array buf))

      "hamt/entry"
      ;; Body is [key-lit val-lit]
      (let [kb (encode-lit-bytes (nth body 0))
            vb (encode-lit-bytes (nth body 1))
            buf (bb (+ 1 (alength kb) (alength vb)))]
        (put-u8 buf tid)
        (put-bytes buf kb)
        (put-bytes buf vb)
        (.array buf))

      (throw (ex-info "unsupported literal type" {:type type :form form})))))

(defn decode-lit
  "Decode one Lit from buffer; advances position. Returns {:type :body}."
  [^ByteBuffer buf]
  (ensure-remaining buf 1)
  (let [tid (get-u8 buf)
        tname (or (type-id->name tid)
                  (throw (ex-info "unknown literal type_id" {:id tid})))]
    (case tname
      "null" {:type "null" :body nil}

      "bool"
      (do (ensure-remaining buf 1)
          {:type "bool" :body (not (zero? (get-u8 buf)))})

      "char"
      (let [dlen (get-u8 buf)
            bs (get-bytes buf dlen)]
        {:type "char" :body (first (seq (utf8-str bs)))})

      "i64"
      (do (ensure-remaining buf 8)
          {:type "i64" :body (get-i64 buf)})

      "f64"
      (do (ensure-remaining buf 8)
          {:type "f64" :body (.getDouble buf)})

      "string"
      (let [n (get-u32 buf)
            bs (get-bytes buf n)]
        {:type "string" :body (utf8-str bs)})

      "blob"
      (let [n (get-u32 buf)
            bs (get-bytes buf n)]
        {:type "blob" :body (vec (map #(bit-and % 0xff) bs))})

      ("vector" "set" "ft/empty" "ft/digit" "ft/node" "ft/deep"
                "hamt/empty" "hamt/bitmap")
      (let [n (int (get-u32 buf))
            kids (mapv (fn [_] (decode-lit buf)) (range n))]
        {:type tname :body kids})

      "map"
      (let [n (int (get-u32 buf))
            pairs (mapv (fn [_]
                          [(decode-lit buf) (decode-lit buf)])
                        (range n))]
        {:type "map" :body pairs})

      "hamt/entry"
      {:type "hamt/entry"
       :body [(decode-lit buf) (decode-lit buf)]}

      (throw (ex-info "unsupported literal type" {:type tname})))))

(defn decode-lit-bytes
  "Decode a full literal payload byte array."
  [^bytes bs]
  (let [buf (wrap-bytes bs)
        form (decode-lit buf)]
    (when (pos? (remaining buf))
      (throw (ex-info "trailing garbage in literal payload"
                      {:remaining (remaining buf)})))
    form))

;; =============================================================================
;; Node encode / decode
;; =============================================================================

(defn- scalar-data-bytes
  "Canonical scalar data bytes (no type_id) from store entry."
  [[type-name data :as entry]]
  (byte-array (map unchecked-byte (types/encode-value entry))))

(defn encode-node-bytes
  "Encode a store entry [type-name data] as node payload bytes."
  [[type-name data :as entry]]
  (let [t (str type-name)]
    (cond
      (#{"null" "bool" "char" "i64" "f64" "i8" "i16" "i32"
         "u8" "u16" "u32" "u64" "f32" "negative"} t)
      (let [tid (or (name->type-id t)
                    (throw (ex-info "scalar type not in wire-v1 table yet" {:type t})))
            data-bs (scalar-data-bytes entry)
            ;; kind + type_id + dlen + data
            buf (bb (+ 3 (alength data-bs)))]
        (put-u8 buf kind-scalar)
        (put-u8 buf tid)
        (put-u8 buf (alength data-bs))
        (put-bytes buf data-bs)
        (.array buf))

      (str/starts-with? t "ft/")
      (let [sub (or (name->ft-subtype t)
                    (throw (ex-info "unknown ft type" {:type t})))
            m (:measure data)
            children (case t
                       "ft/empty" []
                       "ft/deep" [(:left data) (:spine data) (:right data)]
                       (:children data))
            n (count children)
            buf (bb (+ 2 48 1 (* 32 n)))]
        (put-u8 buf kind-ft)
        (put-u8 buf sub)
        (put-measure buf m)
        (put-u8 buf n)
        (doseq [h children] (put-hash buf h))
        (.array buf))

      (str/starts-with? t "hamt/")
      (case t
        "hamt/empty"
        (let [buf (bb (+ 2 48))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 0)
          (put-measure buf (:measure data))
          (.array buf))

        "hamt/entry"
        (let [buf (bb (+ 2 48 (* 3 32)))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 1)
          (put-measure buf (:measure data))
          (put-hash buf (:key-hash data))
          (put-hash buf (:key-ref data))
          (put-hash buf (:val-ref data))
          (.array buf))

        "hamt/bitmap"
        (let [ch (:children data)
              n (count ch)
              buf (bb (+ 2 48 4 1 (* 32 n)))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 2)
          (put-measure buf (:measure data))
          (put-u32 buf (:bitmap data))
          (put-u8 buf n)
          (doseq [h ch] (put-hash buf h))
          (.array buf))

        (throw (ex-info "unknown hamt type" {:type t})))

      (#{"vector" "string" "blob" "map" "set"} t)
      (let [cid (name->coll-id t)
            buf (bb 50)]
        (put-u8 buf kind-collection)
        (put-u8 buf cid)
        (put-hash buf (:root data))
        (put-u64 buf (:count data 0))
        (put-u64 buf (:size-bytes data 0))
        (.array buf))

      :else
      (throw (ex-info "unsupported node type for wire-v1" {:type t})))))

(defn decode-node-bytes
  "Decode node payload to store entry [type-name data]."
  [^bytes bs]
  (let [buf (wrap-bytes bs)
        kind (get-u8 buf)]
    (case kind
      0x00 ; scalar
      (let [tid (get-u8 buf)
            tname (or (type-id->name tid)
                      (throw (ex-info "unknown scalar type_id" {:id tid})))
            dlen (get-u8 buf)
            data-bs (get-bytes buf dlen)]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in scalar node" {})))
        ;; Reconstruct typed value from canonical bytes via types when possible
        (case tname
          "null" ["null" nil]
          "bool" ["bool" (not (zero? (aget data-bs 0)))]
          "i64" ["i64" (get-i64 (wrap-bytes data-bs))]
          "f64" ["f64" (.getDouble (wrap-bytes data-bs))]
          "char" ["char" (first (seq (utf8-str data-bs)))]
          (throw (ex-info "scalar type decode not implemented" {:type tname}))))

      0x01 ; ft
      (let [sub (get-u8 buf)
            _ (when (= sub 1)
                (throw (ex-info "ft/single reserved" {:subtype sub})))
            tname (or (ft-subtype->name sub)
                      (throw (ex-info "unknown ft subtype" {:sub sub})))
            m (get-measure buf)
            n (get-u8 buf)
            children (mapv (fn [_] (get-hash buf)) (range n))]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in ft node" {})))
        (case tname
          "ft/empty" [tname {:measure m}]
          "ft/deep" [tname {:left (nth children 0)
                            :spine (nth children 1)
                            :right (nth children 2)
                            :measure m}]
          [tname {:children children :measure m}]))

      0x02 ; hamt
      (let [sub (get-u8 buf)
            tname (or (hamt-subtype->name sub)
                      (throw (ex-info "unknown hamt subtype" {:sub sub})))
            m (get-measure buf)]
        (case tname
          "hamt/empty"
          (do (when (pos? (remaining buf))
                (throw (ex-info "trailing garbage" {})))
              [tname {:measure m}])

          "hamt/entry"
          (let [kh (get-hash buf) kr (get-hash buf) vr (get-hash buf)]
            (when (pos? (remaining buf))
              (throw (ex-info "trailing garbage" {})))
            [tname {:measure m :key-hash kh :key-ref kr :val-ref vr}])

          "hamt/bitmap"
          (let [bitmap (get-u32 buf)
                n (get-u8 buf)
                ch (mapv (fn [_] (get-hash buf)) (range n))]
            (when (pos? (remaining buf))
              (throw (ex-info "trailing garbage" {})))
            [tname {:measure m :bitmap bitmap :children ch}])))

      0x03 ; collection
      (let [cid (get-u8 buf)
            tname (or (coll-id->name cid)
                      (throw (ex-info "unknown coll_id" {:id cid})))
            root (get-hash buf)
            cnt (get-u64 buf)
            sz (get-u64 buf)]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in collection node" {})))
        [tname {:root root :count cnt :size-bytes sz}])

      (throw (ex-info "unknown node kind" {:kind kind})))))

;; =============================================================================
;; Chunk message
;; =============================================================================

(defn encode-item
  "Encode one logical item map to bytes (without outer chunk header).

   Item map:
     {:enc :node|:literal
      :hash hash-vector
      :entry [type data]}           ; when :node
      :literal {:type :body}}       ; when :literal
   "
  [{:keys [enc hash entry literal]}]
  (let [payload (case enc
                  :node (encode-node-bytes entry)
                  :literal (encode-lit-bytes literal)
                  (throw (ex-info "enc must be :node or :literal" {:enc enc})))
        enc-b (case enc :node enc-node :literal enc-literal)
        buf (bb (+ 1 32 4 (alength payload)))]
    (put-u8 buf enc-b)
    (put-hash buf hash)
    (put-u32 buf (alength payload))
    (put-bytes buf payload)
    (.array buf)))

(defn encode-chunk
  "Encode a chunk message map to binary bytes.

   {:budget n
    :items [{:enc :node|:literal :hash h :entry e | :literal form} …]}"
  [{:keys [budget items] :or {budget 0}}]
  (let [item-bs (mapv encode-item items)
        body-len (reduce + 0 (map alength item-bs))
        ;; magic4 + ver1 + msg1 + flags1 + n4 + budget4 + items
        buf (bb (+ 4 1 1 1 4 4 body-len))]
    (put-bytes buf (magic-bytes))
    (put-u8 buf version)
    (put-u8 buf msg-type-chunk)
    (put-u8 buf 0)
    (put-u32 buf (count items))
    (put-u32 buf (long (or budget 0)))
    (doseq [ib item-bs] (put-bytes buf ib))
    (.array buf)))

(defn decode-item
  "Decode one Item from buffer."
  [^ByteBuffer buf]
  (ensure-remaining buf (+ 1 32 4))
  (let [enc-b (get-u8 buf)
        h (get-hash buf)
        plen (int (get-u32 buf))
        payload (get-bytes buf plen)
        enc (case enc-b
              0x00 :node
              0x01 :literal
              (throw (ex-info "unknown item enc" {:enc enc-b})))]
    (case enc
      :node {:enc :node :hash h :entry (decode-node-bytes payload)}
      :literal {:enc :literal :hash h :literal (decode-lit-bytes payload)})))

(defn decode-chunk
  "Decode full ChunkMessage bytes → map."
  [^bytes bs]
  (let [buf (wrap-bytes bs)]
    (ensure-remaining buf 12)
    (let [mag (get-bytes buf 4)]
      (when-not (java.util.Arrays/equals mag (magic-bytes))
        (throw (ex-info "bad magic" {:got (bytes->hex mag)}))))
    (let [ver (get-u8 buf)
          mtype (get-u8 buf)
          flags (get-u8 buf)
          n (int (get-u32 buf))
          budget (get-u32 buf)]
      (when-not (= ver version)
        (throw (ex-info "unsupported wire version" {:version ver})))
      (when-not (= mtype msg-type-chunk)
        (throw (ex-info "unsupported msg_type" {:msg-type mtype})))
      (when-not (zero? flags)
        (throw (ex-info "nonzero flags reserved in v1" {:flags flags})))
      (let [items (mapv (fn [_] (decode-item buf)) (range n))]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage after chunk" {:remaining (remaining buf)})))
        {:version ver
         :msg-type :chunk
         :flags flags
         :budget budget
         :items items}))))

(defn encode-chunk-hex
  "encode-chunk → lowercase hex string."
  [chunk-map]
  (bytes->hex (encode-chunk chunk-map)))

(defn decode-chunk-hex
  "hex string → decode-chunk."
  [hex]
  (decode-chunk (hex->bytes hex)))

;; =============================================================================
;; Apply to store
;; =============================================================================

(defn apply-chunk-message!
  "Apply a decoded chunk map to store. Verifies literal hashes when
   pack/*verify-literal-hash* is true.

   Returns {:applied n :created [hex…] :exists [hex…]}."
  [st {:keys [items]}]
  (let [created (atom [])
        exists (atom [])]
    (doseq [{:keys [enc hash entry literal]} items]
      (let [hex (store/hash->hex hash)
            had? (store/s-has? st hash)]
        (case enc
          :node
          (do (store/s-put st hash entry)
              (if had?
                (swap! exists conj hex)
                (swap! created conj hex)))

          :literal
          (if had?
            (swap! exists conj hex)
            (let [got (pack/materialize-literal! st (:type literal) (:body literal))]
              (when (and pack/*verify-literal-hash* (not= got hash))
                (throw (ex-info "literal hash mismatch"
                                {:expected hex
                                 :got (store/hash->hex got)})))
              (swap! created conj hex))))))
    {:applied (count items)
     :created @created
     :exists @exists}))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn fixture-root
  "Absolute path to fixtures/wire-v1 (repo root), independent of cwd."
  []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (or (loop [dir cwd]
          (when dir
            (let [f (io/file dir "fixtures" "wire-v1" "cases")]
              (if (.isDirectory f)
                (.getCanonicalPath (io/file dir "fixtures" "wire-v1"))
                (recur (.getParentFile dir))))))
        (throw (ex-info "fixtures/wire-v1 not found walking parents of cwd"
                        {:cwd (.getCanonicalPath cwd)})))))

(defn load-fixture
  "Load a case dir: returns {:description :message-bytes :hash-hex}."
  [case-id]
  (let [root (io/file (fixture-root) "cases" case-id)
        msg-hex (str/trim (slurp (io/file root "message.hex")))
        hash-file (io/file root "hash.hex")
        desc-file (io/file root "description.json")]
    {:id case-id
     :description (when (.exists desc-file) (slurp desc-file))
     :message-hex msg-hex
     :message-bytes (hex->bytes msg-hex)
     :hash-hex (when (.exists hash-file) (str/trim (slurp hash-file)))}))

(defn list-fixture-ids
  "Case directory names under fixtures/wire-v1/cases."
  []
  (let [cases (io/file (fixture-root) "cases")]
    (->> (.listFiles cases)
         (filter #(.isDirectory %))
         (map #(.getName %))
         sort
         vec)))

;; =============================================================================
;; Pack EDN chunk bridge (leaf-chunking EDN maps ↔ wire-v1)
;; =============================================================================

(def content-type-chunk-v1
  "application/vnd.dacite.chunk.v1")

(defn binary-content-type?
  "True if Content-Type / Accept prefers wire-v1 binary chunks."
  [ct]
  (when ct
    (let [s (str/lower-case (str ct))]
      (or (str/includes? s "vnd.dacite.chunk")
          (str/includes? s "application/octet-stream")))))

(defn edn-content-type?
  [ct]
  (when ct
    (str/includes? (str/lower-case (str ct)) "edn")))

(defn pack-item->wire-item
  "Convert pack Layer-1 EDN item to wire encode-item map."
  [item]
  (let [h (store/hex->hash (:hash item))
        enc (keyword (:encoding item))]
    (case enc
      :node {:enc :node :hash h :entry (:body item)}
      :literal {:enc :literal
                :hash h
                :literal {:type (str (:type item)) :body (:body item)}}
      (throw (ex-info "unknown pack item encoding" {:encoding enc})))))

(defn wire-item->pack-item
  "Convert wire item map to pack Layer-1 EDN item."
  [{:keys [enc hash entry literal]}]
  (case enc
    :node {:encoding :node
           :hash (store/hash->hex hash)
           :body entry}
    :literal {:encoding :literal
              :hash (store/hash->hex hash)
              :type (:type literal)
              :body (:body literal)}
    (throw (ex-info "unknown wire enc" {:enc enc}))))

(defn pack-edn->wire-map
  "EDN pack chunk map → wire encode-chunk map."
  [chunk]
  {:budget (long (or (:budget chunk) 0))
   :items (mapv pack-item->wire-item (:items chunk))})

(defn wire-map->pack-edn
  "Wire decode-chunk map → EDN pack chunk map (for pack/apply-chunk!)."
  [{:keys [budget items]}]
  {:dacite.wire/chunk-v1 true
   :budget (long (or budget 0))
   :items (mapv wire-item->pack-item items)})

(defn encode-pack-edn
  "Serialize an EDN pack chunk to wire-v1 binary bytes."
  [chunk]
  (encode-chunk (pack-edn->wire-map chunk)))

(defn decode-pack-edn
  "Parse wire-v1 binary bytes to an EDN pack chunk map."
  [^bytes bs]
  (wire-map->pack-edn (decode-chunk bs)))

(defn wants-binary?
  "True if Accept header prefers binary chunks over EDN."
  [accept]
  (boolean
   (when accept
     (let [a (str/lower-case (str accept))]
       (and (str/includes? a "vnd.dacite.chunk")
            ;; if both listed, prefer the one that appears first
            (let [bi (or (str/index-of a "vnd.dacite.chunk") 9999)
                  ei (or (str/index-of a "application/edn") 9999)]
              (<= bi ei)))))))
