(ns dacite.store-checks
  "Content-addressed storage checks that run on the JVM and on nbb.

   These were `dacite.store-test`, JVM only. Mem and layered stores are
   portable. The file and LMDB stores are host code, so each host passes
   `env`, a map of the stores and the few file and LMDB calls a check needs:

     :file-store :lmdb-store :lmdb-close :write-batch :lmdb-root-cell
     :join :exists? :dir? :rm-rf :tmp-dir
     :lmdb-raw   (fn [st h]) the raw bytes under `h` in the content DB, or nil
     :to-ubytes  (fn [bytes]) host bytes as a vector of unsigned ints

   On the JVM that is `dacite.store.jvm` and `dacite.store.file`
   (`dacite.store-test`). On nbb it is `dacite.store.nbb` and
   `dacite.store.nbb.lmdb` (`dacite.store.storage-cljs-test`,
   `npm run storage:test`).

   Each check is `(f env dir)`: `dir` is a new temp directory that the
   runner deletes afterward. A check returns a vector of problem strings,
   empty when it holds. A check takes milliseconds.

   A hash read back from LMDB on nbb is four unsigned BigInt words, and on
   the JVM four signed longs. `canon` writes every four-word vector as its
   hex on both sides of a compare, so a compare is still exact about the
   bits. A fuse state written to LMDB must be BigInt words on nbb (`words`)."
  (:require [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value.collections :as coll]
            [dacite.value.scalar :as scalar]
            [dacite.value.types :as types]
            #?(:cljs [dacite.value :as v])
            [dacite.wire.binary :as bin]))

(defn- diff
  [label expected actual]
  (when-not (= expected actual)
    [(str label ": expected " (pr-str expected) ", got " (pr-str actual))]))

(defn- word?
  [x]
  #?(:clj (integer? x)
     :cljs (or (integer? x) (= (type x) js/BigInt))))

(defn- canon
  "`x` with every four-word vector written as its hash hex."
  [x]
  (cond
    (and (vector? x) (= 4 (count x)) (every? word? x)) (store/hash->hex x)
    (vector? x) (mapv canon x)
    (map? x) (into {} (map (fn [[k v]] [k (canon v)])) x)
    :else x))

