(ns dacite.store.nbb
  "Node.js file-backed content store for nbb (SCI on Node).

   Same on-disk layout as dacite.store.jvm/FileStore:
     {base}/{hex[0:2]}/{hex[2:4]}/{hex}.edn

   Pair with dacite.rooted/file-root-cell at the same base path for a
   durable rooted store under nbb. Not part of the pure portable core —
   host durability glue, analogous to store.jvm on the JVM.

   Store entries nest 64-bit hash words. On nbb those words are BigInt;
   pr-str would emit unreadable #object[BigInt …], so we encode them as
   #dacite/u64 \"…\" tagged literals for EDN round-trip."
  (:require [clojure.edn :as edn]
            [dacite.store :as store]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- bigint? [x]
  (= (type x) js/BigInt))

(defn- encode-value
  "Walk a store value, wrapping BigInts as #dacite/u64 tagged literals."
  [x]
  (cond
    (bigint? x) (tagged-literal 'dacite/u64 (str x))
    (vector? x) (mapv encode-value x)
    (map? x) (into {} (map (fn [[k v]] [(encode-value k) (encode-value v)]) x))
    (set? x) (into #{} (map encode-value) x)
    (seq? x) (doall (map encode-value x))
    :else x))

(defn- decode-value
  "Walk a store value after EDN read (tagged literals already expanded)."
  [x]
  (cond
    (vector? x) (mapv decode-value x)
    (map? x) (into {} (map (fn [[k v]] [(decode-value k) (decode-value v)]) x))
    (set? x) (into #{} (map decode-value) x)
    (seq? x) (doall (map decode-value x))
    :else x))

(def ^:private edn-readers
  {'dacite/u64 (fn [s] (js/BigInt s))})

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
    (let [p (hash->path base h)]
      (when (.existsSync fs p)
        (read-edn p))))

  (s-put [this h value]
    (let [p (hash->path base h)]
      (ensure-parent-dirs! p)
      (write-edn p value)
      this))

  (s-has? [_ h]
    (.existsSync fs (hash->path base h)))

  (s-delete [this h]
    (let [p (hash->path base h)]
      (when (.existsSync fs p)
        (.unlinkSync fs p)))
    this)

  (s-snapshot [_]
    (into {}
          (map (fn [p]
                 (let [name (.basename path p)
                       hex (.substring name 0 64)
                       h (store/hex->hash hex)
                       v (read-edn p)]
                   [h v])))
          (walk-edn-files (str base))))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (doseq [p (walk-edn-files (str base))]
      (.unlinkSync fs p))
    this))

(defn file-store
  "Create a Node file-backed content-addressed store at path."
  [dir]
  (let [base (str dir)]
    (when-not (.existsSync fs base)
      (.mkdirSync fs base #js {:recursive true}))
    (->FileStore base)))
