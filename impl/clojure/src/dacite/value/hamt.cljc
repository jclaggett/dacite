(ns dacite.value.hamt
  "Store-aware Hash Array Mapped Trie (HAMT) for the value layer.

   Like the value finger tree, every operation reads and writes nodes
   directly through the value's IStore via s-get / s-put rather than
   threading a pure map. The store is mutated in place, so operations
   return only the new root hash.

   The key's 256-bit hash is read 5 bits at a time, from the most-mixed word
   (c0) down: chunk 0 is the top 5 bits, chunk 50 the 5 bits before the last,
   and chunk 51 the last bit alone (value 0 or 16). There are 52 chunks. Each
   node caches a measure for O(1) count, size, and data hash at the root.

   Node types (stored as [type-name data]):
   - [\"hamt/empty\"  {:measure m}]
   - [\"hamt/entry\"  {:key-hash h :key-ref h :val-ref h :measure m}]
   - [\"hamt/bitmap\" {:bitmap n :split c :children [h...] :measure m}]

   **Telescoping, canonical shape (issue #12).** A bitmap node never has one
   child. It holds two or more, and `:split` is the index of the chunk it
   branches on: the first chunk at which its keys do not all agree. The keys of
   a node agree on every chunk before `:split`, and each child holds the keys
   that share the node's chunk at `:split`, so a child's own split is greater.
   A node is therefore the HAMT of its own key set, whatever order the keys
   were added in, whatever was removed, and whichever map holds it: equal key
   sets give byte-identical nodes (proof sketch: peer-v1 §3.3). `assoc` makes a
   new branch directly at the chunk where two keys first differ; `dissoc`
   replaces a node left with one child by that child (an entry, or a node that
   is already canonical for its keys).

   Bitmap nodes are the one exception to shape-independence (§3.7): their
   bitmap and split are folded into the hash (`bitmap-node-hash`), because two
   bitmaps with the same elements but different routing are NOT
   interchangeable.

   **Full-hash collisions.** Two keys with the same 256-bit hash are the same
   key: the hash is a content hash and the key *is* its content, so there is no
   collision bucket. `assoc` of an equal key-hash replaces the entry. Distinct
   hashes differ at some chunk 0..51, so a branch always exists, at the latest
   at the last chunk (51), where only one bit is read. A lookup that reaches an
   entry compares the whole key-hash, not just the chunks it read.

   The bitmap and 64-bit hash-word navigation go through dacite.host so the
   arithmetic is identical on JVM (native long) and JS (BigInt)."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const BITS 5)
(def ^:private MASK-WORD (host/word 0x1F))
(def ^:private ONE-WORD (host/word 1))

;; =============================================================================
;; Measure (monoid)
;; =============================================================================

(def measure-identity
  {:count 0 :size-bytes 0 :elements-fuse host/zero-hash})

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))
   :elements-fuse (hash/unchecked-fuse (:elements-fuse m1) (:elements-fuse m2))})

(defn- measure-seq [measures]
  (reduce measure-combine measure-identity measures))

;; =============================================================================
;; Hash navigation
;; =============================================================================

(defn hash-chunk
  "Extract the 5-bit chunk of a hash at the given level (0 = most
   significant), as a native int 0..31. Levels span the 256-bit hash from
   c0 down to c3."
  [hash-longs level]
  (let [bit-offset (* level BITS)
        long-idx (quot bit-offset 64)
        bit-in-long (mod bit-offset 64)
        long-val (nth hash-longs long-idx host/zero-word)
        shift (- 59 bit-in-long)]
    (if (>= shift 0)
      (host/word->int (host/band64 MASK-WORD (host/ushr64 long-val shift)))
      (let [bits-from-current (+ shift 5)
            bits-from-next (- 5 bits-from-current)
            current-part (host/shl64
                          (host/band64 long-val
                                       (host/sub64 (host/shl64 ONE-WORD bits-from-current)
                                                   ONE-WORD))
                          bits-from-next)
            next-long (nth hash-longs (inc long-idx) host/zero-word)
            next-part (host/ushr64 next-long (- 64 bits-from-next))]
        (host/word->int (host/band64 MASK-WORD (host/bor64 current-part next-part)))))))

