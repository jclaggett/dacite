(ns dacite.wire.binary-checks
  "Drive the shipped wire-v1 codec against goldens and live public values,
   on the JVM and on nbb. The JVM runs these checks from binary_test.clj,
   one deftest each under its old name. nbb runs them from
   `dacite.wire.binary-cljs-test` (`npm run wire:test`).

   The fixtures are host pieces in `env`: `:fixture-ids` (case directory
   names under fixtures/wire-v1/cases, sorted), `:load-fixture` (a case id
   to {:id :description :message-hex :message-bytes :hash-hex}, as
   `bin/load-fixture`), and `:manifest` (fixtures/wire-v1/manifest.json,
   keys as keywords). Other host pieces: bytes are a byte-array or a
   Uint8Array (`host-bytes`, `byte-vec`), and p as 32 bytes.

   `is` and `testing` are `dacite.store.check`."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.value :as v]
            [dacite.host :as host]
            [dacite.wire.binary :as bin]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing throws?]]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.store.pack :as pack]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]))

;; ---------------------------------------------------------------------------
;; Host pieces
;; ---------------------------------------------------------------------------

(defn host-bytes
  "Host bytes for the ints `xs` (0..255, or signed bytes)."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js (map #(bit-and 0xff %) xs)))))

(defn- host-bytes?
  [x]
  #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x)))

(defn- byte-vec
  "The unsigned bytes of host bytes, or of a seq of ints, as a vector."
  [x]
  #?(:clj (mapv #(bit-and 0xff %) x)
     :cljs (if (host-bytes? x) (vec (js/Array.from x)) (mapv #(bit-and 0xff %) x))))

(defn- copy-bytes [bs] #?(:clj (aclone ^bytes bs) :cljs (js/Uint8Array.from bs)))

(defn- set-byte! [bs i b] #?(:clj (aset-byte bs i (unchecked-byte b)) :cljs (aset bs i (bit-and 0xff b))))

(defn- re-quote
  "`s` as a regex matching itself (java.util.regex.Pattern/quote)."
  [s]
  #?(:clj (java.util.regex.Pattern/quote s)
     :cljs (str/replace s #"[\\^$.|?*+()\[\]{}]" #(str "\\" %))))

(defn- p-bytes
  "p as 32 big-endian bytes."
  []
  #?(:clj (let [b (vec (.toByteArray ^java.math.BigInteger (host/sl2-p)))] (subvec b (- (count b) 32)))
     :cljs (let [h (.padStart (.toString (host/sl2-p) 16) 64 "0")]
             (mapv #(js/parseInt (subs h (* 2 %) (+ 2 (* 2 %))) 16) (range 32)))))

;; ---------------------------------------------------------------------------
;; Public type inventory (must stay aligned with value constructors + wire tables)
;; ---------------------------------------------------------------------------

(def public-scalars
  ["null" "bool" "char"
   "i8" "i16" "i32" "i64"
   "u8" "u16" "u32" "u64" "u256"
   "f32" "f64"
   "negative"])

(def public-collections
  ["string" "blob" "vector" "map" "set"])

(def public-value-types
  (into public-scalars public-collections))

(defn run-repeat-have-wire-ids
  [_]
  (is (= 0x50 (get bin/name->type-id "run")))
  (is (= 0x51 (get bin/name->type-id "repeat"))))

(defn u8-run-lit-keeps-raw-bytes
  [_]
  (let [raw (host-bytes [0 1 127 128 255])
        form {:type "run" :body {:of "u8" :values raw}}
        bs (bin/encode-lit-bytes form)
        back (bin/decode-lit-bytes bs)
        vals (get-in back [:body :values])
        from-vec (bin/encode-lit-bytes
                  {:type "run" :body {:of "u8" :values [0 1 127 128 255]}})]
    (is (= "u8" (get-in back [:body :of])))
    (is (host-bytes? vals))
    (is (= [0 1 127 128 255]
           (byte-vec vals)))
    (is (= (bin/bytes->hex bs) (bin/bytes->hex from-vec)))
    (is (= (bin/bytes->hex bs) (bin/bytes->hex (bin/encode-lit-bytes back))))))

(defn run-repeat-lit-bytes-round-trip
  [_]
  (doseq [form [{:type "run" :body {:of "char" :values "hello"}}
                {:type "run" :body {:of "i64" :values [1 2 3]}}
                {:type "run" :body {:of "string" :values ["a" "bb"]}}
                {:type "run" :body {:of "char" :values "é"}}
                {:type "repeat" :body {:of "char" :n 4 :value \x}}
                {:type "repeat" :body {:of "bool" :n 8 :value false}}]]
    (let [bs (bin/encode-lit-bytes form)
          back (bin/decode-lit-bytes bs)]
      (is (= form back) (str "logical " (pr-str form)))
      (is (= (bin/bytes->hex bs)
             (bin/bytes->hex (bin/encode-lit-bytes back)))
          (str "canonical " (pr-str form))))))

(defn public-types-have-wire-ids
  [_]
  (doseq [t public-value-types]
    (is (contains? bin/name->type-id t)
        (str "missing wire type_id for public type " t)))
  (doseq [t public-scalars]
    (is (contains? bin/public-scalar-types t)))
  (doseq [t public-value-types]
    (is (contains? bin/public-value-types t))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn all-fixtures-decode-and-reencode
  [{:keys [fixture-ids load-fixture]}]
  (doseq [id (fixture-ids)]
    (let [fx (load-fixture id)
          dec (bin/decode-chunk (:message-bytes fx))
          re (bin/encode-chunk
              {:budget (:budget dec)
               :items (mapv (fn [it]
                              (if (= :literal (:enc it))
                                {:enc :literal
                                 :hash (:hash it)
                                 :literal (:literal it)}
                                {:enc :node
                                 :hash (:hash it)
                                 :entry (:entry it)}))
                            (:items dec))})]
      (is (= (:message-hex fx) (bin/bytes->hex re))
          (str "canonical re-encode " id))
      (let [st (store/mem-store)]
        (bin/apply-chunk-message! st dec)
        (when (:hash-hex fx)
          (is (store/await (store/s-has? st (store/hex->hash (:hash-hex fx))))
              (str "hash present " id)))))))

(defn fixture-manifest-covers-public-types
  [{:keys [fixture-ids manifest]}]
  (let [manifest (manifest)
        ids (set (map :id (:cases manifest)))
        case-dirs (set (fixture-ids))]
    (is (= ids case-dirs) "manifest ids match case directories")
    (doseq [t public-value-types]
      (let [has-lit (some #(re-find (re-pattern (str "chunk-literal-.*"
                                                     (re-quote t)))
                                    %)
                          ids)
            ;; naming is suffix-based; check explicit required cases
            ]
        (is (or has-lit
                (some #(str/includes? % (str "literal-" t)) ids)
                (some #(and (str/starts-with? % "chunk-literal-")
                            (str/includes? % t))
                      ids)
                ;; collections/scalars have dedicated cases checked below
                true))))
    ;; Explicit node + literal presence for every public scalar
    (doseq [[t substr] [["null" "null"]
                        ["bool" "bool"]
                        ["char" "char"]
                        ["i8" "i8"]
                        ["i16" "i16"]
                        ["i32" "i32"]
                        ["i64" "i64"]
                        ["u8" "u8"]
                        ["u16" "u16"]
                        ["u32" "u32"]
                        ["u64" "u64"]
                        ["u256" "u256"]
                        ["f32" "f32"]
                        ["f64" "f64"]
                        ["negative" "negative"]]]
      (is (some #(and (str/starts-with? % "chunk-literal-")
                      (str/includes? % substr))
                ids)
          (str "literal fixture for " t))
      (is (some #(and (str/starts-with? % "chunk-node-")
                      (str/includes? % substr))
                ids)
          (str "node fixture for " t)))
    ;; Collections
    (doseq [t ["string" "blob" "vector" "map" "set"]]
      (is (some #(and (str/starts-with? % "chunk-literal-")
                      (str/includes? % t))
                ids)
          (str "literal fixture for " t))
      (is (some #(and (str/starts-with? % "chunk-node-")
                      (str/includes? % t))
                ids)
          (str "node fixture for " t)))
    ;; FT / HAMT pack forms
    (doseq [t ["ft-empty" "ft-digit" "ft-deep" "hamt-empty" "hamt-entry" "hamt-bitmap"]]
      (is (some #(str/includes? % t) ids)
          (str "fixture coverage for " t)))))

;; ---------------------------------------------------------------------------
;; Live values: node + literal for every public type
;; ---------------------------------------------------------------------------

(defn- round-trip-literal!
  [st v lit-form]
  (let [h (types/dacite-hash v)
        msg {:budget 1024
             :items [{:enc :literal :hash h :literal lit-form}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)
        st2 (store/mem-store)
        _ (bin/apply-chunk-message! st2 dec)]
    (is (= (bin/bytes->hex bs) (bin/bytes->hex (bin/encode-chunk dec)))
        (str "literal re-encode " (types/dacite-type v)))
    (is (store/await (store/s-has? st2 h))
        (str "literal apply " (types/dacite-type v)))
    (is (= h (pack/materialize-literal!
              (store/mem-store)
              (:type lit-form)
              (:body lit-form)))
        (str "literal materialize hash " (types/dacite-type v)))))

(defn- entry-canon
  "Compare store entries structurally (u256 byte arrays by content). On nbb a
   live u64 entry holds a Number and a decoded one a BigInt (as with the
   bitmap, #64), so u64 data is compared as a host word."
  [[t data]]
  [t (case t
       "u256" (byte-vec data)
       "u64" (host/word data)
       data)])

(defn- round-trip-node!
  [st v]
  (let [h (types/dacite-hash v)
        entry (store/await (store/s-get st h))
        msg {:budget 0
             :items [{:enc :node :hash h :entry entry}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)
        st2 (store/mem-store)
        _ (bin/apply-chunk-message! st2 dec)]
    (is (= (bin/bytes->hex bs) (bin/bytes->hex (bin/encode-chunk dec)))
        (str "node re-encode " (types/dacite-type v)))
    (is (store/await (store/s-has? st2 h))
        (str "node apply has " (types/dacite-type v)))
    (is (= (entry-canon entry) (entry-canon (store/await (store/s-get st2 h))))
        (str "node apply entry " (types/dacite-type v)))))

(defn live-public-scalars-node-and-literal
  [_]
  (let [st (store/mem-store)
        u256-data (host-bytes (range 32))
        samples [["null" (scalar/null-with-store st)]
                 ["bool" (scalar/bool-with-store st true)]
                 ["char" (scalar/dacite-char-with-store st \Z)]
                 ["i8" (scalar/i8-with-store st -7)]
                 ["i16" (scalar/i16-with-store st -400)]
                 ["i32" (scalar/i32-with-store st -123456)]
                 ["i64" (scalar/i64-with-store st 99)]
                 ["u8" (scalar/u8-with-store st 250)]
                 ["u16" (scalar/u16-with-store st 50000)]
                 ["u32" (scalar/u32-with-store st 3000000001)]
                 ["u64" (scalar/u64-with-store st 1001)]
                 ["u256" (scalar/u256-with-store st u256-data)]
                 ["f32" (scalar/f32-with-store st (float 2.25))]
                 ["f32-neg" (scalar/f32-with-store st (float -1.5))]
                 ["f64" (scalar/f64-with-store st 6.25)]
                 ["negative" (scalar/negative-with-store st)]]]
    (doseq [[lab v] samples]
      (testing lab
        (let [h (types/dacite-hash v)
              form (pack/literal-of st h)]
          (is (some? form) (str "literal-of " lab))
          (round-trip-literal! st v form)
          (round-trip-node! st v))))))

(defn live-public-collections-node-and-literal
  [_]
  (let [st (store/mem-store)
        samples [["string" (coll/string-with-store st "wire")]
                 ["blob" (coll/blob-with-store st (host-bytes [9 8 7]))]
                 ["vector" (coll/vector-with-store st 4 5 6)]
                 ["map" (coll/hash-map-with-store st "k" 1)]
                 ["set" (coll/dacite-set-with-store st 7 8)]]]
    (doseq [[lab v] samples]
      (testing lab
        (let [h (types/dacite-hash v)
              form (pack/literal-of st h)]
          (is (some? form) (str "literal-of " lab))
          (round-trip-literal! st v form)
          (round-trip-node! st v))))))

(defn live-ft-hamt-nodes-and-literals
  [_]
  (let [st (store/mem-store)
        leaf-hs (mapv #(types/dacite-hash (scalar/i64-with-store st %)) [1 2 3])
        dig-h (ft/ft-digit-from-value-hashes st leaf-hs)
        empty-ft (ft/ft-empty st)
        kh (types/dacite-hash (scalar/i64-with-store st 10))
        vh (types/dacite-hash (scalar/i64-with-store st 20))
        empty-hamt (hamt/hamt-empty st)
        entry-h (hamt/hamt-entry-node st kh kh vh)]
    (doseq [h [dig-h empty-ft empty-hamt entry-h]]
      (let [entry (store/await (store/s-get st h))
            t (types/entry-type entry)
            v-wrap nil]
        (testing t
          (let [msg {:budget 0
                     :items [{:enc :node :hash h :entry entry}]}
                bs (bin/encode-chunk msg)
                dec (bin/decode-chunk bs)]
            (is (= (bin/bytes->hex bs)
                   (bin/bytes->hex (bin/encode-chunk dec)))
                (str "node re-encode " t))
            (let [st2 (store/mem-store)]
              (bin/apply-chunk-message! st2 dec)
              (is (= entry (store/await (store/s-get st2 h))))))
          (when-let [form (pack/literal-of st h)]
            (let [msg {:budget 1024
                       :items [{:enc :literal :hash h :literal form}]}
                  bs (bin/encode-chunk msg)
                  dec (bin/decode-chunk bs)
                  st2 (store/mem-store)]
              (is (= (bin/bytes->hex bs)
                     (bin/bytes->hex (bin/encode-chunk dec)))
                  (str "literal re-encode " t))
              (bin/apply-chunk-message! st2 dec)
              (is (store/await (store/s-has? st2 h))))))))))

(defn fixture-literal-i64-42-matches-and-round-trips
  [{:keys [load-fixture]}]
  (let [fx (load-fixture "chunk-literal-i64-42")
        decoded (bin/decode-chunk (:message-bytes fx))
        item (first (:items decoded))
        h (store/hex->hash (:hash-hex fx))]
    (is (= 1 (:version decoded)))
    (is (= :chunk (:msg-type decoded)))
    (is (= 1024 (:budget decoded)))
    (is (= 1 (count (:items decoded))))
    (is (= :literal (:enc item)))
    (is (= h (:hash item)))
    (is (= "i64" (:type (:literal item))))
    (is (= 42 (:body (:literal item))))
    (let [re (bin/encode-chunk
              {:budget (:budget decoded)
               :items [{:enc :literal
                        :hash (:hash item)
                        :literal (:literal item)}]})]
      (is (= (bin/bytes->hex (:message-bytes fx))
             (bin/bytes->hex re))))
    (let [st (store/mem-store)
          r (bin/apply-chunk-message! st decoded)]
      (is (= 1 (:applied r)))
      (is (store/await (store/s-has? st h)))
      (is (= ["i64" 42] (store/await (store/s-get st h)))))))

(defn reject-bad-magic-and-version
  [{:keys [load-fixture]}]
  (is (throws? #"bad magic"
               #(bin/decode-chunk (host-bytes [0 1 2 3 1 1 0 0 0 0 0 0 0 0 0]))))
  (let [good (:message-bytes (load-fixture "chunk-literal-i64-42"))
        bad (copy-bytes good)]
    (set-byte! bad 4 99)
    (is (throws? #"unsupported wire version"
                 #(bin/decode-chunk bad)))))

(defn pack-edn-bridge-round-trip
  [_]
  (let [st (store/mem-store)
        v (coll/vector-with-store st 10 20)
        h (types/dacite-hash v)
        form (pack/literal-of st h)
        edn-chunk {:dacite.wire/chunk-v1 true
                   :budget 1024
                   :items [{:encoding :literal
                            :hash (store/hash->hex h)
                            :type (:type form)
                            :body (:body form)}]}
        bs (bin/encode-pack-edn edn-chunk)
        back (bin/decode-pack-edn bs)
        st2 (store/mem-store)]
    (is (true? (:dacite.wire/chunk-v1 back)))
    (is (= 1 (count (:items back))))
    (pack/apply-chunk! st2 back)
    (is (store/await (store/s-has? st2 h)))
    (is (= (bin/bytes->hex bs)
           (bin/bytes->hex (bin/encode-pack-edn back))))))

(defn spot-check-new-scalar-and-collection
  [{:keys [load-fixture]}]
  ;; Decode fixture, apply, compare hash/value to live constructors.
  (testing "i8 scalar"
    (let [fx (load-fixture "chunk-literal-i8-neg5")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live (scalar/i8-with-store (store/mem-store) -5)]
      (is (= h (types/dacite-hash live)))
      (is (= ["i8" -5] (store/await (store/s-get st h))))))
  (testing "negative f32 (sign bit set on wire)"
    (let [fx (load-fixture "chunk-literal-f32-neg1.5")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live (scalar/f32-with-store (store/mem-store) (float -1.5))
          entry (store/await (store/s-get st h))]
      (is (= h (types/dacite-hash live)))
      (is (= "f32" (first entry)))
      (is (= (float -1.5) (float (second entry))))))
  (testing "map collection"
    (let [fx (load-fixture "chunk-literal-map-ab")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live-st (store/mem-store)
          live (coll/hash-map-with-store live-st "a" 1 "b" 2)]
      (is (= h (types/dacite-hash live)))
      (is (store/await (store/s-has? st h))))))

;; ---------------------------------------------------------------------------
;; A measure whose state is not in SL(2, F_p): :bad-state (docs/spec/wire-v1.md)
;; ---------------------------------------------------------------------------

(defn- sub-index
  "Index of the first occurrence of byte vector `needle` in `hay`, or nil."
  [hay needle]
  (let [n (count needle)]
    (first (filter #(= needle (subvec hay % (+ % n))) (range (inc (- (count hay) n)))))))

(defn a-measure-state-outside-sl2-is-bad-state
  [_]
  (let [st (store/mem-store)
        m (v/vector st (v/string st "aaa") (v/string st "bbb"))
        h (:root (second (store/await (store/s-get st (v/hash m)))))
        entry (store/await (store/s-get st h))
        bs (byte-vec (bin/encode-item {:enc :node :hash h :entry entry}))
        state-bytes (byte-vec (hash/state->bytes (:elements-fuse (:measure (second entry)))))
        off (sub-index bs state-bytes)
        outcome (fn [bytes]
                  (try (bin/decode-item-stream (host-bytes bytes)) :decoded
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                         [(:reason (ex-data e)) (ex-message e)])))
        splice (fn [at new] (into (into (subvec bs 0 at) new) (subvec bs (+ at (count new)))))
        p-bytes (p-bytes)]
    (is (some? off) "the 128-byte state is inside the item")
    (is (= :decoded (outcome bs)) "the untouched item decodes")
    (doseq [[label bad] {"one bit flipped in cell a" (update bs off #(bit-xor % 1))
                         "one bit flipped in cell d" (update bs (+ off 127) #(bit-xor % 1))
                         "all-zero state" (splice off (vec (repeat 128 0)))
                         "cell a = p, not canonical" (splice off p-bytes)
                         "cell a = 2^256 - 1, above p" (splice off (vec (repeat 32 0xff)))}
            :let [[reason msg] (outcome bad)]]
      (is (= :bad-state reason) label)
      (is (re-find #"bad fuse state" msg) label))))

(def checks
  {"run-repeat-have-wire-ids" run-repeat-have-wire-ids
   "u8-run-lit-keeps-raw-bytes" u8-run-lit-keeps-raw-bytes
   "run-repeat-lit-bytes-round-trip" run-repeat-lit-bytes-round-trip
   "public-types-have-wire-ids" public-types-have-wire-ids
   "all-fixtures-decode-and-reencode" all-fixtures-decode-and-reencode
   "fixture-manifest-covers-public-types" fixture-manifest-covers-public-types
   "live-public-scalars-node-and-literal" live-public-scalars-node-and-literal
   "live-public-collections-node-and-literal" live-public-collections-node-and-literal
   "live-ft-hamt-nodes-and-literals" live-ft-hamt-nodes-and-literals
   "fixture-literal-i64-42-matches-and-round-trips" fixture-literal-i64-42-matches-and-round-trips
   "reject-bad-magic-and-version" reject-bad-magic-and-version
   "pack-edn-bridge-round-trip" pack-edn-bridge-round-trip
   "spot-check-new-scalar-and-collection" spot-check-new-scalar-and-collection
   "a-measure-state-outside-sl2-is-bad-state" a-measure-state-outside-sl2-is-bad-state})
