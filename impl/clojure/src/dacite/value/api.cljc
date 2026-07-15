(ns dacite.value.api
  "Functional, host-agnostic surface over Dacite collection values.

   This is the canonical cross-language API. On the JVM, Dacite collections
   also implement the native clojure.lang.* interfaces, so plain
   `clojure.core/get`/`conj`/`nth`/`count` work too; but on SCI hosts
   (babashka, nbb) those interfaces are unavailable, so all code — and any
   future Python/C/C++ port — operates through these functions.

   Every function takes a Dacite value as its first argument and dispatches
   on its type (\"vector\", \"map\", \"set\", \"string\", \"blob\"). Element
   accessors return wrapped Dacite values; use `realize` (or dacite.value's
   realize) to recover native content.

   Usage:  (require '[dacite.value.api :as d])
           (d/conj v 42) (d/get m :k) (d/nth v 0) (d/count v)"
  (:refer-clojure :exclude [count nth get assoc conj seq peek pop keys vals
                            contains? dissoc empty?])
  (:require [dacite.value.types :as types]
            [dacite.value.collections :as coll]))

(defn- s [v] (types/dacite-store v))
(defn- h [v] (types/dacite-hash v))

(defn dacite-value?
  "True if x is a Dacite value."
  [x]
  (satisfies? types/IDaciteValue x))

(defn value-type
  "The type-name string of a Dacite value (\"vector\", \"map\", ...)."
  [v]
  (types/dacite-type v))

(def realize
  "Recover a Dacite value's content in the host language."
  types/realize)

(defn count
  "Number of elements/entries in a Dacite collection, O(1)."
  [v]
  (coll/coll-count (s v) (h v)))

(defn empty?
  "True if the Dacite collection has no elements."
  [v]
  (zero? (count v)))

(defn seq
  "Elements of a sequence (string/blob/vector) as wrapped values,
   [k v] pairs of a map (wrapped), or members of a set (wrapped).
   Returns nil when empty."
  [v]
  (case (value-type v)
    ("string" "blob" "vector") (coll/seq-vals (s v) (h v))
    "map" (coll/map-entries (s v) (h v))
    "set" (coll/set-vals (s v) (h v))
    nil))

(defn nth
  "Wrapped element at index i of a sequence collection."
  ([v i]
   (coll/seq-nth (s v) (h v) i))
  ([v i not-found]
   (if (and (integer? i) (<= 0 i) (< i (count v)))
     (coll/seq-nth (s v) (h v) i)
     not-found)))

(defn get
  "Look up a key/index in a map/set/vector. Returns a wrapped value or
   not-found (nil by default)."
  ([v k] (get v k nil))
  ([v k not-found]
   (case (value-type v)
     "map"    (coll/map-get (s v) (h v) k not-found)
     "set"    (coll/set-get (s v) (h v) k not-found)
     "vector" (if (and (integer? k) (<= 0 k) (< k (count v)))
                (coll/seq-nth (s v) (h v) k)
                not-found)
     not-found)))

(defn contains?
  "True if key/index k is present."
  [v k]
  (case (value-type v)
    "map"    (coll/map-contains? (s v) (h v) k)
    "set"    (coll/set-contains? (s v) (h v) k)
    "vector" (and (integer? k) (<= 0 k) (< k (count v)))
    false))

(defn assoc
  "Associate k->val in a vector (integer index) or map. Returns a new
   Dacite value."
  [v k val]
  (case (value-type v)
    "vector" (coll/vec-assoc (s v) (h v) k val)
    "map"    (coll/map-assoc (s v) (h v) k val)
    (throw (ex-info "assoc unsupported for type" {:type (value-type v)}))))

(defn dissoc
  "Remove key k from a map. Returns a new Dacite map."
  [v k]
  (case (value-type v)
    "map" (coll/map-dissoc (s v) (h v) k)
    (throw (ex-info "dissoc unsupported for type" {:type (value-type v)}))))

(defn conj
  "Append to a vector, add to a set, or add a [k v] pair to a map. Returns
   a new Dacite value."
  [v x]
  (case (value-type v)
    "vector" (coll/vec-conj (s v) (h v) x)
    "set"    (coll/set-conj (s v) (h v) x)
    "map"    (coll/map-assoc (s v) (h v) (clojure.core/nth x 0) (clojure.core/nth x 1))
    (throw (ex-info "conj unsupported for type" {:type (value-type v)}))))

(defn peek
  "Last element of a vector (wrapped), or nil if empty."
  [v]
  (coll/vec-peek (s v) (h v)))

(defn pop
  "Drop the last element of a vector. Returns a new Dacite vector."
  [v]
  (coll/vec-pop (s v) (h v)))

(defn keys
  "Wrapped keys of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (s v) (h v))]
    (map first es)))

(defn vals
  "Wrapped values of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (s v) (h v))]
    (map second es)))
