(ns dacite.examples.sync
  "Directory / blob sync — pull only the files you open.

   **Values** — a directory entry:
     {\"kind\" \"dir\"  \"entries\" {name → entry}}
     {\"kind\" \"file\" \"size\" n \"blob\" <dacite blob>}

   ls reads kind/size only. cat realizes one blob. Identical file
   contents share a blob hash.

   **Store** — file-rooted or HTTP remote. `store/sync-reachable!` copies
   the subgraph; `push-ref` / root CAS publishes it.

   Run:
     clojure -M:sync -- --reset seed
     clojure -M:sync -- ls
     clojure -M:sync -- cat readme.txt
     clojure -M:sync -- put /path/to/dir
     clojure -M:sync -- --url http://127.0.0.1:8080 push
     clojure -M:sync -- bench
     bb sync --reset seed
     npx nbb -m dacite.examples.sync -- --reset seed
     npx nbb -m dacite.examples.sync -- --lmdb --reset seed"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?(:clj [clojure.java.io :as io])))

;; =============================================================================
;; Values
;; =============================================================================

(defn file-entry
  "A file entry. `content` is a host byte array or seq of bytes."
  [peer content]
  (let [bs (if #?(:clj (bytes? content) :cljs false)
             content
             #?(:clj (byte-array (map unchecked-byte content))
                :cljs content))
        n #?(:clj (alength ^bytes bs)
             :cljs (or (.-length bs) (count bs)))]
    (v/map peer
           "kind" "file"
           "size" n
           "blob" (v/blob peer bs))))

(defn dir-entry
  "A directory entry from a seq of [name entry] pairs."
  [peer children]
  (v/map peer
         "kind" "dir"
         "entries" (apply v/map peer (mapcat identity children))))

(defn dir? [e] (= "dir" (v/native (v/get e "kind"))))
(defn file? [e] (= "file" (v/native (v/get e "kind"))))
(defn entry-size [e] (or (v/native (v/get e "size")) 0))
(defn entry-blob [e] (v/get e "blob"))
(defn entries [e] (v/get e "entries"))

(defn- utf8-bytes
  "UTF-8 code units as 0–255 ints. CLJS `int` of a one-char string is 0,
   so we go through the host UTF-8 encoder on every platform."
  [s]
  #?(:cljs (let [buf (.from js/Buffer s "utf8")]
             (mapv #(aget buf %) (range (.-length buf))))
     :default (mapv #(Byte/toUnsignedInt %)
                    (.getBytes ^String (str s) "UTF-8"))))

(defn sample-tree
  "Fixture tree: two files share a blob; one larger unique file; a subdir."
  [peer]
  (let [hello (utf8-bytes "hello dacite\n")
        long-txt (apply str (repeat 40 "The same paragraph. "))
        long-bs (utf8-bytes long-txt)
        bin (range 256)
        note (file-entry peer long-bs)
        sub (dir-entry peer [["note.txt" note]])]
    (dir-entry peer
               [["readme.txt" (file-entry peer hello)]
                ["copy.txt" (file-entry peer hello)]
                ["data.bin" (file-entry peer bin)]
                ["sub" sub]])))

(defn load-or-seed!
  "Load the tree from a root, or CAS-seed `sample-tree`."
  [tree-ref]
  (if-let [prior (v/deref tree-ref)]
    [prior false]
    (let [t (sample-tree tree-ref)]
      (if (v/cas! tree-ref nil t)
        [t true]
        [(v/deref tree-ref) false]))))

(defn parse-rel-path
  [s]
  (vec (remove str/blank? (str/split (str s) #"/"))))

(defn lookup
  "Walk `path-segs` from a dir entry. Returns the entry, or throws."
  [root segs]
  (reduce (fn [node seg]
            (when-not (dir? node)
              (throw (ex-info "not a directory" {:seg seg})))
            (or (v/get (entries node) seg)
                (throw (ex-info "path not found" {:seg seg}))))
          root
          segs))

(defn child-names
  "Names in a dir, without realizing blobs."
  [dir]
  (mapv v/native (or (v/keys (entries dir)) ())))

(defn list-entries
  "[{:name :kind :size}] for a dir. Does not realize blobs."
  [dir]
  (mapv (fn [name]
          (let [e (v/get (entries dir) name)]
            {:name name
             :kind (v/native (v/get e "kind"))
             :size (when (file? e) (entry-size e))}))
        (child-names dir)))

(defn cat-file
  "Bytes of the file at `path-segs`. Throws {:dacite/missing true} if the
   blob is not in the store."
  [root segs]
  (let [e (lookup root segs)]
    (when-not (file? e)
      (throw (ex-info "not a file" {:path segs})))
    (v/as-bytes (entry-blob e))))

(defn blob-hash
  [file-e]
  (v/hash (entry-blob file-e)))

(defn node-count [v]
  (count (store/s-snapshot (v/dacite-store v))))

(defn measure-sharing
  "Sample tree: copy.txt and readme.txt must share a blob; listing must
   not require as-bytes."
  [root]
  (let [readme (lookup root ["readme.txt"])
        copy (lookup root ["copy.txt"])
        listed (list-entries root)]
    {:names (mapv :name listed)
     :shared-blob? (= (blob-hash readme) (blob-hash copy))
     :nodes (node-count root)
     :readme-bytes (entry-size readme)
     :data-bytes (entry-size (lookup root ["data.bin"]))}))

;; =============================================================================
;; Host directory ingest / emit (JVM, babashka, nbb)
;; =============================================================================

(defn- host-dir?
  [p]
  #?(:cljs (.isDirectory (.statSync (js/require "fs") p))
     :default (.isDirectory (io/file p))))

(defn- host-list
  [p]
  #?(:cljs (->> (js->clj (.readdirSync (js/require "fs") p))
                sort
                vec)
     :default (->> (.listFiles (io/file p))
                   (map #(.getName ^java.io.File %))
                   sort
                   vec)))

(defn- host-join
  [dir name]
  #?(:cljs (.join (js/require "path") dir name)
     :default (str (io/file dir name))))

(defn- slurp-bytes
  [p]
  #?(:cljs (.readFileSync (js/require "fs") p)
     :default
     (with-open [in (io/input-stream (io/file p))]
       (.readAllBytes in))))

(defn- spit-bytes
  [p bs]
  #?(:cljs (.writeFileSync (js/require "fs") p (js/Buffer.from (clj->js (vec bs))))
     :default
     (do (io/make-parents p)
         (with-open [out (io/output-stream (io/file p))]
           (.write out ^bytes bs)))))

