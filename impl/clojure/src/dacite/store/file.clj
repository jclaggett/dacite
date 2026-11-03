(ns dacite.store.file
  "Filesystem content store for the JVM and babashka (java.io only).

   Same on-disk layout as dacite.store.nbb/FileStore:
     {base}/{hex[0:2]}/{hex[2:4]}/{hex}.edn

   The store format tag (`store/store-format`) is the file `{base}/FORMAT`:
   the integer in decimal. It is written when a store is created (an empty or
   new directory) and checked on every open. A directory with `.edn` nodes and
   no tag, or another tag, is `:store-format-mismatch`.

   Pair with dacite.rooted/file-root-cell at the same base path.
   LMDB lives in dacite.store.jvm — keep this ns free of native deps so
   babashka can load it."
  (:require [dacite.store :as store]
            [dacite.wire :as wire]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

;; nbb writes 64-bit words as #dacite/u64 "…"; accept them so a store
;; directory can be opened from either host (unsigned → signed long bits).
(def ^:private edn-opts
  {:readers {'dacite/u64 (fn [s] (Long/parseUnsignedLong (str s)))
             'dacite/bytes wire/bytes-from-edn}})

(defn- tag-byte-arrays
  "Replace byte arrays with `#dacite/bytes` so `pr-str` round-trips.
   Page bodies keep the arrays in memory; only the EDN file sees the vector."
  [x]
  (cond
    (bytes? x) (tagged-literal 'dacite/bytes (wire/bytes->vec x))
    (map? x) (reduce-kv (fn [m k v]
                          (assoc m (tag-byte-arrays k) (tag-byte-arrays v)))
                        {}
                        x)
    (vector? x) (mapv tag-byte-arrays x)
    (set? x) (into #{} (map tag-byte-arrays) x)
    (seq? x) (doall (map tag-byte-arrays x))
    :else x))

(defn- read-edn [s]
  (edn/read-string edn-opts s))

(defn- hash->path
  "Convert hash to file path with directory sharding."
  [^File base-dir h]
  (let [hex (store/hash->hex h)
        dir1 (subs hex 0 2)
        dir2 (subs hex 2 4)
        filename (str hex ".edn")]
    (io/file base-dir dir1 dir2 filename)))

(defn- ensure-parent-dirs [^File f]
  (let [parent (.getParentFile f)]
    (when-not (.exists parent)
      (.mkdirs parent))))

(def ^:private format-file-name "FORMAT")

(defn- check-format!
  "Tag a new (empty) store directory; check an existing one."
  [^File dir]
  (let [tag-file (io/file dir format-file-name)]
    (if (.exists tag-file)
      (let [s (str/trim (slurp tag-file))
            found (try (Long/parseLong s) (catch NumberFormatException _ s))]
        (store/check-store-format! (.getPath dir) found))
      (if (some #(and (.isFile ^File %) (.endsWith (.getName ^File %) ".edn"))
                (file-seq dir))
        (throw (store/store-format-mismatch (.getPath dir) nil))
        (spit tag-file (str store/store-format "\n"))))))

(defrecord FileStore [base-dir]
  store/IStore
  (s-get [_ h]
    (store/completed
     (let [f (hash->path base-dir h)]
       (when (.exists f)
         (read-edn (slurp f))))))

  (s-put [this h value]
    (let [f (hash->path base-dir h)]
      (ensure-parent-dirs f)
      (spit f (pr-str (tag-byte-arrays value)))
      (store/completed this)))

  (s-has? [_ h]
    (store/completed (.exists (hash->path base-dir h))))

  (s-delete [this h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (.delete f)))
    (store/completed this))

  (s-snapshot [_]
    (store/completed
     (let [files (file-seq base-dir)]
       (into {}
             (comp
              (filter #(.isFile ^File %))
              (filter #(.endsWith (.getName ^File %) ".edn"))
              (map (fn [^File f]
                     (let [hex (subs (.getName f) 0 64)
                           h (store/hex->hash hex)
                           v (read-edn (slurp f))]
                       [h v]))))
             files))))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/await (store/s-put this h v)))
    (store/completed this))

  (s-reset [this]
    (doseq [^File f (file-seq base-dir)
            :when (and (.isFile f) (.endsWith (.getName f) ".edn"))]
      (.delete f))
    (store/completed this)))

(defn file-store
  "Create a file-based content-addressed store."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (check-format! dir)
    (->FileStore dir)))
