(ns dacite.store.file
  "Filesystem content store for the JVM and babashka (java.io only).

   Same on-disk layout as dacite.store.nbb/FileStore:
     {base}/{hex[0:2]}/{hex[2:4]}/{hex}.edn

   Pair with dacite.rooted/file-root-cell at the same base path.
   LMDB lives in dacite.store.jvm — keep this ns free of native deps so
   babashka can load it."
  (:require [dacite.store :as store]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io File]))

;; nbb writes 64-bit words as #dacite/u64 "…"; accept them so a store
;; directory can be opened from either host (unsigned → signed long bits).
(def ^:private edn-opts
  {:readers {'dacite/u64 (fn [s] (Long/parseUnsignedLong (str s)))}})

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

(defrecord FileStore [base-dir]
  store/IStore
  (s-get [_ h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (read-edn (slurp f)))))

  (s-put [this h value]
    (let [f (hash->path base-dir h)]
      (ensure-parent-dirs f)
      (spit f (pr-str value))
      this))

  (s-has? [_ h]
    (.exists (hash->path base-dir h)))

  (s-delete [this h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (.delete f)))
    this)

  (s-snapshot [_]
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
            files)))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (doseq [^File f (file-seq base-dir)
            :when (and (.isFile f) (.endsWith (.getName f) ".edn"))]
      (.delete f))
    this))

(defn file-store
  "Create a file-based content-addressed store."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (->FileStore dir)))