(defn- chunk-bit
  "The single-bit word for a chunk index (0..31)."
  [chunk]
  (host/shl64 ONE-WORD chunk))

(defn- index-of
  "Sparse index of a chunk in a bitmap: popcount of bits below `bit`."
  [bitmap bit]
  (host/popcount (host/band64 bitmap (host/sub64 bit ONE-WORD))))

;; =============================================================================
;; Node helpers (store-backed)
;; =============================================================================

(def ^:const LAST-CHUNK
  "Index of the last hash chunk (chunks 0..51 cover the 256-bit hash)."
  51)

(defn bitmap-node-hash
  "Hash of a `hamt/bitmap` node, from its own data:
   `fuse(node_hash(\"hamt/bitmap\", elements_fuse), fuse_bytes(bitmap_be8 ++ [split]))`.

   `bitmap_be8` is the bitmap as 8 big-endian bytes, `split` one byte. The
   bitmap and the split tell two nodes of different routing apart (§3.7); with
   telescoping (issue #12) the split also tells a node from any node below it
   that holds the same entries, since a child's split is greater than its
   parent's. The children's hashes are not folded in, so a receiver checks it
   from the node's own bytes."
  [ef bitmap split]
  (hash/unchecked-fuse (types/node-hash "hamt/bitmap" ef)
                       (hash/fuse-bytes (conj (vec (host/int->bytes-be bitmap 8))
                                              split))))

(defn- add-node!
  "Persist a node, returning its content hash. Bitmap nodes fold their
   bitmap and split into the hash (`bitmap-node-hash`); all other nodes use
   the plain fuse(type_hash, elements_fuse) rule."
  [store node]
  (let [type-name (first node)
        data (second node)
        ef (:elements-fuse (:measure data))
        h (if (= "hamt/bitmap" type-name)
            (bitmap-node-hash ef (:bitmap data) (:split data))
            (types/node-hash type-name ef))]
    (store/await (store/s-put store h node))
    h))

(defn- lookup [store h] (store/require-node store h))
(defn- node-type [node] (first node))
(defn- node-data [node] (second node))
(defn- get-measure [store h] (:measure (node-data (lookup store h))))

(defn- bad-node! [msg data]
  (throw (ex-info msg (assoc data :reason :bad-hamt-node))))

(defn- bitmap-fields
  "`{:bitmap :split :children}` of a bitmap node reached under a parent whose
   split is `above` (-1 at the root). A split must exceed its parent's and be
   at most 51: that bounds every walk at 52 nodes even for a forged store."
  [data above]
  (let [split (:split data)]
    (when-not (and (integer? split) (> split above) (<= split LAST-CHUNK))
      (bad-node! "hamt/bitmap split out of order" {:split split :above above}))
    data))

(defn- first-key-hash
  "Key hash of the leftmost entry under `h`: a representative of the node's
   key set, whose keys all agree on every chunk before the node's split."
  [store h]
  (loop [h h, n 0]
    (let [node (lookup store h)]
      (case (node-type node)
        "hamt/entry" (:key-hash (node-data node))
        "hamt/bitmap" (if (> n LAST-CHUNK)
                        (bad-node! "hamt too deep" {})
                        (recur (first (:children (node-data node))) (inc n)))))))

(defn- first-diff-chunk
  "First chunk in [from, limit) at which hashes `a` and `b` differ, or nil."
  [a b from limit]
  (loop [c from]
    (cond
      (>= c limit) nil
      (not= (hash-chunk a c) (hash-chunk b c)) c
      :else (recur (inc c)))))

;; =============================================================================
;; Node constructors
;; =============================================================================

(defn- make-empty! [store]
  (add-node! store ["hamt/empty" {:measure measure-identity}]))

(defn- make-entry! [store key-hash key-ref val-ref measure]
  (add-node! store ["hamt/entry" {:key-hash key-hash
                                  :key-ref key-ref
                                  :val-ref val-ref
                                  :measure measure}]))

(defn- make-bitmap!
  "A bitmap node of two or more `children`, in chunk order, that branch on
   chunk `split`. The measure is the sum of the children's."
  [store bitmap split children]
  (add-node! store ["hamt/bitmap" {:bitmap bitmap
                                   :split split
                                   :children (vec children)
                                   :measure (measure-seq
                                             (mapv #(get-measure store %) children))}]))

(defn- branch2!
  "The node that holds stored nodes `a` (key hash `ka`) and `b` (key hash
   `kb`) side by side, branching at chunk `d`, where the hashes differ."
  [store d a ka b kb]
  (let [ca (hash-chunk ka d)
        cb (hash-chunk kb d)]
    (make-bitmap! store
                  (host/bor64 (chunk-bit ca) (chunk-bit cb))
                  d
                  (if (< ca cb) [a b] [b a]))))

;; =============================================================================
;; Internal operations
;; =============================================================================

(defn- hamt-lookup* [store node-hash key-hash above]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" nil
      "hamt/entry"
      (let [{ekh :key-hash evr :val-ref} (node-data node)]
        (when (= ekh key-hash) evr))
      "hamt/bitmap"
      (let [{:keys [bitmap split children]} (bitmap-fields (node-data node) above)
            bit (chunk-bit (hash-chunk key-hash split))]
        (when-not (host/word-zero? (host/band64 bitmap bit))
          (hamt-lookup* store (nth children (index-of bitmap bit)) key-hash split))))))

(defn- hamt-assoc*
  "Add the entry under `node-hash`. `from` is the first chunk at which the
   new key might still differ from the keys under the node: those before it
   agree, by the routing above."
  [store node-hash key-hash key-ref val-ref measure from]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty"
      (make-entry! store key-hash key-ref val-ref measure)

      "hamt/entry"
      (let [ekh (:key-hash (node-data node))]
        (if (= ekh key-hash)
          (make-entry! store key-hash key-ref val-ref measure)
          (let [d (first-diff-chunk ekh key-hash from (inc LAST-CHUNK))]
            (branch2! store d node-hash ekh
                      (make-entry! store key-hash key-ref val-ref measure) key-hash))))

      "hamt/bitmap"
      (let [{:keys [bitmap split children]} (bitmap-fields (node-data node) (dec from))
            ;; the new key may leave the node's keys before the split: then the
            ;; branch goes here, above the node, at the first chunk it differs
            rep (when (< from split) (first-key-hash store node-hash))
            d (when rep (first-diff-chunk rep key-hash from split))]
        (if d
          (branch2! store d node-hash rep
                    (make-entry! store key-hash key-ref val-ref measure) key-hash)
          (let [bit (chunk-bit (hash-chunk key-hash split))
                idx (index-of bitmap bit)]
            (if-not (host/word-zero? (host/band64 bitmap bit))
              (make-bitmap! store bitmap split
                            (assoc children idx
                                   (hamt-assoc* store (nth children idx) key-hash
                                                key-ref val-ref measure (inc split))))
              (make-bitmap! store (host/bor64 bitmap bit) split
                            (vec (concat (subvec children 0 idx)
                                         [(make-entry! store key-hash key-ref val-ref measure)]
                                         (subvec children idx)))))))))))

(defn- hamt-dissoc*
  "Remove a key. Returns the new node hash, or nil if the node becomes
   empty. A node left with one child is replaced by that child, so no node
   with one child is ever built."
  [store node-hash key-hash above]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" nil
      "hamt/entry"
      (if (= key-hash (:key-hash (node-data node))) nil node-hash)
      "hamt/bitmap"
      (let [{:keys [bitmap split children]} (bitmap-fields (node-data node) above)
            bit (chunk-bit (hash-chunk key-hash split))]
        (if (host/word-zero? (host/band64 bitmap bit))
          node-hash
          (let [idx (index-of bitmap bit)
                old-child (nth children idx)
                new-child (hamt-dissoc* store old-child key-hash split)]
            (cond
              (= new-child old-child) node-hash

              (nil? new-child)
              (let [new-children (vec (concat (subvec children 0 idx)
                                              (subvec children (inc idx))))]
                (if (= 1 (count new-children))
                  (first new-children)
                  (make-bitmap! store (host/band64 bitmap (host/bnot64 bit)) split
                                new-children)))

              :else
              (make-bitmap! store bitmap split (assoc children idx new-child)))))))))