(defn- words
  "Host words for a literal: longs on the JVM, BigInts on nbb."
  [xs]
  #?(:clj (vec xs) :cljs (mapv #(js/BigInt %) xs)))

(defn- u64s
  "The words a hash reads back as: signed longs on the JVM, unsigned on nbb."
  [h]
  #?(:clj h :cljs (mapv #(.asUintN js/BigInt 64 (js/BigInt %)) h)))

(defn- get* [s h] (store/await (store/s-get s h)))
(defn- has* [s h] (store/await (store/s-has? s h)))
(defn- put* [s h v] (store/await (store/s-put s h v)))
(defn- entries [s] (:entries (store/await (store/local-stats s))))

(defn- with-lmdb
  "`(f st)` on an LMDB store at `path`, closed after."
  [env path f]
  (let [st ((:lmdb-store env) path)]
    (try (f st)
         (finally ((:lmdb-close env) st)))))

;; =============================================================================
;; Hash conversion
;; =============================================================================

(defn hash-hex-roundtrip-test
  [_ _]
  (let [h #?(:clj [1234567890123456789 -1234567890123456789 0 -1]
             :cljs [(js/BigInt "1234567890123456789")
                    (js/BigInt "-1234567890123456789") 0 -1])
        hex (store/hash->hex h)
        back (store/hex->hash hex)]
    (concat
     (diff "hex length" 64 (count hex))
     (diff "back" (u64s h) (u64s back)))))

;; =============================================================================
;; MemStore
;; =============================================================================

(defn mem-store-basic-test
  [_ _]
  (let [s (store/mem-store)
        h [1 2 3 4]
        v {:data "test"}
        before-get (get* s h)
        before-has (has* s h)]
    (put* s h v)
    (concat
     (diff "get before put" nil before-get)
     (diff "has? before put" false (boolean before-has))
     (diff "has? after put" true (has* s h))
     (diff "get after put" v (get* s h)))))

(defn mem-store-entries-test
  [_ _]
  (let [s (store/mem-store)]
    (put* s [1 2 3 4] :a)
    (put* s [5 6 7 8] :b)
    (concat
     (diff "has a" true (has* s [1 2 3 4]))
     (diff "has b" true (has* s [5 6 7 8]))
     (diff "get a" :a (get* s [1 2 3 4]))
     (diff "entries" 2 (entries s)))))

(defn mem-store-reset-test
  [_ _]
  (let [s (store/mem-store)]
    (put* s [1 2 3 4] :a)
    (store/await (store/s-reset s))
    (concat
     (diff "get after reset" nil (get* s [1 2 3 4]))
     (diff "entries after reset" 0 (entries s)))))

;; =============================================================================
;; FileStore
;; =============================================================================

(defn file-store-basic-test
  [env dir]
  (let [s ((:file-store env) dir)
        h [1 2 3 4]
        v {:data "test"}
        before-has (has* s h)]
    (put* s h v)
    (concat
     (diff "has? before put" false (boolean before-has))
     (diff "has? after put" true (has* s h))
     (diff "get after put" v (get* s h)))))

(defn file-store-persistence-test
  [env dir]
  (let [h [1 2 3 4]
        v {:data "persistent"}]
    (put* ((:file-store env) dir) h v)
    (diff "a new instance reads it" v (get* ((:file-store env) dir) h))))

(defn file-store-sharding-test
  [env dir]
  (let [s ((:file-store env) dir)
        h [1 2 3 4]]
    (put* s h {:data 123})
    (let [hex (store/hash->hex h)
          expected ((:join env) dir (subs hex 0 2) (subs hex 2 4))]
      (concat
       (diff "shard dir exists" true ((:exists? env) expected))
       (diff "shard is a directory" true ((:dir? env) expected))))))

(defn file-store-entries-test
  [env dir]
  (let [s ((:file-store env) dir)]
    (put* s [1 2 3 4] :a)
    (put* s [5 6 7 8] :b)
    (concat
     (diff "entries" 2 (entries s))
     (diff "get a" :a (get* s [1 2 3 4]))
     (diff "get b" :b (get* s [5 6 7 8])))))

(defn file-store-reset-test
  [env dir]
  (let [s ((:file-store env) dir)]
    (put* s [1 2 3 4] :a)
    (store/await (store/s-reset s))
    (diff "get after reset" nil (get* s [1 2 3 4]))))

(defn file-store-creates-dir-test
  [env dir]
  (let [path ((:join env) dir "nested" "subdir")
        s ((:file-store env) path)
        made ((:exists? env) path)]
    (put* s [1 2 3 4] :v)
    (concat
     (diff "dir made" true made)
     (diff "get" :v (get* s [1 2 3 4])))))

;; =============================================================================
;; LayeredStore
;; =============================================================================

(defn layered-store-write-all-test
  [_ _]
  (let [fast (store/mem-store)
        slow (store/mem-store)
        s (store/layered-store fast slow)]
    (put* s [1 2 3 4] :value)
    (concat
     (diff "fast" :value (get* fast [1 2 3 4]))
     (diff "slow" :value (get* slow [1 2 3 4])))))

(defn layered-store-with-file-test
  [env dir]
  (let [mem (store/mem-store)
        file ((:file-store env) dir)
        s (store/layered-store mem file)]
    (put* s [1 2 3 4] {:data "layered"})
    (concat
     (diff "mem has it" {:data "layered"} (get* mem [1 2 3 4]))
     (diff "file has it" {:data "layered"} (get* file [1 2 3 4]))
     (diff "empty mem over file finds it" {:data "layered"}
           (get* (store/layered-store (store/mem-store) file) [1 2 3 4])))))

(defn layered-store-reset-test
  [_ _]
  (let [fast (store/mem-store {[1 2 3 4] :a})
        slow (store/mem-store {[5 6 7 8] :b})
        s (store/layered-store fast slow)]
    (store/await (store/s-reset s))
    (concat
     (diff "fast entries" 0 (entries fast))
     (diff "slow entries" 0 (entries slow)))))

;; =============================================================================
;; LmdbStore
;; =============================================================================

(defn lmdb-store-basic-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb")
    (fn [s]
      (let [h [1 2 3 4]
            v ["i64" 42]
            before-get (get* s h)
            before-has (has* s h)]
        (put* s h v)
        (concat
         (diff "get before put" nil before-get)
         (diff "has? before put" false (boolean before-has))
         (diff "has? after put" true (has* s h))
         (diff "get after put" v (get* s h)))))))

(defn lmdb-store-persistence-test
  [env dir]
  (let [path ((:join env) dir "lmdb-persist")
        h [1 2 3 4]
        v ["i64" 99]]
    (with-lmdb env path
      (fn [s] (put* s h v)))
    (with-lmdb env path
      (fn [s]
        (diff "a new instance reads it" v (get* s h))))))

(defn lmdb-store-entries-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb-keys")
    (fn [s]
      (put* s [1 2 3 4] ["i64" 1])
      (put* s [5 6 7 8] ["i64" 2])
      (concat
       (diff "entries" 2 (entries s))
       (diff "get 1" ["i64" 1] (get* s [1 2 3 4]))
       (diff "get 2" ["i64" 2] (get* s [5 6 7 8]))))))

