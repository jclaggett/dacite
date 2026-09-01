(ns dacite.examples.lmdb-bench
  "Time nbb file-store vs nbb LMDB on the sync sample tree.
   Also prove a JVM-written env can be opened from nbb.

   npx nbb -m dacite.examples.lmdb-bench
   npx nbb -m dacite.examples.lmdb-bench -- /tmp/dacite-lmdb-interop"
  (:require [dacite.examples.sync :as fs]
            [dacite.store :as store]
            [dacite.store.nbb :as nbb-store]
            [dacite.store.nbb.lmdb :as lmdb]
            [dacite.value :as v]))

(defn- now-ms [] (.now js/Date))

(defn- rm-rf [p]
  (let [fs (js/require "fs")]
    (when (.existsSync fs p)
      (.rmSync fs p #js {:recursive true :force true}))))

(defn- file-count [dir]
  (let [fs (js/require "fs")
        path (js/require "path")]
    (letfn [(walk [d]
              (if-not (.existsSync fs d)
                0
                (reduce + 0
                        (map (fn [name]
                               (let [p (.join path d name)
                                     st (.statSync fs p)]
                                 (if (.isDirectory st) (walk p) 1)))
                             (vec (.readdirSync fs d))))))]
      (walk dir))))

(defn- file-size [p]
  (let [fs (js/require "fs")]
    (if (.existsSync fs p)
      (.-size (.statSync fs p))
      0)))

(defn- seed-into [open-fn path]
  (rm-rf path)
  (let [t0 (now-ms)
        rs (store/rooted-store (open-fn path)
                               (store/file-root-cell path))
        r (v/root rs)
        [tree _] (fs/load-or-seed! r)
        ms (- (now-ms) t0)
        hex (store/hash->hex (v/hash tree))]
    {:ms ms :hex hex :path path}))

(defn -main [& args]
  (let [file-path "target/dacite-sync-file-bench"
        lmdb-path "target/dacite-sync-lmdb-bench"
        file-res (seed-into nbb-store/file-store file-path)
        lmdb-res (let [t0 (now-ms)
                       _ (rm-rf lmdb-path)
                       st (lmdb/lmdb-store lmdb-path)
                       rs (store/rooted-store st (lmdb/lmdb-root-cell st))
                       r (v/root rs)
                       [tree _] (fs/load-or-seed! r)
                       ms (- (now-ms) t0)
                       hex (store/hash->hex (v/hash tree))]
                   (let [root-h (lmdb/lmdb-get-meta st "root")]
                     (lmdb/lmdb-close st)
                     {:ms ms :hex hex :path lmdb-path
                      :meta-hex (when root-h (store/hash->hex root-h))
                      :reopen-n (let [st2 (lmdb/lmdb-store lmdb-path)
                                      h (store/hex->hash hex)
                                      ok? (store/s-has? st2 h)]
                                  (lmdb/lmdb-close st2)
                                  ok?)}))]
    (println "file-store")
    (println "  ms     " (:ms file-res))
    (println "  files  " (file-count file-path))
    (println "  root   " (:hex file-res))
    (println "lmdb-store")
    (println "  ms     " (:ms lmdb-res))
    (println "  data   " (file-size (str lmdb-path "/data.mdb")) "B")
    (println "  files  " (file-count lmdb-path))
    (println "  root   " (:hex lmdb-res))
    (println "  meta   " (:meta-hex lmdb-res))
    (println "  reopen has root?" (:reopen-n lmdb-res))
    (println "same root?" (= (:hex file-res) (:hex lmdb-res)))
    (when-let [jvm-path (->> args (remove #{"--"}) first)]
      (let [st (lmdb/lmdb-store jvm-path)
            ;; known i64 42 from the JVM interop seed
            h (store/hex->hash
               "c325aa0c3ce069bfc934762612f7e78586b4cb6e2189b88f25e67ec3621a0c8d")
            entry (store/s-get st h)]
        (println "jvm-env" jvm-path "s-get i64-42 =>" (pr-str entry))
        (lmdb/lmdb-close st)))))