(defn- hamt-entries* [store node-hash above]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" []
      "hamt/entry" (let [{:keys [key-ref val-ref]} (node-data node)]
                     [[key-ref val-ref]])
      "hamt/bitmap" (let [{:keys [split children]} (bitmap-fields (node-data node) above)]
                      (mapcat #(hamt-entries* store % split) children)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn hamt-empty
  "Create an empty HAMT in the store. Returns its root hash."
  [store]
  (make-empty! store))

(defn hamt-assoc
  "Associate key-ref/val-ref under key-hash (the key's content hash, used
   for navigation). Both refs must already be in the store. Returns the
   new root hash."
  [store root key-hash key-ref val-ref]
  (let [key-size (types/dacite-size (lookup store key-ref))
        val-size (types/dacite-size (lookup store val-ref))
        measure {:count 1
                 :size-bytes (+ key-size val-size)
                 :elements-fuse (hash/unchecked-fuse key-ref val-ref)}]
    (hamt-assoc* store root key-hash key-ref val-ref measure 0)))

(defn hamt-get
  "Look up a value ref by key hash. Returns the val-ref or nil."
  [store root key-hash]
  (hamt-lookup* store root key-hash -1))

(defn hamt-dissoc
  "Remove a key by key hash. Returns the new root hash (an empty node if
   the map becomes empty)."
  [store root key-hash]
  (let [new-root (hamt-dissoc* store root key-hash -1)]
    (if (nil? new-root)
      (make-empty! store)
      new-root)))

