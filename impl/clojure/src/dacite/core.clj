(ns dacite.core
  "Dacite: Data citing with fused hashing.

   Ergonomic API for constructing and working with Dacite values.

   Scalars:
     (d/null)              => a null value
     (d/bool true)         => a boolean
     (d/i64 42)            => a 64-bit integer
     (d/f64 3.14)          => a 64-bit float
     (d/char \\a)           => a character
     (d/scalar :u8 255)    => any scalar by type name

   Strings:
     (d/string \"hello\")    => a string (typed seq of char scalars)

   Collections:
     (d/vector [1 2 3])         => typed vector of i64s (auto-coerced)
     (d/vector [:i64 1] [:i64 2]) => explicit typed values
     (d/dacite-map {\"name\" \"Alice\", \"age\" 30})

   Hashing:
     (d/dacite-hash val)    => 256-bit hash of any dacite value
     (d/dacite-hash-hex val) => hex string of hash

   Low-level access:
     (d/value-type val)     => type keyword
     (d/value-data val)     => raw data"
  (:refer-clojure :exclude [vector])
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.finger-tree :as ft]
            [dacite.hamt :as hamt]))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn null
  "Create a null value."
  []
  [:null nil])

(defn bool
  "Create a boolean value."
  [b]
  {:pre [(instance? Boolean b)]}
  [:bool b])

(defn i8 "Create a signed 8-bit integer." [n] [:i8 (byte n)])
(defn i16 "Create a signed 16-bit integer." [n] [:i16 (short n)])
(defn i32 "Create a signed 32-bit integer." [n] [:i32 (int n)])
(defn i64 "Create a signed 64-bit integer." [n] [:i64 (long n)])

(defn u8 "Create an unsigned 8-bit integer (0-255)." [n]
  {:pre [(<= 0 n 255)]}
  [:u8 n])
(defn u16 "Create an unsigned 16-bit integer." [n]
  {:pre [(<= 0 n 65535)]}
  [:u16 n])
(defn u32 "Create an unsigned 32-bit integer." [n]
  {:pre [(<= 0 n 4294967295)]}
  [:u32 n])
(defn u64 "Create an unsigned 64-bit integer." [n]
  {:pre [(<= 0 n)]}
  [:u64 n])

(defn f32 "Create a 32-bit float." [n] [:f32 (float n)])
(defn f64 "Create a 64-bit float." [n] [:f64 (double n)])

(defn dacite-char
  "Create a character value."
  [c]
  {:pre [(char? c)]}
  [:char c])

(defn scalar
  "Create a scalar value of any type.
   (scalar :i64 42) is equivalent to (i64 42)."
  [type-kw data]
  [type-kw data])

;; =============================================================================
;; Value accessors
;; =============================================================================

(defn value-type
  "Get the type keyword of a dacite value."
  [v]
  (types/dacite-type v))

(defn value-data
  "Get the raw data of a dacite value."
  [v]
  (types/dacite-data v))

;; =============================================================================
;; Hashing
;; =============================================================================

(defn value-hash
  "Compute the 256-bit hash of a dacite value.
   Returns [long, long, long, long]."
  [v]
  (hash/typed-value-hash v))

(defn value-hash-hex
  "Compute the hash of a dacite value as a 64-char hex string."
  [v]
  (hash/hash->hex (value-hash v)))

;; =============================================================================
;; String construction
;; =============================================================================

