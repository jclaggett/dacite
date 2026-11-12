(ns dacite.store.format-checks
  "The store format tag (`store/store-format`) of the persisted stores, on
   the JVM and on nbb: file and LMDB. A new store is tagged, a reopened one
   passes, and an untagged or wrong-version store is
   `:store-format-mismatch` (a clear error, not a node-hash failure). The
   IndexedDB store is tested in the idb suite.

   These were `dacite.store.format-test`, JVM only. The host passes `env`,
   the same map as `dacite.store-checks` plus:

     :slurp :spit :delete    text file read, write, and delete
     :lmdb-meta-format       (fn [st]) the raw string under meta key \"format\", or nil
     :set-lmdb-meta-format!  (fn [st s]) overwrite it (string) or delete it (nil)

   The JVM runs them from `dacite.store.format-test`. nbb runs them from
   `dacite.store.storage-cljs-test` (`npm run storage:test`).

   Each check is `(f env dir)`, with `dir` a new temp directory, and returns
   a vector of problem strings, empty when it holds."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn- diff
  [label expected actual]
  (when-not (= expected actual)
    [(str label ": expected " (pr-str expected) ", got " (pr-str actual))]))

(defn- want
  [label ok?]
  (when-not ok? [label]))

(defn- mismatch
  "The ex-data of the :store-format-mismatch thrown by `f`, or nil."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (when (= :store-format-mismatch (:reason (ex-data e)))
           (assoc (ex-data e) :message (ex-message e))))))

(defn- one-node
  "[hash entry] of a small value."
  []
  (let [mem (store/mem-store)
        h (v/hash (v/i64 mem 42))]
    [h (store/await (store/s-get mem h))]))

(defn- with-lmdb
  "`(f st)` on an LMDB store at `path`, closed after."
  [env path f]
  (let [st ((:lmdb-store env) path)]
    (try (f st)
         (finally ((:lmdb-close env) st)))))

