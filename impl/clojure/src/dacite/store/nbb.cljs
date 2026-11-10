(ns dacite.store.nbb
  "Node.js file-backed content store for nbb (SCI on Node).

   Same on-disk layout as dacite.store.jvm/FileStore:
     {base}/{hex[0:2]}/{hex[2:4]}/{hex}.edn

   Store format tag: the file {base}/FORMAT holds `store/store-format` in
   decimal, as in dacite.store.file. Written when the directory is new or has
   no nodes, checked on open (`:store-format-mismatch`).

   Pair with dacite.rooted/file-root-cell at the same base path for a
   durable rooted store under nbb. Not part of the pure portable core —
   host durability glue, analogous to store.jvm on the JVM.

   Store entries nest 64-bit hash words. On nbb those words are BigInt;
   pr-str would emit unreadable #object[BigInt …], so we encode them as
   #dacite/u64 \"…\" tagged literals for EDN round-trip."
  (:require [clojure.edn :as edn]
            [dacite.store :as store]
            [dacite.store.bytes :as bytes]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- bigint? [x]
  (= (type x) js/BigInt))

(defn- u8-array? [x]
  (instance? js/Uint8Array x))

(defn- encode-value
  "Walk a store value, wrapping BigInts as #dacite/u64 and byte buffers
   as #dacite/bytes."
  [x]
  (cond
    (bigint? x) (tagged-literal 'dacite/u64 (str x))
    (u8-array? x)
    (tagged-literal 'dacite/bytes (mapv #(aget x %) (range (.-length x))))
    (vector? x) (mapv encode-value x)
    (map? x) (into {} (map (fn [[k v]] [(encode-value k) (encode-value v)]) x))
    (set? x) (into #{} (map encode-value) x)
    (seq? x) (doall (map encode-value x))
    :else x))

(defn- decode-value
  "Walk a store value after EDN read (tagged literals already expanded)."
  [x]
  (cond
    (u8-array? x) x
    (vector? x) (mapv decode-value x)
    (map? x) (into {} (map (fn [[k v]] [(decode-value k) (decode-value v)]) x))
    (set? x) (into #{} (map decode-value) x)
    (seq? x) (doall (map decode-value x))
    :else x))

(def ^:private edn-readers
  {'dacite/u64 (fn [s] (js/BigInt s))
   'dacite/bytes (fn [xs]
                   (let [out (js/Uint8Array. (count xs))]
                     (dotimes [i (count xs)]
                       (aset out i (bit-and 0xff (nth xs i))))
                     out))})

(defn- write-edn [file-path value]
  (.writeFileSync fs file-path (pr-str (encode-value value)) "utf8"))

(defn- read-edn [file-path]
  (decode-value
   (edn/read-string {:readers edn-readers}
                    (.readFileSync fs file-path "utf8"))))

(defn- hash->path
  "Sharded file path for a hash under base."
  [base h]
  (let [hex (store/hash->hex h)
        dir1 (.substring hex 0 2)
        dir2 (.substring hex 2 4)
        filename (str hex ".edn")]
    (.join path (str base) dir1 dir2 filename)))

(defn- ensure-parent-dirs! [file-path]
  (let [parent (.dirname path file-path)]
    (when-not (.existsSync fs parent)
      (.mkdirSync fs parent #js {:recursive true}))))

(defn- walk-edn-files
  "Return a seq of absolute paths to .edn files under dir."
  [dir]
  (if-not (.existsSync fs dir)
    []
    (letfn [(walk [d]
              (mapcat
               (fn [name]
                 (let [p (.join path d name)
                       st (.statSync fs p)]
                   (cond
                     (.isDirectory st) (walk p)
                     (and (.isFile st) (.endsWith name ".edn")) [p]
                     :else [])))
               (vec (.readdirSync fs d))))]
      (walk dir))))

(defrecord FileStore [base]
  store/IStore
  (s-get [_ h]
    (store/completed
     (let [p (hash->path base h)]
       (when (.existsSync fs p)
         (read-edn p)))))

  (s-put [this h value]
    (let [p (hash->path base h)]
      (ensure-parent-dirs! p)
      (write-edn p value)
      (store/completed this)))

  (s-has? [_ h]
    (store/completed (.existsSync fs (hash->path base h))))

  (s-delete [this h]
    (let [p (hash->path base h)]
      (when (.existsSync fs p)
        (.unlinkSync fs p)))
    (store/completed this))

  (s-reset [this]
    (doseq [p (walk-edn-files (str base))]
      (.unlinkSync fs p))
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    ;; Filenames are the hashes. Node files that are kept are not read.
    (let [removed (volatile! 0)]
      (doseq [p (walk-edn-files (str base))
              :let [hex (.substring (.basename path p) 0 64)]
              :when (not (contains? live hex))]
        (.unlinkSync fs p)
        (vswap! removed inc))
      (store/completed {:removed @removed})))

  store/ILocalStats
  (local-stats [this]
    (store/completed
     (reduce (fn [acc p]
               (let [hex (.substring (.basename path p) 0 64)
                     v (store/await (store/s-get this (store/hex->hash hex)))]
                 (-> acc
                     (update :entries inc)
                     (update :edn-bytes + (bytes/edn-bytes v))
                     (update :wire-bytes + (bytes/wire-bytes v)))))
             {:entries 0 :edn-bytes 0 :wire-bytes 0}
             (walk-edn-files (str base))))))

(defn- check-format!
  "Tag a new (no nodes) store directory; check an existing one."
  [base]
  (let [tag-path (.join path base "FORMAT")]
    (if (.existsSync fs tag-path)
      (let [s (.trim (.readFileSync fs tag-path "utf8"))
            n (js/parseInt s 10)]
        (store/check-store-format! base (if (and (not (js/isNaN n)) (= s (str n))) n s)))
      (if (seq (walk-edn-files base))
        (throw (store/store-format-mismatch base nil))
        (.writeFileSync fs tag-path (str store/store-format "\n") "utf8")))))

(defn file-store
  "Create a Node file-backed content-addressed store at path."
  [dir]
  (let [base (str dir)]
    (when-not (.existsSync fs base)
      (.mkdirSync fs base #js {:recursive true}))
    (check-format! base)
    (->FileStore base)))