(defn string
  "Create a dacite string value.

   A string is a typed value: seq(type-name, data) where data is a
   finger tree of char scalars. Returns a map with:
     :type    - :string
     :chars   - vector of [:char c] values
     :store   - the backing dacite-map
     :root    - root hash of the finger tree
     :hash    - semantic hash of the typed string value

   The string can be converted back via (string-value s)."
  [^String s]
  (let [chars (mapv #(dacite-char %) s)
        ;; Build finger tree of char scalars
        [store root] (ft/from-seq chars)
        ;; Semantic hash: the typed-value-hash using the string's content
        h (hash/typed-value-hash [:string s])]
    {:type :string
     :value s
     :chars chars
     :store store
     :root root
     :hash h}))

(defn string-value
  "Extract the string value from a dacite string."
  [ds]
  (:value ds))

(defn string-count
  "Get the character count of a dacite string (O(1))."
  [ds]
  (ft/tree-count [(:store ds) (:root ds)]))

;; =============================================================================
;; Vector construction
;; =============================================================================

(defn- coerce-element
  "Coerce a plain Clojure value to a dacite typed value if needed.
   Already-typed values (2-element vectors starting with a keyword) pass through."
  [x]
  (cond
    ;; Already a typed value [keyword, data]
    (and (vector? x) (= 2 (count x)) (keyword? (first x)))
    x

    ;; Auto-coerce common types
    (nil? x)             (null)
    (instance? Boolean x) (bool x)
    (integer? x)          (i64 x)
    (float? x)            (f64 (double x))
    (double? x)           (f64 x)
    (char? x)             (dacite-char x)
    (string? x)           [:string x]

    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

(defn vector
  "Create a dacite vector from a sequence of values.

   Values can be:
   - Explicit typed values: [:i64 42], [:string \"hello\"]
   - Plain Clojure values (auto-coerced): 42 → [:i64 42], true → [:bool true]

   Returns a map with:
     :type     - :vector
     :elements - vector of typed values
     :store    - the backing dacite-map
     :root     - root hash of the finger tree
     :hash     - semantic hash of the typed vector value

   Examples:
     (vector [1 2 3])
     (vector [[:i64 1] [:string \"two\"] [:bool true]])"
  [elements]
  (let [typed-elems (mapv coerce-element elements)
        [store root] (ft/from-seq typed-elems)
        ;; The vector's semantic hash uses typed-value-hash
        ef (ft/tree-elements-fuse [store root])
        h (hash/node-hash :vector ef)]
    {:type :vector
     :elements typed-elems
     :store store
     :root root
     :hash h}))

(defn vector-count
  "Get the element count of a dacite vector (O(1))."
  [dv]
  (ft/tree-count [(:store dv) (:root dv)]))

(defn vector-elements
  "Get the elements of a dacite vector as a Clojure vector of typed values."
  [dv]
  (:elements dv))

(defn vector-nth
  "Get the nth element of a dacite vector.
   Note: O(n) for now; efficient random access requires finger tree split."
  [dv n]
  (nth (:elements dv) n))

(defn vector-conj
  "Append an element to a dacite vector. Returns a new vector."
  [dv elem]
  (let [typed-elem (coerce-element elem)
        new-elems (conj (:elements dv) typed-elem)
        [m vh] (ft/add-value (:store dv) typed-elem)
        [store root] (ft/conj-right [m (:root dv)] vh)
        ef (ft/tree-elements-fuse [store root])
        h (hash/node-hash :vector ef)]
    {:type :vector
     :elements new-elems
     :store store
     :root root
     :hash h}))

;; =============================================================================
;; Map construction
;; =============================================================================

(defn dacite-map
  "Create a dacite map from a Clojure map.

   Keys and values are auto-coerced to dacite typed values.
   String keys are common: {\"name\" \"Alice\", \"age\" 30}

   Returns a map with:
     :type    - :map
     :entries - the original entries as {coerced-key coerced-val}
     :store   - the backing dacite-map
     :root    - root hash of the HAMT
     :hash    - semantic hash of the typed map value

   Examples:
     (dacite-map {\"name\" \"Alice\" \"age\" 30})
     (dacite-map {[:string \"x\"] [:i64 1]})"
  [m]
  (let [coerced-entries (mapv (fn [[k v]]
                                [(coerce-element k) (coerce-element v)])
                              m)
        ;; Build HAMT
        [store root] (reduce
                      (fn [[store root] [k-val v-val]]
                        (let [[m1 k-ref] (hamt/add-value store k-val)
                              [m2 v-ref] (hamt/add-value m1 v-val)
                              k-hash (hash/typed-value-hash k-val)]
                          (hamt/assoc-val [m2 root] k-hash k-ref v-ref)))
                      (hamt/hamt)
                      coerced-entries)
        ef (hamt/hamt-elements-fuse [store root])
        h (hash/node-hash :map ef)]
    {:type :map
     :entries (into {} coerced-entries)
     :store store
     :root root
     :hash h}))

(defn map-count
  "Get the entry count of a dacite map (O(1))."
  [dm]
  (hamt/hamt-count [(:store dm) (:root dm)]))

(defn map-get
  "Look up a value by key in a dacite map.
   Key is auto-coerced. Returns the typed value or nil."
  [dm key]
  (let [k-val (coerce-element key)
        k-hash (hash/typed-value-hash k-val)
        val-ref (hamt/get-val [(:store dm) (:root dm)] k-hash)]
    (when val-ref
      (get (:store dm) val-ref))))

(defn map-assoc
  "Associate a key-value pair in a dacite map. Returns a new map."
  [dm key val]
  (let [k-val (coerce-element key)
        v-val (coerce-element val)
        [m1 k-ref] (hamt/add-value (:store dm) k-val)
        [m2 v-ref] (hamt/add-value m1 v-val)
        k-hash (hash/typed-value-hash k-val)
        [store root] (hamt/assoc-val [m2 (:root dm)] k-hash k-ref v-ref)
        ef (hamt/hamt-elements-fuse [store root])
        h (hash/node-hash :map ef)]
    {:type :map
     :entries (assoc (:entries dm) k-val v-val)
     :store store
     :root root
     :hash h}))

(defn map-dissoc
  "Remove a key from a dacite map. Returns a new map."
  [dm key]
  (let [k-val (coerce-element key)
        k-hash (hash/typed-value-hash k-val)
        [store root] (hamt/dissoc-val [(:store dm) (:root dm)] k-hash)
        ef (hamt/hamt-elements-fuse [store root])
        h (hash/node-hash :map ef)]
    {:type :map
     :entries (dissoc (:entries dm) k-val)
     :store store
     :root root
     :hash h}))

(defn map-entries
  "Get all entries as a sequence of [typed-key typed-val] pairs."
  [dm]
  (let [raw-entries (hamt/entries [(:store dm) (:root dm)])]
    (mapv (fn [[k-ref v-ref]]
            [(get (:store dm) k-ref)
             (get (:store dm) v-ref)])
          raw-entries)))

;; =============================================================================
;; Generic hash (works on any dacite value or collection)
;; =============================================================================

(defn dacite-hash
  "Get the hash of any dacite value or collection.
   - Scalar/typed values: returns typed-value-hash
   - Collections (string, vector, map): returns the semantic hash"
  [v]
  (if (map? v)
    (:hash v)
    (hash/typed-value-hash v)))

(defn dacite-hash-hex
  "Get the hash of any dacite value or collection as a hex string."
  [v]
  (hash/hash->hex (dacite-hash v)))

;; =============================================================================
;; Equality
;; =============================================================================

(defn dacite=
  "Content equality for dacite values. Two values are equal if they
   have the same hash (content-addressed identity)."
  [a b]
  (= (dacite-hash a) (dacite-hash b)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Scalars
  (null)                    ;; => [:null nil]
  (bool true)               ;; => [:bool true]
  (i64 42)                  ;; => [:i64 42]
  (f64 3.14)                ;; => [:f64 3.14]
  (dacite-char \a)          ;; => [:char \a]

  ;; Hashing
  (value-hash (i64 42))     ;; => [long long long long]
  (value-hash-hex (i64 42)) ;; => "a1b2c3..."

  ;; Strings
  (def s (string "hello"))
  (string-value s)          ;; => "hello"
  (string-count s)          ;; => 5

  ;; Vectors
  (def v (vector [1 2 3]))
  (vector-count v)          ;; => 3
  (vector-nth v 0)          ;; => [:i64 1]
  (def v2 (vector-conj v 4))
  (vector-count v2)         ;; => 4

  ;; Maps
  (def m (dacite-map {"name" "Alice" "age" 30}))
  (map-count m)             ;; => 2
  (map-get m "name")        ;; => [:string "Alice"]
  (map-get m "age")         ;; => [:i64 30]
  (def m2 (map-assoc m "email" "alice@example.com"))
  (map-count m2)            ;; => 3

  ;; Content equality
  (dacite= (i64 42) (i64 42))     ;; => true
  (dacite= (i64 42) (i64 43))     ;; => false
  (dacite= (vector [1 2]) (vector [1 2])))  ;; => true