(defn- open-or-nil
  "The store at `dir`, or nil when opening it throws."
  [env dir]
  (try ((:file-store env) dir)
       (catch #?(:clj Exception :cljs :default) _ nil)))

(defn the-format-is-one-constant
  [_ _]
  (concat
   (want "store-format is an integer" (integer? store/store-format))
   (diff "store-format" 2 store/store-format)))

;; ---------------------------------------------------------------------------
;; File store: {base}/FORMAT
;; ---------------------------------------------------------------------------

(defn a-new-file-store-is-tagged-and-a-reopened-one-passes
  [env dir]
  (let [[h e] (one-node)
        tag ((:join env) dir "FORMAT")
        st ((:file-store env) dir)
        tagged (str/trim ((:slurp env) tag))
        _ (store/await (store/s-put st h e))
        again ((:file-store env) dir)
        reopened (store/await (store/s-get again h))
        n (:entries (store/await (store/local-stats again)))]
    ;; s-reset keeps the tag
    (store/await (store/s-reset ((:file-store env) dir)))
    (concat
     (diff "FORMAT holds the number" (str store/store-format) tagged)
     (diff "reopened, data intact" e reopened)
     (diff "the tag is not a node" 1 n)
     (want "s-reset keeps the tag"
           (some? (open-or-nil env dir))))))

(defn an-untagged-file-store-with-nodes-is-a-format-mismatch
  [env dir]
  (let [[h e] (one-node)
        d (:join env)]
    (store/await (store/s-put ((:file-store env) dir) h e))
    ((:delete env) (d dir "FORMAT"))     ; as a store written before tags
    (let [m (mismatch #((:file-store env) dir))
          d2 (d dir "fresh")
          fresh (open-or-nil env d2)]
      (concat
       (want "untagged with nodes is a mismatch" (some? m))
       (diff "found" nil (:found m))
       (diff "expected" store/store-format (:expected m))
       (want "message says untagged" (re-find #"untagged" (str (:message m))))
       (want "message says store format" (re-find #"store format" (str (:message m))))
       ;; an untagged EMPTY directory is just a new store
       (want "an untagged empty directory opens" (some? fresh))
       (diff "and is tagged" true ((:exists? env) (d d2 "FORMAT")))))))

(defn a-file-store-of-another-version-is-a-format-mismatch
  [env dir]
  ((:file-store env) dir)
  ;; "1" is the store of the nilpotent fuse (before issue #14)
  (vec
   (mapcat (fn [tag]
             ((:spit env) ((:join env) dir "FORMAT") (str tag "\n"))
             (let [m (mismatch #((:file-store env) dir))]
               (concat
                (want (str tag ": a mismatch") (some? m))
                (diff (str tag ": found")
                      (if (= "banana" tag) "banana" (parse-long tag))
                      (:found m))
                (want (str tag ": message says format")
                      (re-find #"format" (str (:message m)))))))
           ["1" "3" "0" "banana"])))

;; ---------------------------------------------------------------------------
;; LMDB: meta DB key "format"
;; ---------------------------------------------------------------------------

(defn a-new-lmdb-store-is-tagged-and-a-reopened-one-passes
  [env dir]
  (let [[h e] (one-node)
        p ((:join env) dir "lmdb")
        tagged (with-lmdb env p
                 (fn [st]
                   (store/await (store/s-put st h e))
                   ((:lmdb-meta-format env) st)))
        reopened (with-lmdb env p
                   (fn [st] (store/await (store/s-get st h))))]
    (concat
     (diff "meta key format holds the number" (str store/store-format) tagged)
     (diff "reopened, data intact" e reopened))))

(defn an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch
  [env dir]
  (let [[h e] (one-node)
        d (:join env)
        p (d dir "lmdb")
        set-tag! (:set-lmdb-meta-format! env)]
    (with-lmdb env p
      (fn [st]
        (store/await (store/s-put st h e))
        (set-tag! st nil)))              ; as a store written before tags
    (let [m (mismatch #((:lmdb-store env) p))
          ;; the failed open closed its env: the same path fails the same
          ;; way, not with an env-already-open error
          m-again (mismatch #((:lmdb-store env) p))
          p2 (d dir "lmdb2")
          _ (with-lmdb env p2 (fn [st] (set-tag! st "7")))
          m2 (mismatch #((:lmdb-store env) p2))
          p3 (d dir "lmdb3")
          _ (with-lmdb env p3 (fn [st] (set-tag! st nil)))
          retagged (with-lmdb env p3 (:lmdb-meta-format env))]
      (concat
       (want "untagged with nodes is a mismatch" (some? m))
       (diff "found" nil (:found m))
       (want "message says untagged" (re-find #"untagged" (str (:message m))))
       (want "a second open is the same mismatch" (some? m-again))
       (diff "second open found" nil (:found m-again))
       (diff "wrong version found" 7 (:found m2))
       (diff "wrong version expected" store/store-format (:expected m2))
       ;; an untagged EMPTY store is just a new store
       (diff "an untagged empty store is tagged" (str store/store-format) retagged)))))

(def checks
  {"the-format-is-one-constant" the-format-is-one-constant
   "a-new-file-store-is-tagged-and-a-reopened-one-passes" a-new-file-store-is-tagged-and-a-reopened-one-passes
   "an-untagged-file-store-with-nodes-is-a-format-mismatch" an-untagged-file-store-with-nodes-is-a-format-mismatch
   "a-file-store-of-another-version-is-a-format-mismatch" a-file-store-of-another-version-is-a-format-mismatch
   "a-new-lmdb-store-is-tagged-and-a-reopened-one-passes" a-new-lmdb-store-is-tagged-and-a-reopened-one-passes
   "an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch" an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch})

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-one
  "Run check `name` in a new temp dir. Returns {:name :ms :problems}. A
   check that throws is a problem, and one over `max-ms` is a problem."
  [env name]
  (let [f (get checks name)
        dir ((:tmp-dir env) "dacite-fmt-")
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
