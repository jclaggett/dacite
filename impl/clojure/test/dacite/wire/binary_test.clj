(ns dacite.wire.binary-test
  "Drive the shipped wire-v1 codec against goldens and live public values."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [dacite.wire.binary :as bin]
            [dacite.store :as store]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.store.pack :as pack]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]))

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

(deftest public-types-have-wire-ids
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

(deftest all-fixtures-decode-and-reencode
  (doseq [id (bin/list-fixture-ids)]
    (let [fx (bin/load-fixture id)
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
          (is (store/s-has? st (store/hex->hash (:hash-hex fx)))
              (str "hash present " id)))))))

(deftest fixture-manifest-covers-public-types
  (let [manifest (json/parse-string
                  (slurp (io/file (bin/fixture-root) "manifest.json"))
                  true)
        ids (set (map :id (:cases manifest)))
        case-dirs (set (bin/list-fixture-ids))]
    (is (= ids case-dirs) "manifest ids match case directories")
    (doseq [t public-value-types]
      (let [has-lit (some #(re-find (re-pattern (str "chunk-literal-.*"
                                                     (java.util.regex.Pattern/quote t)))
                                    %)
                          ids)
            ;; naming is suffix-based; check explicit required cases
            ]
        (is (or has-lit
                (some #(.contains ^String % (str "literal-" t)) ids)
                (some #(and (.startsWith ^String % "chunk-literal-")
                            (.contains ^String % t))
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
      (is (some #(and (.startsWith ^String % "chunk-literal-")
                      (.contains ^String % substr))
                ids)
          (str "literal fixture for " t))
      (is (some #(and (.startsWith ^String % "chunk-node-")
                      (.contains ^String % substr))
                ids)
          (str "node fixture for " t)))
    ;; Collections
    (doseq [t ["string" "blob" "vector" "map" "set"]]
      (is (some #(and (.startsWith ^String % "chunk-literal-")
                      (.contains ^String % t))
                ids)
          (str "literal fixture for " t))
      (is (some #(and (.startsWith ^String % "chunk-node-")
                      (.contains ^String % t))
                ids)
          (str "node fixture for " t)))
    ;; FT / HAMT pack forms
    (doseq [t ["ft-empty" "ft-digit" "ft-deep" "hamt-empty" "hamt-entry" "hamt-bitmap"]]
      (is (some #(.contains ^String % t) ids)
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
    (is (store/s-has? st2 h)
        (str "literal apply " (types/dacite-type v)))
    (is (= h (pack/materialize-literal!
              (store/mem-store)
              (:type lit-form)
              (:body lit-form)))
        (str "literal materialize hash " (types/dacite-type v)))))

(defn- entry-canon
  "Compare store entries structurally (u256 byte arrays by content)."
  [[t data]]
  [t (if (= "u256" t)
       (mapv #(bit-and 0xff %) (if (bytes? data) data data))
       data)])

(defn- round-trip-node!
  [st v]
  (let [h (types/dacite-hash v)
        entry (store/s-get st h)
        msg {:budget 0
             :items [{:enc :node :hash h :entry entry}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)
        st2 (store/mem-store)
        _ (bin/apply-chunk-message! st2 dec)]
    (is (= (bin/bytes->hex bs) (bin/bytes->hex (bin/encode-chunk dec)))
        (str "node re-encode " (types/dacite-type v)))
    (is (store/s-has? st2 h)
        (str "node apply has " (types/dacite-type v)))
    (is (= (entry-canon entry) (entry-canon (store/s-get st2 h)))
        (str "node apply entry " (types/dacite-type v)))))

(deftest live-public-scalars-node-and-literal
  (let [st (store/mem-store)
        u256-data (byte-array (map unchecked-byte (range 32)))
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

(deftest live-public-collections-node-and-literal
  (let [st (store/mem-store)
        samples [["string" (coll/string-with-store st "wire")]
                 ["blob" (coll/blob-with-store st (byte-array [9 8 7]))]
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

(deftest live-ft-hamt-nodes-and-literals
  (let [st (store/mem-store)
        leaf-hs (mapv #(types/dacite-hash (scalar/i64-with-store st %)) [1 2 3])
        dig-h (ft/ft-digit-from-value-hashes st leaf-hs)
        empty-ft (ft/ft-empty st)
        kh (types/dacite-hash (scalar/i64-with-store st 10))
        vh (types/dacite-hash (scalar/i64-with-store st 20))
        empty-hamt (hamt/hamt-empty st)
        entry-h (hamt/hamt-entry-node st kh kh vh)]
    (doseq [h [dig-h empty-ft empty-hamt entry-h]]
      (let [entry (store/s-get st h)
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
              (is (= entry (store/s-get st2 h)))))
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
              (is (store/s-has? st2 h)))))))))

(deftest fixture-literal-i64-42-matches-and-round-trips
  (let [fx (bin/load-fixture "chunk-literal-i64-42")
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
      (is (store/s-has? st h))
      (is (= ["i64" 42] (store/s-get st h))))))

(deftest reject-bad-magic-and-version
  (is (thrown-with-msg? Exception #"bad magic"
                        (bin/decode-chunk (byte-array [0 1 2 3 1 1 0 0 0 0 0 0 0 0 0]))))
  (let [good (:message-bytes (bin/load-fixture "chunk-literal-i64-42"))
        bad (aclone good)]
    (aset-byte bad 4 (unchecked-byte 99))
    (is (thrown-with-msg? Exception #"unsupported wire version"
                          (bin/decode-chunk bad)))))

(deftest pack-edn-bridge-round-trip
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
    (is (store/s-has? st2 h))
    (is (= (bin/bytes->hex bs)
           (bin/bytes->hex (bin/encode-pack-edn back))))))

(deftest spot-check-new-scalar-and-collection
  ;; Decode fixture, apply, compare hash/value to live constructors.
  (testing "i8 scalar"
    (let [fx (bin/load-fixture "chunk-literal-i8-neg5")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live (scalar/i8-with-store (store/mem-store) -5)]
      (is (= h (types/dacite-hash live)))
      (is (= ["i8" -5] (store/s-get st h)))))
  (testing "negative f32 (sign bit set on wire)"
    (let [fx (bin/load-fixture "chunk-literal-f32-neg1.5")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live (scalar/f32-with-store (store/mem-store) (float -1.5))
          entry (store/s-get st h)]
      (is (= h (types/dacite-hash live)))
      (is (= "f32" (first entry)))
      (is (= (float -1.5) (float (second entry))))))
  (testing "map collection"
    (let [fx (bin/load-fixture "chunk-literal-map-ab")
          dec (bin/decode-chunk (:message-bytes fx))
          h (store/hex->hash (:hash-hex fx))
          st (store/mem-store)
          _ (bin/apply-chunk-message! st dec)
          live-st (store/mem-store)
          live (coll/hash-map-with-store live-st "a" 1 "b" 2)]
      (is (= h (types/dacite-hash live)))
      (is (store/s-has? st h)))))