(defn lmdb-store-batch-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb-batch")
    (fn [s]
      (store/await ((:write-batch env) s [[[1 2 3 4] ["i64" 10]] [[5 6 7 8] ["i64" 20]]]))
      (concat
       (diff "get 10" ["i64" 10] (get* s [1 2 3 4]))
       (diff "get 20" ["i64" 20] (get* s [5 6 7 8]))))))

(defn lmdb-store-reset-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb-reset")
    (fn [s]
      (put* s [1 2 3 4] ["i64" 1])
      (store/await (store/s-reset s))
      (concat
       (diff "get after reset" nil (get* s [1 2 3 4]))
       (diff "entries after reset" 0 (entries s))))))

(defn lmdb-store-complex-values-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb-complex")
    (fn [s]
      (let [header ["vector" {:root [9 8 7 6] :count 3 :size-bytes 24}]
            fuse (words [1 0 0 1])]
        (put* s [1 0 0 0] ["i64" 42])
        (put* s [2 0 0 0] header)
        (put* s [3 0 0 0] ["ft/deep" {:left [1 1 1 1]
                                      :spine [2 2 2 2]
                                      :right [3 3 3 3]
                                      :measure {:count 5
                                                :size-bytes 40
                                                :elements-fuse fuse}}])
        (put* s [4 0 0 0] ["hamt/entry" {:key-hash [5 5 5 5]
                                         :key-ref [6 6 6 6]
                                         :val-ref [7 7 7 7]
                                         :measure {:count 1
                                                   :size-bytes 8
                                                   :elements-fuse fuse}}])
        (let [ft (get* s [3 0 0 0])
              hamt (get* s [4 0 0 0])]
          (concat
           (diff "scalar" ["i64" 42] (get* s [1 0 0 0]))
           (diff "collection header" (canon header) (canon (get* s [2 0 0 0])))
           (diff "ft type" "ft/deep" (first ft))
           (diff "ft left" (canon [1 1 1 1]) (canon (:left (second ft))))
           (diff "hamt type" "hamt/entry" (first hamt))
           (diff "hamt key-ref" (canon [6 6 6 6]) (canon (:key-ref (second hamt))))))))))

(defn lmdb-store-with-layered-test
  [env dir]
  (with-lmdb env ((:join env) dir "lmdb-layered")
    (fn [lmdb]
      (let [mem (store/mem-store)
            s (store/layered-store mem lmdb)]
        (put* s [1 2 3 4] ["i64" 42])
        (concat
         (diff "mem has it" ["i64" 42] (get* mem [1 2 3 4]))
         (diff "lmdb has it" ["i64" 42] (get* lmdb [1 2 3 4]))
         (diff "empty mem over lmdb finds it" ["i64" 42]
               (get* (store/layered-store (store/mem-store) lmdb) [1 2 3 4])))))))

(defn lmdb-value-layer-scalar-and-collection-persist
  [env dir]
  (let [path ((:join env) dir "lmdb-value-layer")
        [problems sh se vh ve]
        (with-lmdb env path
          (fn [st]
            (let [s (scalar/i64-with-store st 42)
                  sh (types/dacite-hash s)
                  se (get* st sh)
                  v (coll/vector-with-store st 1 2 3)
                  vh (types/dacite-hash v)
                  ve (get* st vh)]
              [(concat
                (diff "scalar entry" ["i64" 42] se)
                (diff "vector type" "vector" (first ve))
                (diff "vector count" 3 (:count (second ve))))
               sh se vh ve])))]
    (with-lmdb env path
      (fn [st2]
        (concat
         problems
         (diff "scalar after reopen" (canon se) (canon (get* st2 sh)))
         (diff "vector after reopen" (canon ve) (canon (get* st2 vh)))
         (diff "has scalar" true (has* st2 sh))
         (diff "has vector" true (has* st2 vh))
       ;; full graph still present for vector children
         (diff "has vector root" true (has* st2 (:root (second ve)))))))))

(defn lmdb-rooted-value-layer-persist
  [env dir]
  (let [path ((:join env) dir "lmdb-rooted-value")
        [problems root-h]
        (with-lmdb env path
          (fn [st]
            (let [rooted (rs/rooted-store st ((:lmdb-root-cell env) st))
                  h (types/dacite-hash (coll/vector-with-store st "a" "b"))]
              (rs/set-root! rooted h)
              [(diff "root" (canon h) (canon (rs/root rooted))) h])))]
    (with-lmdb env path
      (fn [st2]
        (let [rooted2 (rs/rooted-store st2 ((:lmdb-root-cell env) st2))]
          (concat
           problems
           (diff "root after reopen" (canon root-h) (canon (rs/root rooted2)))
           (diff "has root" true (has* st2 root-h))
           (diff "root type" "vector" (first (get* st2 root-h)))))))))