(defn ingest-tree
  "Host directory → Dacite dir entry. Skips names starting with '.'."
  [peer host-path]
  (letfn [(go [p]
            (if (host-dir? p)
              (dir-entry peer
                         (for [name (host-list p)
                               :when (not (str/starts-with? name "."))]
                           [name (go (host-join p name))]))
              (file-entry peer (slurp-bytes p))))]
    (go host-path)))

(defn emit-tree
  "Write a Dacite dir entry onto the host filesystem."
  [root host-path]
  (letfn [(go [e p]
            (if (dir? e)
              (do
                #?(:clj (.mkdirs (io/file p))
                   :cljs (.mkdirSync (js/require "fs") p #js {:recursive true}))
                (doseq [{:keys [name]} (list-entries e)]
                  (go (v/get (entries e) name) (host-join p name))))
              (spit-bytes p (v/as-bytes (entry-blob e)))))]
    (go root host-path)))

;; =============================================================================
;; Store
;; =============================================================================

(def default-path
  "target/dacite-sync")

(defn open-mem [] (store/mem))
(defn open-file [path] (store/file path))
(defn open-remote [url] (store/remote url))
(defn open-lmdb [path] (store/lmdb path))

(defn reset-store-dir!
  [path]
  (store/file path {:reset true})
  nil)

(defn reset-lmdb-dir!
  [path]
  (store/lmdb path {:reset true})
  nil)

(defn local-path
  [{:keys [lmdb? path]}]
  (if (and lmdb? (= path default-path))
    (str default-path "-lmdb")
    path))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--lmdb] [--reset|-r] [--out DIR]
        [seed|ls [PATH]|cat PATH|put HOSTDIR|push|pull|bench|export DIR]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :lmdb? false
                :path default-path
                :url nil
                :out nil
                :cmd "ls"
                :cmd-args []}]
      (if-not (seq args)
        acc
        (let [a (first args)
              more (rest args)]
          (cond
            (or (= a "--reset") (= a "-r"))
            (recur more (assoc acc :reset? true))

            (= a "--lmdb")
            (recur more (assoc acc :lmdb? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (= a "--out")
            (recur (rest more) (assoc acc :out (first more)))

            (#{"seed" "ls" "cat" "put" "push" "pull" "bench" "export"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "ls" :cmd-args (vec args))))))))

(defn open-store
  [{:keys [url lmdb?] :as opts}]
  (cond
    url (open-remote url)
    lmdb? (open-lmdb (local-path opts))
    :else (open-file (:path opts))))

(defn open-local
  [opts]
  (if (:lmdb? opts)
    (open-lmdb (local-path opts))
    (open-file (:path opts))))

(defn push-tree!
  "Copy reachable nodes to dest and CAS dest root to src root."
  [src dest]
  (let [h (store/root src)
        result (store/sync-reachable! src dest h)
        ok (store/cas-root! dest (store/root dest) h)]
    (assoc result :published? ok)))

;; =============================================================================
;; Render + main
;; =============================================================================

(defn- print! [s]
  (print s)
  (flush))

(defn render-ls [dir path]
  (let [rows (list-entries dir)]
    (str (if (str/blank? path) "." path) "/\n"
         (apply str
                (map (fn [{:keys [name kind size]}]
                       (if (= kind "dir")
                         (str "  " name "/\n")
                         (str "  " name "  " size " B\n")))
                     rows))
         "root: " (store/hash->hex (v/hash dir)) "\n")))

(defn render-bench [m]
  (str "sync bench\n"
       "  names:        " (str/join ", " (:names m)) "\n"
       "  shared blob:  " (:shared-blob? m) "\n"
       "  store nodes:  " (:nodes m) "\n"
       "  readme:       " (:readme-bytes m) " B\n"
       "  data.bin:     " (:data-bytes m) " B\n"))

(defn -main [& args]
  (let [{:keys [reset? path url lmdb? out cmd cmd-args] :as opts} (parse-args args)
        local (local-path opts)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local store only" {:url url})))
    (when reset?
      (if lmdb?
        (reset-lmdb-dir! local)
        (reset-store-dir! path))
      (println "reset store at" local))
    (when lmdb?
      (println "lmdb" local))
    (let [rs (open-store opts)
          tree-ref (v/root rs)]
      (case cmd
        "seed"
        (let [[t seeded?] (load-or-seed! tree-ref)]
          (println (if seeded? "seeded sample tree" "already had a root"))
          (print! (render-ls t "")))

        "ls"
        (let [[t seeded?] (load-or-seed! tree-ref)
              segs (parse-rel-path (or (first cmd-args) ""))]
          (when seeded?
            (println "seeded sample tree"))
          (print! (render-ls (if (seq segs) (lookup t segs) t)
                             (or (first cmd-args) ""))))

        "cat"
        (let [t (or (v/deref tree-ref)
                    (throw (ex-info "empty store; run seed or put" {})))
              segs (parse-rel-path (or (first cmd-args)
                                       (throw (ex-info "cat requires a path" {}))))
              bs (cat-file t segs)]
          (if out
            (do (spit-bytes out bs)
                (println "wrote" out))
            #?(:clj (do (.write System/out ^bytes bs)
                        (flush))
               :cljs (println (apply str (map char bs))))))

        "put"
        (let [host (or (first cmd-args)
                       (throw (ex-info "put requires a host directory" {})))
              t (ingest-tree tree-ref host)]
          (if (v/deref tree-ref)
            (v/swap! tree-ref (fn [_] t))
            (v/cas! tree-ref nil t))
          (print! (render-ls t "")))

        "push"
        (do
          (when-not url
            (throw (ex-info "push requires --url" {})))
          (let [local (open-local opts)
                remote (open-remote url)]
            (when-not (store/root local)
              (throw (ex-info "local store has no root; seed or put first" {})))
            (let [result (push-tree! local remote)]
              (println "synced" (:copied result) "nodes via" (:via result)
                       (if (:published? result)
                         "and published root"
                         "but CAS lost the race")))))

        "pull"
        (do
          (when-not url
            (throw (ex-info "pull requires --url" {})))
          (let [local (open-local opts)
                remote (open-remote url)]
            (when-not (store/root remote)
              (throw (ex-info "remote root is empty" {})))
            (let [result (push-tree! remote local)]
              (println "pulled" (:copied result) "nodes via" (:via result)
                       (if (:published? result)
                         "and set local root"
                         "but local CAS failed")))))

        "export"
        (let [t (or (v/deref tree-ref)
                    (throw (ex-info "empty store" {})))
              dest (or out
                       (when (= "--out" (first cmd-args)) (second cmd-args))
                       (first cmd-args)
                       (throw (ex-info "export requires a directory" {})))]
          (emit-tree t dest)
          (println "exported to" dest))

        "bench"
        (let [t (or (v/deref tree-ref)
                    (let [[s _] (load-or-seed! tree-ref)] s))]
          (print! (render-bench (measure-sharing t))))))))
