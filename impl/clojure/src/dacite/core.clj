(ns dacite.core
  "Dacite: Data citing with fused hashing.

   Ergonomic API for constructing and working with Dacite values.

   All constructors take a store (map) as the first argument and return
   [store' hash] — the updated store and the hash of the new value.

   Scalars:
     (d/null store)           => [store' hash]
     (d/bool store true)      => [store' hash]
     (d/i64 store 42)         => [store' hash]
     (d/string store \"hello\") => [store' hash]

   Collections (from refs already in store):
     (d/vector store [h1 h2 h3])
     (d/dacite-map store [[kh1 vh1] [kh2 vh2]])

   Convenience (auto-coerce and store):
     (d/vector-of store [1 2 3])
     (d/map-of store {\"name\" \"Alice\" \"age\" 30})

   Stateful convenience (REPL):
     (d/with-store [s {}]
       (let [a (d/i64! 42)
             b (d/string! \"hello\")]
         (d/vector! [a b])))"
  (:refer-clojure :exclude [vector vector-of])
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.finger-tree :as ft]
            [dacite.hamt :as hamt]))

;; =============================================================================
;; with-store macro
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current store atom when inside with-store."
  nil)

(defmacro with-store
  "Execute body with a managed store atom. Binds the symbol to the atom
   and sets *store* for bang constructors. Returns [final-store last-value].

   Usage:
     (with-store [s {}]
       (let [a (i64! 42)
             b (string! \"hello\")]
         (vector! [a b])))
     ;; => [final-store root-hash]"
  [[sym init] & body]
  `(let [~sym (atom ~init)]
     (binding [*store* ~sym]
       (let [result# (do ~@body)]
         [@~sym result#]))))

(defn- store!
  "Add a typed value to the dynamic *store*. Returns its hash."
  [value]
  (let [h (hash/typed-value-hash value)]
    (swap! *store* assoc h value)
    h))

;; =============================================================================
;; Scalar constructors (pure)
;; =============================================================================

(defn scalar "Create a scalar of any type. Returns [store' hash]."
  [store type-kw data]
  (let [v [type-kw data] h (hash/typed-value-hash v)]
    [(assoc store h v) h]))

(defn null
  "Create a null value. Returns [store' hash]."
  [store]
  (scalar store :null nil))

(defn bool
  "Create a boolean value. Returns [store' hash]."
  [store b]
  {:pre [(instance? Boolean b)]}
  (scalar store :bool b))

(defn i8 "Create a signed 8-bit integer. Returns [store' hash]."
  [store n]
  (scalar store :i8 n))

(defn i16 "Create a signed 16-bit integer. Returns [store' hash]."
  [store n]
  (scalar store :i16 n))

(defn i32 "Create a signed 32-bit integer. Returns [store' hash]."
  [store n]
  (scalar store :i32 n))

(defn i64 "Create a signed 64-bit integer. Returns [store' hash]."
  [store n]
  (scalar store :i64 n))

(defn u8 "Create an unsigned 8-bit integer. Returns [store' hash]."
  [store n]
  {:pre [(<= 0 n 255)]}
  (scalar store :u8 n))

(defn u16 "Create an unsigned 16-bit integer. Returns [store' hash]."
  [store n]
  {:pre [(<= 0 n 65535)]}
  (scalar store :u16 n))

(defn u32 "Create an unsigned 32-bit integer. Returns [store' hash]."
  [store n]
  {:pre [(<= 0 n 4294967295)]}
  (scalar store :u32 n))

(defn u64 "Create an unsigned 64-bit integer. Returns [store' hash]."
  [store n]
  {:pre [(<= 0 n)]}
  (scalar store :u64 n))

(defn u256 "Create an unsigned 256-bit integer (e.g. a hash as data). Returns [store' hash].
  Data should be a 32-byte array."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar store :u256 data))

(defn f32 "Create a 32-bit float. Returns [store' hash]."
  [store n]
  (scalar store :f32 (float n)))

(defn f64 "Create a 64-bit float. Returns [store' hash]."
  [store n]
  (scalar store :f64 (double n)))

(defn dacite-char "Create a character value. Returns [store' hash]."
  [store c]
  {:pre [(char? c)]}
  (scalar store :char c))

;; =============================================================================
;; Scalar constructors (bang — use with with-store)
;; =============================================================================

(defn null! "Create a null value in *store*. Returns hash." []
  (store! [:null nil]))

(defn bool! "Create a boolean in *store*. Returns hash." [b]
  {:pre [(instance? Boolean b)]}
  (store! [:bool b]))

(defn i8! "Create i8 in *store*. Returns hash." [n] (store! [:i8 (byte n)]))
(defn i16! "Create i16 in *store*. Returns hash." [n] (store! [:i16 (short n)]))
(defn i32! "Create i32 in *store*. Returns hash." [n] (store! [:i32 (int n)]))
(defn i64! "Create i64 in *store*. Returns hash." [n] (store! [:i64 (long n)]))

(defn u8! "Create u8 in *store*. Returns hash." [n]
  {:pre [(<= 0 n 255)]} (store! [:u8 n]))
(defn u16! "Create u16 in *store*. Returns hash." [n]
  {:pre [(<= 0 n 65535)]} (store! [:u16 n]))
(defn u32! "Create u32 in *store*. Returns hash." [n]
  {:pre [(<= 0 n 4294967295)]} (store! [:u32 n]))
(defn u64! "Create u64 in *store*. Returns hash." [n]
  {:pre [(<= 0 n)]} (store! [:u64 n]))

(defn u256! "Create u256 in *store*. Returns hash." [^bytes data]
  {:pre [(= 32 (alength data))]} (store! [:u256 data]))

(defn f32! "Create f32 in *store*. Returns hash." [n] (store! [:f32 (float n)]))
(defn f64! "Create f64 in *store*. Returns hash." [n] (store! [:f64 (double n)]))

(defn dacite-char! "Create char in *store*. Returns hash." [c]
  {:pre [(char? c)]} (store! [:char c]))

(defn scalar! "Create scalar of any type in *store*. Returns hash." [type-kw data]
  (store! [type-kw data]))

;; =============================================================================
;; Value accessors
;; =============================================================================

(defn value-type
  "Get the type keyword of a value in the store."
  [store hash]
  (types/dacite-type (get store hash)))

(defn value-data
  "Get the raw data of a value in the store."
  [store hash]
  (types/dacite-data (get store hash)))

(defn lookup
  "Look up a value by hash in the store. Returns [type-kw data] or nil."
  [store hash]
  (get store hash))

;; =============================================================================
;; Hashing utilities
;; =============================================================================

(defn hash-hex
  "Convert a raw hash to a 64-char hex string."
  [hash]
  (hash/hash->hex hash))

(defn hash-as-value
  "Store a raw hash as a :u256 data value. Returns [store' hash]."
  [store raw-hash]
  (u256 store (hash/longs->bytes raw-hash)))

;; =============================================================================
;; String construction
;; =============================================================================

(defn string
  "Create a dacite string (typed seq of char scalars). Returns [store' hash].

   The string is stored as a finger tree of char scalars, with the
   string's typed-value-hash as its identity in the store."
  [store s]
  (let [chars (mapv #(clojure.core/vector :char %) s)
        [ft-store _ft-root] (ft/from-seq chars)
        merged (merge store ft-store)
        h (hash/typed-value-hash [:string s])]
    [(assoc merged h [:string s]) h]))

(defn string!
  "Create a dacite string in *store*. Returns hash."
  [s]
  (let [chars (mapv #(clojure.core/vector :char %) s)
        [ft-store _ft-root] (ft/from-seq chars)
        h (hash/typed-value-hash [:string s])]
    (swap! *store* #(assoc (merge % ft-store) h [:string s]))
    h))

(defn string-value
  "Extract the string from a :string value in the store."
  [store hash]
  (let [[type-kw data] (get store hash)]
    (when (= :string type-kw) data)))

;; =============================================================================
;; Auto-coercion (internal)
;; =============================================================================

(defn- coerce-and-store
  "Coerce a plain Clojure value to a dacite typed value and store it.
   Returns [store' hash]."
  [store x]
  (cond
    (nil? x)              (null store)
    (instance? Boolean x) (bool store x)
    (integer? x)          (i64 store x)
    (float? x)            (f64 store (double x))
    (double? x)           (f64 store x)
    (char? x)             (dacite-char store x)
    (string? x)           (string store x)
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

(defn- coerce-and-store!
  "Coerce a plain Clojure value and store in *store*. Returns hash."
  [x]
  (cond
    (nil? x)              (null!)
    (instance? Boolean x) (bool! x)
    (integer? x)          (i64! x)
    (float? x)            (f64! (double x))
    (double? x)           (f64! x)
    (char? x)             (dacite-char! x)
    (string? x)           (string! x)
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

;; =============================================================================
;; Vector construction
;; =============================================================================

(defn vector
  "Create a dacite vector from a sequence of hashes (refs already in store).
   Returns [store' hash]."
  [store refs]
  (let [[ft-store ft-root]
        (reduce (fn [[s root] ref]
                  (ft/conj-right [s root] ref))
                (let [[s root] (ft/finger-tree)]
                  [(merge store s) root])
                refs)
        ef (ft/tree-elements-fuse [ft-store ft-root])
        h (hash/node-hash :vector ef)]
    [(assoc ft-store h [:vector {:root ft-root :refs (clojure.core/vec refs)}]) h]))

(defn vector-of
  "Create a dacite vector from plain Clojure values (auto-coerced).
   Returns [store' hash]."
  [store values]
  (let [[s refs] (reduce (fn [[s refs] v]
                           (let [[s' h] (coerce-and-store s v)]
                             [s' (conj refs h)]))
                         [store []]
                         values)]
    (vector s refs)))

(defn vector!
  "Create a dacite vector from refs in *store*. Returns hash."
  [refs]
  (let [store @*store*
        [store' h] (vector store refs)]
    (reset! *store* store')
    h))

(defn vector-of!
  "Create a dacite vector from plain values in *store*. Returns hash."
  [values]
  (let [refs (mapv coerce-and-store! values)]
    (vector! refs)))

(defn vector-count
  "Get the element count of a dacite vector (O(1))."
  [store hash]
  (let [{:keys [root]} (second (get store hash))]
    (ft/tree-count [store root])))

(defn vector-nth
  "Get the nth element ref from a dacite vector."
  [store hash n]
  (let [{:keys [refs]} (second (get store hash))]
    (nth refs n)))

(defn vector-refs
  "Get all element refs from a dacite vector."
  [store hash]
  (let [{:keys [refs]} (second (get store hash))]
    refs))

(defn vector-conj
  "Append a ref to a dacite vector. Returns [store' new-hash]."
  [store hash ref]
  (let [{:keys [root refs]} (second (get store hash))
        [s' new-root] (ft/conj-right [store root] ref)
        new-refs (conj refs ref)
        ef (ft/tree-elements-fuse [s' new-root])
        h (hash/node-hash :vector ef)]
    [(assoc s' h [:vector {:root new-root :refs new-refs}]) h]))

;; =============================================================================
;; Map construction
;; =============================================================================

(defn dacite-map
  "Create a dacite map from a sequence of [key-hash val-hash] pairs
   (refs already in store). Returns [store' hash]."
  [store pairs]
  (let [[hamt-store hamt-root]
        (reduce (fn [[s root] [kh vh]]
                  (let [k-hash (hash/typed-value-hash (get s kh))]
                    (hamt/assoc-val [s root] k-hash kh vh)))
                (let [[s root] (hamt/hamt)]
                  [(merge store s) root])
                pairs)
        ef (hamt/hamt-elements-fuse [hamt-store hamt-root])
        h (hash/node-hash :map ef)]
    [(assoc hamt-store h [:map {:root hamt-root :pairs (clojure.core/vec pairs)}]) h]))

(defn map-of
  "Create a dacite map from a Clojure map (auto-coerced keys and values).
   Returns [store' hash]."
  [store m]
  (let [[s pairs] (reduce (fn [[s pairs] [k v]]
                            (let [[s' kh] (coerce-and-store s k)
                                  [s'' vh] (coerce-and-store s' v)]
                              [s'' (conj pairs [kh vh])]))
                          [store []]
                          m)]
    (dacite-map s pairs)))

(defn dacite-map!
  "Create a dacite map from [key-hash val-hash] pairs in *store*. Returns hash."
  [pairs]
  (let [store @*store*
        [store' h] (dacite-map store pairs)]
    (reset! *store* store')
    h))

(defn map-of!
  "Create a dacite map from a Clojure map in *store*. Returns hash."
  [m]
  (let [pairs (mapv (fn [[k v]]
                      [(coerce-and-store! k) (coerce-and-store! v)])
                    m)]
    (dacite-map! pairs)))

(defn map-count
  "Get the entry count of a dacite map (O(1))."
  [store hash]
  (let [{:keys [root]} (second (get store hash))]
    (hamt/hamt-count [store root])))

(defn map-get
  "Look up a value ref by key in a dacite map.
   Key is auto-coerced. Returns the val-ref hash or nil."
  [store hash key]
  (let [{:keys [root]} (second (get store hash))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))]
    (hamt/get-val [store root] k-hash)))

(defn map-assoc
  "Associate a key-ref and val-ref in a dacite map. Returns [store' new-hash]."
  [store hash key-ref val-ref]
  (let [{:keys [root pairs]} (second (get store hash))
        k-hash (hash/typed-value-hash (get store key-ref))
        [s' new-root] (hamt/assoc-val [store root] k-hash key-ref val-ref)
        new-pairs (conj (clojure.core/vec (remove #(= key-ref (first %)) pairs))
                        [key-ref val-ref])
        ef (hamt/hamt-elements-fuse [s' new-root])
        h (hash/node-hash :map ef)]
    [(assoc s' h [:map {:root new-root :pairs new-pairs}]) h]))

(defn map-dissoc
  "Remove a key from a dacite map. Key is auto-coerced. Returns [store' new-hash]."
  [store hash key]
  (let [{:keys [root pairs]} (second (get store hash))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))
        [s'' new-root] (hamt/dissoc-val [store root] k-hash)
        new-pairs (clojure.core/vec (remove #(= kh (first %)) pairs))
        ef (hamt/hamt-elements-fuse [s'' new-root])
        h (hash/node-hash :map ef)]
    [(assoc s'' h [:map {:root new-root :pairs new-pairs}]) h]))

(defn map-entries
  "Get all entries as a sequence of [key-ref val-ref] hash pairs."
  [store hash]
  (let [{:keys [root]} (second (get store hash))]
    (hamt/entries [store root])))

;; =============================================================================
;; Content equality
;; =============================================================================

(defn dacite=
  "Content equality: two hashes are equal if they're the same hash."
  [h1 h2]
  (= h1 h2))

;; =============================================================================
;; REPL examples
;; =============================================================================

;; See test/dacite/core_test.clj for usage examples.