(defn lmdb-content-is-wire-v1-node-payload
  [env dir]
  (let [path ((:join env) dir "lmdb-wire-node")
        ub (:to-ubytes env)
        [problems h entry]
        (with-lmdb env path
          (fn [st]
            (let [h (types/dacite-hash (scalar/i64-with-store st 42))
                  entry (get* st h)
                  expected (bin/encode-node-bytes entry)
                  raw ((:lmdb-raw env) st h)]
              [(if (nil? raw)
                 ["raw read: no bytes under the hash"]
                 (concat
                  ;; scalar node kind is 0x00, not an EDN map or vector
                  (diff "node kind byte" 0 (first (ub raw)))
                  (diff "raw is the node payload" (ub expected) (ub raw))
                  (diff "raw decodes to the entry" entry (bin/decode-node-bytes raw))))
               h entry])))]
    (with-lmdb env path
      (fn [st2]
        (let [entry2 (get* st2 h)]
          (concat
           problems
           (diff "entry after reopen" entry entry2)
           (diff "re-encode after reopen"
                 (ub (bin/encode-node-bytes entry))
                 (ub (bin/encode-node-bytes entry2)))))))))

;; =============================================================================
;; Content addressing and await
;; =============================================================================

(defn content-addressing-test
  [_ _]
  (let [s (store/mem-store)]
    (put* s [1 2 3 4] :v)
    (put* s [1 2 3 4] :v)
    (concat
     (diff "get" :v (get* s [1 2 3 4]))
     (diff "one entry" 1 (entries s)))))

(defn- reason-and-hash
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) ex
         (select-keys (ex-data ex) [:reason :hash]))))

(defn missing-node-fails-walk-test
  [_ _]
  (let [s (store/mem-store)
        h [9 9 9 9]
        v (types/wrap-entry "vector" s h)]
    (concat
     (diff "absent" nil (get* s h))
     (diff "walk fails :missing" {:reason :missing :hash h}
           ;; On nbb a SCI deftype has no ICounted, so the walk is v/count.
           (reason-and-hash #?(:clj #(count v) :cljs #(v/count v)))))))

(defn await-rejected-is-unavailable-test
  [_ _]
  (let [h [1 2 3 4]]
    (diff "rejected promise" {:reason :unavailable :hash h}
          (reason-and-hash
           #(store/await
             (store/rejected
              (ex-info "store unavailable"
                       {:reason :unavailable :hash h :cause :down})))))))

(def checks
  {"hash-hex-roundtrip-test" hash-hex-roundtrip-test
   "mem-store-basic-test" mem-store-basic-test
   "mem-store-entries-test" mem-store-entries-test
   "mem-store-reset-test" mem-store-reset-test
   "file-store-basic-test" file-store-basic-test
   "file-store-persistence-test" file-store-persistence-test
   "file-store-sharding-test" file-store-sharding-test
   "file-store-entries-test" file-store-entries-test
   "file-store-reset-test" file-store-reset-test
   "file-store-creates-dir-test" file-store-creates-dir-test
   "layered-store-write-all-test" layered-store-write-all-test
   "layered-store-with-file-test" layered-store-with-file-test
   "layered-store-reset-test" layered-store-reset-test
   "lmdb-store-basic-test" lmdb-store-basic-test
   "lmdb-store-persistence-test" lmdb-store-persistence-test
   "lmdb-store-entries-test" lmdb-store-entries-test
   "lmdb-store-batch-test" lmdb-store-batch-test
   "lmdb-store-reset-test" lmdb-store-reset-test
   "lmdb-store-complex-values-test" lmdb-store-complex-values-test
   "lmdb-store-with-layered-test" lmdb-store-with-layered-test
   "lmdb-value-layer-scalar-and-collection-persist" lmdb-value-layer-scalar-and-collection-persist
   "lmdb-rooted-value-layer-persist" lmdb-rooted-value-layer-persist
   "lmdb-content-is-wire-v1-node-payload" lmdb-content-is-wire-v1-node-payload
   "content-addressing-test" content-addressing-test
   "missing-node-fails-walk-test" missing-node-fails-walk-test
   "await-rejected-is-unavailable-test" await-rejected-is-unavailable-test})

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-one
  "Run check `name` in a new temp dir. Returns {:name :ms :problems}. A
   check that throws is a problem, and one over `max-ms` is a problem."
  [env name]
  (let [f (get checks name)
        dir ((:tmp-dir env) "dacite-store-")
        t0 (now-ms)
        problems (try (vec (f env dir))
                      (catch #?(:clj Throwable :cljs :default) e
                        [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
        ms (- (now-ms) t0)]
    ((:rm-rf env) dir)
    {:name name :ms ms
     :problems (cond-> problems
                 (> ms max-ms) (conj (str "took " ms " ms, over " max-ms)))}))

(defn run-all
  "Run every check. Returns [{:name :ms :problems}]."
  [env]
  (mapv #(run-one env %) (sort (keys checks))))
