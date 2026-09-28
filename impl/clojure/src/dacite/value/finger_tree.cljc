(ns dacite.value.finger-tree
  "Store-aware finger tree for the value layer.

   This is the value refactor's core difference from the original value
   layer: instead of threading a pure {hash -> node} map and merging the
   result back into a global cache, every operation reads and writes nodes
   directly through the value's own IStore via s-get / s-put.

   Nodes are content-addressed: a node's hash is fuse(type_hash,
   elements_fuse), independent of tree shape (§3.6). Because the store is
   mutated in place, operations return only the new root hash.

   Node types (stored as [type-name data]):
   - [\"ft/empty\"  {:measure m}]
   - [\"ft/digit\"  {:children [h...] :measure m}]  ; pointer page (legacy)
   - [\"ft/digit\"  {:body lit :measure m}]         ; 1k literal page
   - [\"ft/node\"   {:children [h...] :measure m}]
   - [\"ft/node\"   {:body lit :measure m}]
   - [\"ft/deep\"   {:left h :spine h :right h :measure m}]

   Leaf elision: digit/node children and 1-element roots are bare value
   hashes (any non-ft/* entry — implicit singles). Structural cells are
   only ft/empty|digit|node|deep. Nested collections must be public
   collection nodes (vector/string/blob/…), never a bare ft/* spine.
   See docs/design/ft-single-elision.md.

   A digit or node is a page of about 1,024 encoded bytes. Writers still
   emit pointer pages (`:children`, 1–32 hashes). Readers dual-read
   `:body` sequence literals (run/repeat/ref/nested lits). A u8 run's
   `:values` is a copied byte array (Uint8Array on CLJS). See
   docs/design/dense-sequence-leaves.md."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.lit :as lit]
            [clojure.string :as str]))

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
;; Node helpers (store-backed)
;; =============================================================================

(defn- add-node!
  "Persist an internal node, returning its content hash. The hash is
   derived from the node type and its elements_fuse, so equal logical
   nodes normalize to one entry regardless of tree shape."
  [store node]
  (let [type-name (first node)
        ef (:elements-fuse (:measure (second node)))
        h (types/node-hash type-name ef)]
    (store/s-put store h node)
    h))

(declare ft-seq)

(defn- lookup [store h] (store/s-get store h))
(defn- node-type [node] (first node))
(defn- node-data [node] (second node))

(defn- ft-type?
  "True when type-name is a finger-tree structural type (ft/*)."
  [type-name]
  (str/starts-with? (str type-name) "ft/"))

(defn- measure-of
  "Measure of an FT cell or implicit leaf (non-ft/*).

   Leaf measure is synthesized: count 1, size-bytes from the entry,
   elements-fuse = the leaf hash itself."
  [store h]
  (let [entry (lookup store h)
        t (node-type entry)]
    (if (ft-type? t)
      (:measure (node-data entry))
      {:count 1
       :size-bytes (types/dacite-size entry)
       :elements-fuse h})))

(defn- as-leaf-hash
  "Leaf value hash (identity). Throws if h names a structural FT cell."
  [store h]
  (let [entry (lookup store h)
        t (node-type entry)]
    (if (ft-type? t)
      (throw (ex-info "expected leaf value hash, not ft/* cell"
                      {:type t :hash h}))
      h)))

(defn- assert-leaf-value!
  "ft-conj may only take public value hashes, not structural ft/* cells."
  [store h]
  (let [entry (lookup store h)
        t (node-type entry)]
    (when (ft-type? t)
      (throw (ex-info "ft-conj requires a leaf value hash, not an ft/* cell"
                      {:type t :hash h})))
    h))

(defn- page-child-hashes
  "Direct child hashes of a digit/node. Pointer pages (`:children`) are
   used as-is. Literal pages (`:body`) intern inlined scalars."
  [store data]
  (if-let [ch (:children data)]
    (vec ch)
    (lit/body-child-hashes store (or (:body data) []))))

(defn- get-children [store h]
  (page-child-hashes store (node-data (lookup store h))))

(defn- page-refs
  "Reachable hashes a pack/GC walk should follow. Pointer pages list every
   child; literal pages list only `ref` items (inlined payload stays in body)."
  [data]
  (if-let [ch (:children data)]
    (vec ch)
    (lit/body-refs (:body data))))

(defn- hash->item
  "Preferred page-body encoding of a stored child hash."
  [store h]
  (let [entry (lookup store h)]
    (if (nil? entry)
      {:type "ref" :body h}
      (let [t (node-type entry)]
        (cond
          (ft-type? t) {:type "ref" :body h}

          (contains? lit/scalar-types t)
          (if (< (types/dacite-size entry) 32)
            {:type t :body (types/entry-data entry)}
            {:type "ref" :body h})

          (= t "string")
          (let [sb (long (or (:size-bytes (node-data entry)) 0))]
            (if (<= sb lit/page-budget)
              {:type "string"
               :body (lit/join-chars
                      (map (fn [ch]
                             (types/entry-data (lookup store ch)))
                           (ft-seq store (:root (node-data entry)))))}
              {:type "ref" :body h}))

          (= t "blob")
          (let [sb (long (or (:size-bytes (node-data entry)) 0))]
            (if (<= sb lit/page-budget)
              {:type "blob"
               :body (mapv (fn [bh]
                             (types/entry-data (lookup store bh)))
                           (ft-seq store (:root (node-data entry))))}
              {:type "ref" :body h}))

          :else {:type "ref" :body h})))))

(defn- page-body-from-data
  "Literal body of a digit/node, converting legacy `:children` to items."
  [store data]
  (or (:body data)
      (lit/rle-lits (mapv #(hash->item store %) (or (:children data) [])))))

(defn- item-measure
  [store item]
  (let [t (str (:type item))
        b (:body item)]
    (cond
      (= t "ref") (measure-of store b)

      (contains? lit/scalar-types t)
      (let [h (types/scalar-value-hash [t b])]
        {:count 1
         :size-bytes (types/dacite-size [t b])
         :elements-fuse h})

      (= t "string")
      (let [s (str b)
            ef (reduce (fn [a ch]
                         (hash/unchecked-fuse
                          a (types/scalar-value-hash ["char" ch])))
                       host/zero-hash
                       (seq s))]
        {:count 1
         :size-bytes (count (host/utf8-bytes s))
         :elements-fuse (types/value-hash "string" ef)})

      (= t "blob")
      (let [xs (vec b)
            ef (reduce (fn [a n]
                         (hash/unchecked-fuse
                          a (types/scalar-value-hash ["u8" (int n)])))
                       host/zero-hash
                       xs)]
        {:count 1
         :size-bytes (count xs)
         :elements-fuse (types/value-hash "blob" ef)})

      :else
      (measure-of store (lit/intern-lit-item! store item)))))

(defn- u8-hash-table
  "The 256 u8 scalar value hashes, indexed by unsigned byte."
  []
  (let [hs (mapv (fn [i] (types/scalar-value-hash ["u8" i])) (range 256))]
    #?(:clj (into-array Object hs)
       :cljs (into-array hs))))

(def ^:private u8-scalar-hashes
  "Computed once. A page measure fuses these in place."
  (delay (u8-hash-table)))

(defn- u8-hash-at [n]
  #?(:clj (aget ^objects @u8-scalar-hashes (int n))
     :cljs (aget @u8-scalar-hashes n)))

#?(:clj
   (defn- fuse-u8-array
     "Fuse per-byte u8 scalar hashes of a byte array. No per-byte map or
      Integer is retained."
     [^bytes bs]
     (let [^objects hs @u8-scalar-hashes
           n (alength bs)]
       (loop [i (int 0) acc host/zero-hash]
         (if (== i n)
           acc
           (recur (unchecked-inc-int i)
                  (hash/unchecked-fuse
                   acc
                   (aget hs (Byte/toUnsignedInt (aget bs i)))))))))
   :cljs
   (defn- fuse-u8-array [bs]
     (let [hs @u8-scalar-hashes
           n (lit/u8-count bs)]
       (loop [i 0 acc host/zero-hash]
         (if (== i n)
           acc
           (recur (inc i)
                  (hash/unchecked-fuse acc (aget hs (lit/u8-nth bs i)))))))))

(defn- u8-run-measure
  "Measure of a u8 run: count and size are the byte length, and
   elements-fuse is the fuse of the per-byte u8 scalar hashes."
  [values]
  (let [n (lit/u8-count values)
        ef (if (lit/u8-bytes? values)
             (fuse-u8-array values)
             (loop [i 0 acc host/zero-hash]
               (if (== i n)
                 acc
                 (recur (inc i)
                        (hash/unchecked-fuse
                         acc (u8-hash-at (lit/u8-nth values i)))))))]
    {:count n :size-bytes n :elements-fuse ef}))

(defn- body-measure
  [store body]
  (measure-seq
   (map (fn [item]
          (if (lit/u8-run? item)
            (u8-run-measure (:values (:body item)))
            (item-measure store item)))
        (mapcat (fn [item]
                  (if (lit/u8-run? item)
                    [item]
                    (lit/expand-rle-seq [item])))
                (or body [])))))

(defn- make-digit-body!
  [store body]
  (add-node! store ["ft/digit" {:body (vec body)
                                :measure (body-measure store body)}]))

(defn- make-node-body!
  [store body]
  (add-node! store ["ft/node" {:body (vec body)
                               :measure (body-measure store body)}]))

(defn ft-digit-from-body!
  "Persist an ft/digit from a literal page body.
   A u8 run is measured by scanning its byte buffer."
  [store body]
  (make-digit-body! store body))

(defn ft-node-from-body!
  "Persist an ft/node from a literal page body.
   A u8 run is measured by scanning its byte buffer."
  [store body]
  (make-node-body! store body))

(defn- persist-spill!
  "Persist an overflow prefix as a spine element. A single ref is used
   as-is; any other payload becomes an ft/node page."
  [store body]
  (let [items (lit/expand-rle-seq body)]
    (cond
      (empty? items) nil
      (and (= 1 (count items))
           (= "ref" (str (:type (first items)))))
      (:body (first items))
      :else (make-node-body! store body))))

(defn- try-append
  "Append item to body if the page stays within budget. Falls back to a
   ref of `h` when the inlined form does not fit. Nil means overflow."
  [body item h]
  (let [b1 (lit/append-item body item)]
    (if (<= (lit/payload-bytes b1) lit/page-budget)
      b1
      (when-not (= "ref" (str (:type item)))
        (let [b2 (lit/append-item body {:type "ref" :body h})]
          (when (<= (lit/payload-bytes b2) lit/page-budget)
            b2))))))

(defn- try-prepend
  [body item h]
  (let [b1 (lit/prepend-item body item)]
    (if (<= (lit/payload-bytes b1) lit/page-budget)
      b1
      (when-not (= "ref" (str (:type item)))
        (let [b2 (lit/prepend-item body {:type "ref" :body h})]
          (when (<= (lit/payload-bytes b2) lit/page-budget)
            b2))))))

;; =============================================================================
;; Node constructors (persist, return hash)
;; =============================================================================

(defn- make-empty! [store]
  (add-node! store ["ft/empty" {:measure measure-identity}]))

(defn- make-digit! [store child-hashes child-measures]
  (let [body (lit/rle-lits (mapv #(hash->item store %) child-hashes))]
    (add-node! store ["ft/digit" {:body body
                                  :measure (measure-seq child-measures)}])))

(defn- make-node! [store child-hashes child-measures]
  {:pre [(<= 1 (count child-hashes))]}
  (let [body (lit/rle-lits (mapv #(hash->item store %) child-hashes))]
    (add-node! store ["ft/node" {:body body
                                 :measure (measure-seq child-measures)}])))

(defn- as-digit!
  "Ensure `h` is an ft/digit page. An existing digit is reused. An ft/node
   is re-wrapped (different type ⇒ different hash). A leaf becomes a
   one-element digit. Never a digit of one ref to another digit — that
   collides with the inner page under type+elements_fuse hashing."
  [store h]
  (let [node (lookup store h)
        t (node-type node)]
    (case t
      "ft/digit" h
      "ft/node" (make-digit-body! store (page-body-from-data store (node-data node)))
      (make-digit! store [h] [(measure-of store h)]))))

(defn- make-deep! [store left spine right left-m spine-m right-m]
  (add-node! store ["ft/deep" {:left left
                               :spine spine
                               :right right
                               :measure (measure-combine
                                         (measure-combine left-m spine-m)
                                         right-m)}]))

(defn- empty-node? [store h]
  (= "ft/empty" (node-type (lookup store h))))

;; =============================================================================
;; Digit operations
;; =============================================================================

(defn- digit-first [store dh] (first (get-children store dh)))
(defn- digit-last [store dh] (peek (get-children store dh)))

(defn- digit-rest!
  "Drop the first child. Returns the new digit hash, or nil if it would
   become empty."
  [store dh]
  (let [children (get-children store dh)]
    (when (> (count children) 1)
      (let [nc (subvec children 1)]
        (make-digit! store nc (mapv #(measure-of store %) nc))))))

(defn- digit-butlast!
  "Drop the last child. Returns the new digit hash, or nil if it would
   become empty."
  [store dh]
  (let [children (get-children store dh)]
    (when (> (count children) 1)
      (let [nc (pop children)]
        (make-digit! store nc (mapv #(measure-of store %) nc))))))

;; =============================================================================
;; Tree operations (internal — operate on element/single hashes)
;; =============================================================================

(declare tree-conj-left! tree-conj-right! ft-seq)

(defn- tree-first* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (digit-first store (:left (node-data node)))
      ("ft/digit" "ft/node") (first (get-children store root))
      root)))

(defn- tree-last* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (digit-last store (:right (node-data node)))
      ("ft/digit" "ft/node") (peek (get-children store root))
      root)))

(defn- to-tree-from-digit!
  "Rebuild a tree from a digit's children."
  [store dh]
  (reduce (fn [h child] (tree-conj-right! store h child))
          (make-empty! store)
          (get-children store dh)))

(defn- tree-rest* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" root
      ("ft/digit" "ft/node")
      (or (digit-rest! store root) (make-empty! store))
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            new-left (digit-rest! store left)]
        (if new-left
          (make-deep! store new-left spine right
                      (measure-of store new-left)
                      (measure-of store spine)
                      (measure-of store right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store right)
            (let [spine-first (tree-first* store spine)
                  new-spine (tree-rest* store spine)
                  nch (get-children store spine-first)
                  new-left' (make-digit! store nch (mapv #(measure-of store %) nch))]
              (make-deep! store new-left' new-spine right
                          (measure-of store new-left')
                          (measure-of store new-spine)
                          (measure-of store right))))))
      (make-empty! store))))

(defn- tree-butlast* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" root
      ("ft/digit" "ft/node")
      (or (digit-butlast! store root) (make-empty! store))
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            new-right (digit-butlast! store right)]
        (if new-right
          (make-deep! store left spine new-right
                      (measure-of store left)
                      (measure-of store spine)
                      (measure-of store new-right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store left)
            (let [spine-last (tree-last* store spine)
                  new-spine (tree-butlast* store spine)
                  nch (get-children store spine-last)
                  new-right' (make-digit! store nch (mapv #(measure-of store %) nch))]
              (make-deep! store left new-spine new-right'
                          (measure-of store left)
                          (measure-of store new-spine)
                          (measure-of store new-right'))))))
      (make-empty! store))))

(defn- tree-conj-left! [store root elem]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" elem
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            old-body (page-body-from-data store (node-data (lookup store left)))
            item (hash->item store elem)
            fitted (try-prepend old-body item elem)]
        (if fitted
          (let [new-left (make-digit-body! store fitted)]
            (make-deep! store new-left spine right
                        (measure-of store new-left)
                        (measure-of store spine)
                        (measure-of store right)))
          (let [old-pb (max 1 (lit/payload-bytes old-body))
                [keep spill] (lit/split-body-at old-body (quot old-pb 3))
                new-node (persist-spill! store spill)
                new-spine (if new-node
                            (tree-conj-left! store spine new-node)
                            spine)
                keep' (or (try-prepend keep item elem)
                          (lit/prepend-item keep {:type "ref" :body elem}))
                new-left (make-digit-body! store keep')]
            (make-deep! store new-left new-spine right
                        (measure-of store new-left)
                        (measure-of store new-spine)
                        (measure-of store right)))))
      (let [left (as-digit! store elem)
            spine (make-empty! store)
            right (as-digit! store root)]
        (make-deep! store left spine right
                    (measure-of store left)
                    (measure-of store spine)
                    (measure-of store right))))))

(defn- tree-conj-right! [store root elem]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" elem
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            old-body (page-body-from-data store (node-data (lookup store right)))
            item (hash->item store elem)
            fitted (try-append old-body item elem)]
        (if fitted
          (let [new-right (make-digit-body! store fitted)]
            (make-deep! store left spine new-right
                        (measure-of store left)
                        (measure-of store spine)
                        (measure-of store new-right)))
          (let [old-pb (max 1 (lit/payload-bytes old-body))
                [spill keep] (lit/split-body-at old-body (quot (* 2 old-pb) 3))
                new-node (persist-spill! store spill)
                new-spine (if new-node
                            (tree-conj-right! store spine new-node)
                            spine)
                keep' (or (try-append keep item elem)
                          (lit/append-item keep {:type "ref" :body elem}))
                new-right (make-digit-body! store keep')]
            (make-deep! store left new-spine new-right
                        (measure-of store left)
                        (measure-of store new-spine)
                        (measure-of store new-right)))))
      (let [left (as-digit! store root)
            spine (make-empty! store)
            right (as-digit! store elem)]
        (make-deep! store left spine right
                    (measure-of store left)
                    (measure-of store spine)
                    (measure-of store right))))))

(defn- tree-to-seq*
  "Lazy sequence of leaf value hashes in order.

   Walks digits, nodes, and deep spines all the way to non-ft/* leaves.
   A single `mapcat get-children` on the spine is not enough: overflow
   can leave a mix of leaves and `ft/node` cells under a digit."
  [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" nil
      ("ft/digit" "ft/node")
      (mapcat #(tree-to-seq* store %) (get-children store root))
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (lazy-cat (tree-to-seq* store left)
                  (tree-to-seq* store spine)
                  (tree-to-seq* store right)))
      ;; bare leaf (scalar, public collection, …)
      (list root))))

(defn- scan-children [store children idx]
  (loop [cs (seq children) remaining idx]
    (let [c (first cs)
          c-count (:count (measure-of store c))]
      (if (< remaining c-count)
        (let [t (node-type (lookup store c))]
          (case t
            "ft/node" (recur (seq (get-children store c)) remaining)
            "ft/digit" (recur (seq (get-children store c)) remaining)
            ;; bare leaf
            (as-leaf-hash store c)))
        (recur (next cs) (- remaining c-count))))))

(defn- intern-u8!
  "Cache the u8 scalar for one unsigned byte and return its value hash."
  [store n]
  (let [n (bit-and 0xff (int n))
        h (u8-hash-at n)]
    (binding [store/*cache-only* true]
      (store/s-put store h ["u8" n]))
    h))

(defn- u8-page-nth
  "Value hash of the u8 at `idx` when that index lands in a u8 piece.
   Nil when the index sits at a non-u8 item, so the caller expands.
   Only the returned byte is boxed and interned."
  [store data idx]
  (when-let [body (and (not (:children data)) (:body data))]
    (loop [items (seq body) i (long idx)]
      (when-let [item (first items)]
        (if-not (lit/u8-piece? item)
          nil
          (let [c (lit/u8-piece-count item)]
            (if (< i c)
              (intern-u8! store (lit/u8-piece-nth item i))
              (recur (next items) (- i c)))))))))

(defn- tree-nth* [store root idx]
  (let [node (lookup store root)
        t (node-type node)]
    (case t
      ("ft/node" "ft/digit")
      (or (u8-page-nth store (node-data node) idx)
          (scan-children store (get-children store root) idx))
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-count (:count (measure-of store left))]
        (if (< idx left-count)
          (tree-nth* store left idx)
          (let [spine-count (:count (measure-of store spine))
                spine-idx (- idx left-count)]
            (if (< spine-idx spine-count)
              (tree-nth* store spine spine-idx)
              (tree-nth* store right (- spine-idx spine-count))))))
      ;; bare leaf as 1-element tree root
      (if (zero? idx)
        (as-leaf-hash store root)
        (throw (ex-info "Index out of range for leaf root"
                        {:index idx :type t}))))))

;; =============================================================================
;; Remove at index (structural)
;; =============================================================================

(declare tree-remove-nth*)

(defn- remove-at-children!
  "Remove the leaf at local index idx from a vector of child node hashes.
   Returns a vector of remaining/replaced child hashes (may be empty)."
  [store children idx]
  (loop [i 0 remaining idx]
    (when (>= i (count children))
      (throw (ex-info "Index out of range while removing from children"
                      {:index idx :child-count (count children)})))
    (let [c (nth children i)
          c-count (:count (measure-of store c))]
      (if (< remaining c-count)
        (let [c' (tree-remove-nth* store c remaining)
              left (subvec children 0 i)
              right (subvec children (inc i))]
          (if c'
            (into (conj left c') right)
            (into left right)))
        (recur (inc i) (- remaining c-count))))))

(defn- tree-remove-nth*
  "Remove the leaf at idx under root. Returns the new root hash, or nil when
   the subtree becomes empty (caller promotes or rebalances)."
  [store root idx]
  (let [node (lookup store root)
        t (node-type node)]
    (case t
      "ft/empty"
      (throw (ex-info "Cannot remove from empty tree" {:index idx}))

      "ft/digit"
      (let [nc (remove-at-children! store (get-children store root) idx)]
        (when (seq nc)
          (make-digit! store nc (mapv #(measure-of store %) nc))))

      "ft/node"
      (let [nc (remove-at-children! store (get-children store root) idx)]
        (case (count nc)
          0 nil
          ;; Nodes require 2–32 children; promote a lone survivor.
          1 (first nc)
          (make-node! store nc (mapv #(measure-of store %) nc))))

      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-m (measure-of store left)
            spine-m (measure-of store spine)
            right-m (measure-of store right)
            left-count (:count left-m)
            spine-count (:count spine-m)]
        (cond
          (< idx left-count)
          (let [nc (remove-at-children! store (get-children store left) idx)]
            (if (seq nc)
              (let [new-left (make-digit! store nc (mapv #(measure-of store %) nc))]
                (make-deep! store new-left spine right
                            (measure-of store new-left) spine-m right-m))
              (if (empty-node? store spine)
                (to-tree-from-digit! store right)
                (let [spine-first (tree-first* store spine)
                      new-spine (tree-rest* store spine)
                      nch (get-children store spine-first)
                      new-left' (make-digit! store nch
                                             (mapv #(measure-of store %) nch))]
                  (make-deep! store new-left' new-spine right
                              (measure-of store new-left')
                              (measure-of store new-spine)
                              right-m)))))

          (< idx (+ left-count spine-count))
          (let [spine-idx (- idx left-count)
                new-spine (tree-remove-nth* store spine spine-idx)]
            (if new-spine
              (make-deep! store left new-spine right
                          left-m (measure-of store new-spine) right-m)
              (make-deep! store left (make-empty! store) right
                          left-m measure-identity right-m)))

          :else
          (let [right-idx (- idx left-count spine-count)
                nc (remove-at-children! store (get-children store right) right-idx)]
            (if (seq nc)
              (let [new-right (make-digit! store nc (mapv #(measure-of store %) nc))]
                (make-deep! store left spine new-right
                            left-m spine-m (measure-of store new-right)))
              (if (empty-node? store spine)
                (to-tree-from-digit! store left)
                (let [spine-last (tree-last* store spine)
                      new-spine (tree-butlast* store spine)
                      nch (get-children store spine-last)
                      new-right' (make-digit! store nch
                                              (mapv #(measure-of store %) nch))]
                  (make-deep! store left new-spine new-right'
                              left-m
                              (measure-of store new-spine)
                              (measure-of store new-right'))))))))

      ;; bare leaf as 1-element tree root
      (if (zero? idx)
        nil
        (throw (ex-info "Index out of range for leaf root"
                        {:index idx :type t}))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ft-empty
  "Create an empty finger tree in the store. Returns its root hash."
  [store]
  (make-empty! store))

(defn ft-conj-right
  "Append a value (by its hash, already in the store) to the right end.
   Returns the new root hash. Stores the leaf hash directly (implicit single).
   value-hash must be a non-ft/* leaf (scalar or public collection)."
  [store root value-hash]
  (assert-leaf-value! store value-hash)
  (tree-conj-right! store root value-hash))

(defn ft-conj-left
  "Prepend a value (by its hash, already in the store) to the left end.
   Returns the new root hash. Stores the leaf hash directly (implicit single).
   value-hash must be a non-ft/* leaf (scalar or public collection)."
  [store root value-hash]
  (assert-leaf-value! store value-hash)
  (tree-conj-left! store root value-hash))

(defn ft-first
  "Hash of the first element, or nil if empty."
  [store root]
  (when-let [s (tree-first* store root)]
    (let [t (node-type (lookup store s))]
      (if (ft-type? t)
        (ft-first store s)
        (as-leaf-hash store s)))))

(defn ft-last
  "Hash of the last element, or nil if empty."
  [store root]
  (when-let [s (tree-last* store root)]
    (let [t (node-type (lookup store s))]
      (if (ft-type? t)
        (ft-last store s)
        (as-leaf-hash store s)))))

(defn ft-rest
  "Remove the first element. Returns the new root hash."
  [store root]
  (tree-rest* store root))

(defn ft-butlast
  "Remove the last element. Returns the new root hash."
  [store root]
  (tree-butlast* store root))

(defn ft-empty? [store root]
  (let [entry (lookup store root)]
    (and entry (= "ft/empty" (node-type entry)))))

(defn ft-measure [store root]
  (measure-of store root))

(defn ft-count
  "Number of elements, O(1) via cached measure (or synthesized for bare leaf roots)."
  [store root]
  (:count (measure-of store root)))

(defn ft-size-bytes
  "Total leaf byte size, O(1) via cached measure (or synthesized for bare leaf roots)."
  [store root]
  (:size-bytes (measure-of store root)))

(defn ft-elements-fuse
  "The seq's data hash: the running fuse of all element hashes, O(1)."
  [store root]
  (:elements-fuse (measure-of store root)))

(defn ft-nth
  "Hash of the element at idx (0-indexed), O(log n). Throws if out of range."
  [store root idx]
  (let [cnt (:count (measure-of store root))]
    (when (or (neg? idx) (>= idx cnt))
      (throw (ex-info (str "Index " idx " out of bounds for count " cnt)
                      {:index idx :count cnt})))
    (tree-nth* store root idx)))

(defn ft-remove-nth
  "Remove the element at idx (0-indexed). Returns the new root hash.
   Structural O(log n) update. Throws if out of range."
  [store root idx]
  (let [cnt (:count (measure-of store root))]
    (when (or (neg? idx) (>= idx cnt))
      (throw (ex-info (str "Index " idx " out of bounds for count " cnt)
                      {:index idx :count cnt})))
    (or (tree-remove-nth* store root idx)
        (make-empty! store))))

(defn ft-seq
  "Lazy sequence of element value hashes under a tree root
   (empty / bare leaf / deep)."
  [store root]
  (map #(as-leaf-hash store %) (tree-to-seq* store root)))

(defn ft-leaves
  "Ordered leaf value hashes under any FT node type (including bare digit/node)
   or a bare leaf hash (implicit single).

   Unlike ft-seq (tree roots only), this walks digit and node cells so pack
   intermediate literals can realize their full leaf payload."
  [store h]
  (let [node (lookup store h)
        t (node-type node)]
    (case t
      "ft/empty" []
      "ft/digit" (mapcat #(ft-leaves store %) (page-child-hashes store (node-data node)))
      "ft/node" (mapcat #(ft-leaves store %) (page-child-hashes store (node-data node)))
      "ft/deep" (ft-seq store h)
      ;; non-ft/*: already a leaf value
      [h])))

(defn- bset-u8 [dest i v]
  #?(:clj (aset-byte ^bytes dest (int i) (unchecked-byte (bit-and 0xff (int v))))
     :cljs (aset dest i (bit-and 0xff (int v)))))

(defn- copy-u8-into
  "Copy `m` bytes from `src` at `src-from` into `dest` at `dest-at`."
  [src src-from dest dest-at m]
  #?(:clj
     (if (bytes? src)
       (System/arraycopy ^bytes src (int src-from) ^bytes dest (int dest-at) (int m))
       (dotimes [j m]
         (bset-u8 dest (+ dest-at j) (lit/u8-nth src (+ src-from j)))))
     :cljs
     (if (instance? js/Uint8Array src)
       (.set dest (.subarray src src-from (+ src-from m)) dest-at)
       (dotimes [j m]
         (bset-u8 dest (+ dest-at j) (lit/u8-nth src (+ src-from j)))))))

(declare export-u8-node)

(defn- export-u8-body
  "Copy u8 leaves of a page body into `dest`. Returns the next index, or
   nil when an item is not u8 payload or a ref to more of the same."
  [store body dest at limit]
  (loop [items (seq body) at at]
    (cond
      (nil? at) nil
      (>= at limit) at
      (nil? items) at
      :else
      (let [item (first items)
            t (str (:type item))
            b (:body item)
            next-at
            (cond
              (lit/u8-piece? item)
              (let [c (lit/u8-piece-count item)
                    m (min c (- limit at))]
                (case t
                  "u8"
                  (do (bset-u8 dest at b) (inc at))

                  "repeat"
                  (do (dotimes [j m]
                        (bset-u8 dest (+ at j) (:value b)))
                      (+ at m))

                  (do (copy-u8-into (:values b) 0 dest at m)
                      (+ at m))))

              (= t "ref")
              (export-u8-node store b dest at limit)

              (and (= t "run") (= "ref" (str (:of b))))
              (loop [hs (seq (:values b)) at at]
                (cond
                  (or (nil? at) (>= at limit) (nil? hs)) at
                  :else (recur (next hs)
                               (export-u8-node store (first hs) dest at limit))))

              :else nil)]
        (recur (next items) next-at)))))

(defn- export-u8-node
  [store h dest at limit]
  (if (or (nil? at) (>= at limit))
    at
    (let [node (lookup store h)
          t (node-type node)
          data (node-data node)]
      (case t
        "ft/empty" at
        ("ft/digit" "ft/node")
        (if-let [ch (:children data)]
          (loop [hs (seq ch) at at]
            (cond
              (or (nil? at) (>= at limit) (nil? hs)) at
              :else (recur (next hs)
                           (export-u8-node store (first hs) dest at limit))))
          (export-u8-body store (:body data) dest at limit))
        "ft/deep"
        (let [{:keys [left spine right]} data]
          (when-let [a (export-u8-node store left dest at limit)]
            (when-let [b (export-u8-node store spine dest a limit)]
              (export-u8-node store right dest b limit))))
        (if (= t "u8")
          (do (bset-u8 dest at data)
              (inc at))
          nil)))))

(defn ft-export-u8
  "Copy `n` u8 leaves under `root` into a fresh byte buffer.
   Returns nil when the tree is not a u8 page tree (caller can walk
   leaves instead). Empty `n` is an empty buffer."
  [store root n]
  (let [n (long n)
        dest #?(:clj (byte-array (int n))
                :cljs (js/Uint8Array. n))
        wrote (if (or (zero? n) (nil? root))
                0
                (export-u8-node store root dest 0 n))]
    (when (= wrote n)
      dest)))

(defn- as-node!
  "Ensure `h` is an ft/node page (spine element). A digit is re-wrapped."
  [store h]
  (let [node (lookup store h)
        t (node-type node)]
    (case t
      "ft/node" h
      "ft/digit" (make-node-body! store (page-body-from-data store (node-data node)))
      (make-node! store [h] [(measure-of store h)]))))

(defn ft-from-run
  "Build a finger-tree root from a homogeneous scalar run, packed into
   1k pages in one write per page (no per-element conj history).

   Pages are assembled as Deep(first, spine-of-middles, last) so overflow
   never mixes a char run with a ref to another page."
  [store type-name values]
  (let [type-name (str type-name)
        chunks (lit/chunk-run type-name values lit/page-budget)
        pages (mapv #(make-digit-body! store [%]) chunks)
        n (count pages)]
    (cond
      (zero? n) (ft-empty store)
      (= 1 n) (first pages)
      :else
      (let [left (first pages)
            right (peek pages)
            mids (mapv #(as-node! store %) (subvec pages 1 (dec n)))
            spine (reduce (fn [root nh] (tree-conj-right! store root nh))
                          (make-empty! store)
                          mids)]
        (make-deep! store left spine right
                    (measure-of store left)
                    (measure-of store spine)
                    (measure-of store right))))))

(defn ft-from-value-hashes
  "Build a finger-tree root by conj-right of the given leaf value hashes
   (already in store). Same construction path as sequence collections.

   Used for intermediate ft/deep (and similar) packing: the resulting root
   hash is fuse(type, elements_fuse) and matches a sender node when types
   and leaf multiset agree."
  [store value-hashes]
  (reduce (fn [root vh] (ft-conj-right store root vh))
          (ft-empty store)
          value-hashes))

(defn ft-digit-from-value-hashes
  "Build an ft/digit whose children are bare leaf value hashes."
  [store value-hashes]
  (let [vhs (vec value-hashes)]
    (doseq [vh vhs] (assert-leaf-value! store vh))
    (make-digit! store vhs (mapv #(measure-of store %) vhs))))

(defn ft-node-from-value-hashes
  "Build an ft/node page whose logical children are these leaf hashes.

   Hash is fuse(type, elements_fuse), so a bottom-level node of char/byte
   leaves round-trips as a pack literal. conj-right (ft-from-value-hashes)
   rebuilds a deep/digit spine instead and fails the dry-run."
  [store value-hashes]
  (let [vhs (vec value-hashes)
        n (count vhs)]
    (when-not (<= 1 n)
      (throw (ex-info "ft/node literal needs at least one leaf" {:count n})))
    (doseq [vh vhs] (assert-leaf-value! store vh))
    (make-node! store vhs (mapv #(measure-of store %) vhs))))

(defn ft-concat
  "Concatenate two trees in the same store. Returns the new root hash."
  [store root-a root-b]
  (reduce (fn [h elem]
            (ft-conj-right store h (as-leaf-hash store elem)))
          root-a
          (tree-to-seq* store root-b)))

;; =============================================================================
;; child-hashes implementations for finger tree node types
;; =============================================================================

(defmethod types/child-hashes "ft/empty" [_] [])

(defmethod types/child-hashes "ft/digit" [[_ data]]
  (page-refs data))

(defmethod types/child-hashes "ft/node" [[_ data]]
  (page-refs data))

(defmethod types/child-hashes "ft/deep" [[_ data]]
  [(:left data) (:spine data) (:right data)])
