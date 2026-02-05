(ns dacite.store
  "Content-addressed storage for Dacite values.
   
   Stores nodes by their 256-bit hash on the filesystem.
   Uses a two-level directory structure to avoid too many files per directory:
   
   store-path/
     ab/
       cd/
         abcd1234...5678.dat
   
   Where ab and cd are the first two bytes of the hash in hex."
  (:require [dacite.hash :as hash]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io File]))

;; =============================================================================
;; Hash utilities (re-exported from dacite.hash)
;; =============================================================================

(def hash->hex
  "Convert hash (4 longs or 32 bytes) to 64-char hex string."
  hash/hash->hex)

(def hex->hash
  "Convert 64-char hex string to hash (vector of 4 longs)."
  hash/hex->hash)

;; =============================================================================
;; Store protocol
;; =============================================================================

(defprotocol Store
  "Protocol for content-addressed storage."
  (store-get [this hash] "Retrieve value by hash. Returns nil if not found.")
  (store-put [this hash value] "Store value at hash. Returns hash.")
  (store-has? [this hash] "Check if hash exists.")
  (store-delete [this hash] "Delete value at hash.")
  (store-list [this] "List all hashes in store."))

;; =============================================================================
;; Filesystem store
;; =============================================================================

(defn- hash->path
  "Convert hash to file path with directory sharding."
  [^File base-dir h]
  (let [hex (hash->hex h)
        dir1 (subs hex 0 2)
        dir2 (subs hex 2 4)
        filename (str hex ".edn")]
    (io/file base-dir dir1 dir2 filename)))

(defn- ensure-parent-dirs
  "Create parent directories if they don't exist."
  [^File f]
  (let [parent (.getParentFile f)]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defrecord FileStore [base-dir]
  Store
  (store-get [_ h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (edn/read-string (slurp f)))))

  (store-put [_ h value]
    (let [f (hash->path base-dir h)]
      (ensure-parent-dirs f)
      (spit f (pr-str value))
      h))

  (store-has? [_ h]
    (.exists (hash->path base-dir h)))

  (store-delete [_ h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (.delete f))))

  (store-list [_]
    (let [files (file-seq base-dir)]
      (->> files
           (filter #(.isFile ^File %))
           (filter #(.endsWith (.getName ^File %) ".edn"))
           (map #(let [name (.getName ^File %)]
                   (hex->hash (subs name 0 64))))))))

(defn file-store
  "Create a file-based content-addressed store."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (->FileStore dir)))

;; =============================================================================
;; In-memory store (for testing)
;; =============================================================================

(defrecord MemStore [data]
  ;; data is an atom containing {hash-vec -> value}
  Store
  (store-get [_ h]
    (get @data (vec h)))

  (store-put [_ h value]
    (swap! data assoc (vec h) value)
    h)

  (store-has? [_ h]
    (contains? @data (vec h)))

  (store-delete [_ h]
    (swap! data dissoc (vec h)))

  (store-list [_]
    (keys @data)))

(defn mem-store
  "Create an in-memory content-addressed store."
  []
  (->MemStore (atom {})))

;; =============================================================================
;; Store operations for Dacite values
;; =============================================================================

(defn put-value
  "Store a Dacite value and return its hash.
   The value should be a map with :type and :data keys."
  [store value]
  (let [;; Compute hash of value
        data-bytes (.getBytes (pr-str value) "UTF-8")
        type-name (or (:type value) "dacite.core/unknown")
        type-hash (hash/sha256-str type-name)
        data-hash (hash/sha256 data-bytes)
        value-hash (hash/fuse type-hash data-hash)
        hash-longs (hash/bytes->longs value-hash)]
    (store-put store hash-longs value)
    hash-longs))

(defn get-value
  "Retrieve a Dacite value by its hash."
  [store hash-longs]
  (store-get store hash-longs))

;; =============================================================================
;; Recursive storage for trees
;; =============================================================================

(defn store-tree!
  "Recursively store a tree structure.
   The tree should have :children (hashes or subtrees) and :data.
   Returns the root hash."
  [store tree]
  (let [;; First store all children that are subtrees (not already hashes)
        children-with-hashes
        (when (:children tree)
          (mapv (fn [child]
                  (if (and (vector? child) (= 4 (count child)) (every? number? child))
                    child  ;; Already a hash
                    (store-tree! store child)))  ;; Store subtree, get hash
                (:children tree)))

        ;; Create the storable version with children as hashes
        storable (if children-with-hashes
                   (assoc tree :children children-with-hashes)
                   tree)]

    ;; Store and return hash
    (put-value store storable)))

(defn fetch-tree
  "Fetch a tree, optionally expanding children up to a depth.
   With depth=0, returns just the node (children stay as hashes).
   With depth=1, fetches one level of children (their children stay as hashes).
   With depth=-1 (or nil), fetches everything recursively."
  ([store root-hash] (fetch-tree store root-hash -1))
  ([store root-hash depth]
   (when-let [node (get-value store root-hash)]
     (if (or (= depth 0) (nil? (:children node)))
       node
       (let [expanded-children
             (mapv (fn [child-hash]
                     (let [child-node (get-value store child-hash)]
                       (if (and child-node
                                (:children child-node)
                                (or (neg? depth) (> depth 1)))
                         ;; Recurse into this child's children
                         (fetch-tree store child-hash
                                     (if (neg? depth) -1 (dec depth)))
                         ;; Just return the child node (or nil if not found)
                         child-node)))
                   (:children node))]
         (assoc node :children expanded-children))))))

(comment
  ;; Example usage
  (def store (mem-store))

  ;; Store some values
  (def h1 (put-value store {:type "dacite.core/i64" :data 42}))
  (def h2 (put-value store {:type "dacite.core/string" :data "hello"}))

  ;; Retrieve
  (get-value store h1)  ;; => {:type "dacite.core/i64", :data 42}

  ;; Store a tree
  (def tree {:type "dacite.core/vector"
             :measure {:count 2 :size-bytes 16}
             :children [{:type "dacite.core/i64" :data 1}
                        {:type "dacite.core/i64" :data 2}]})

  (def root (store-tree! store tree))

  ;; Fetch with different depths
  (fetch-tree store root 0)   ;; Just the root
  (fetch-tree store root 1)   ;; Root + immediate children
  (fetch-tree store root)     ;; Everything

  ;; File store
  (def fs (file-store "/tmp/dacite-store"))
  (put-value fs {:type "test" :data "persistent"}))