(defn hamt-entries
  "All [key-ref val-ref] pairs, in ascending key-hash traversal order."
  [store root]
  (hamt-entries* store root -1))

(defn hamt-measure [store root]
  (get-measure store root))

(defn hamt-count
  "Entry count, O(1) via cached measure."
  [store root]
  (:count (get-measure store root)))

(defn hamt-size-bytes
  "Total key+value byte size, O(1) via cached measure."
  [store root]
  (:size-bytes (get-measure store root)))

(defn hamt-elements-fuse
  "The map's data hash: the fuse of all entry fuses, O(1)."
  [store root]
  (:elements-fuse (get-measure store root)))

(defn hamt-from-entries
  "Build a HAMT root by assoc of [key-hash key-ref val-ref] triples
   (refs already in store). Same path as map construction.

   The shape is canonical, so any node rebuilt from the entries it holds is
   byte-identical to the original: every bitmap node round-trips."
  [store entries]
  (reduce (fn [root [key-hash key-ref val-ref]]
            (hamt-assoc store root key-hash key-ref val-ref))
          (hamt-empty store)
          entries))

(defn hamt-entry-node
  "Persist a single hamt/entry for key-hash / key-ref / val-ref; return hash."
  [store key-hash key-ref val-ref]
  (let [key-size (types/dacite-size (lookup store key-ref))
        val-size (types/dacite-size (lookup store val-ref))
        measure {:count 1
                 :size-bytes (+ key-size val-size)
                 :elements-fuse (hash/unchecked-fuse key-ref val-ref)}]
    (make-entry! store key-hash key-ref val-ref measure)))

;; =============================================================================
;; child-hashes implementations for HAMT node types
;; =============================================================================

(defmethod types/child-hashes "hamt/empty" [_] [])

(defmethod types/child-hashes "hamt/entry" [[_ data]]
  [(:key-ref data) (:val-ref data)])

(defmethod types/child-hashes "hamt/bitmap" [[_ data]]
  (:children data))
