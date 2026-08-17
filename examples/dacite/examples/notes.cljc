(ns dacite.examples.notes
  "Versioned notes — every edit is a snapshot; unchanged fields keep their hash.

   **Values** — a notebook map:
     {\"doc\" {\"title\" \"…\" \"body\" \"…\" \"tags\" […] \"edited-at\" n}
      \"history\" [oldest-doc … most-recent-previous]}  ; conj appends

   Version index 0 is current; 1 is the last history item; 2 is the one
   before that. Restore reuses a historical doc value (same content hash).

   Restore reuses a historical doc value (same content hash). A title-only
   edit does not rewrite the body node. Domain uses only dacite.value.

   **Store** — file-rooted or HTTP remote-rooted (same wiring as config).

   Run:
     clojure -M:notes -- show
     clojure -M:notes -- set title Hello
     clojure -M:notes -- set body A longer body that we will share
     clojure -M:notes -- list
     clojure -M:notes -- diff 0 1
     clojure -M:notes -- restore 1
     clojure -M:notes -- bench
     bb notes show
     npx nbb -m dacite.examples.notes -- show"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?@(:org.babashka/nbb [[dacite.store.nbb :as host-store]]
                :cljs []
                :default [])))

;; =============================================================================
;; Values
;; =============================================================================

(def seed-body
  "A body long enough that rewriting it is visibly more expensive than
   changing the title. Shared across versions when only the title moves."
  (str "Dacite notes keep every snapshot as a content-addressed value. "
       "The body of this seed document is deliberately longer than a title "
       "so a title-only edit can reuse the same body hash. Unchanged "
       "subtrees are free: the store grows by the new title, the new doc "
       "map, and a history conj — not by another copy of this paragraph. "
       "That is the claim this app exists to prove."))

(defn default-doc
  "Seed document relative to `peer`."
  [peer]
  (v/hash-map-via peer
                  "title" "Welcome"
                  "body" seed-body
                  "tags" (v/vector-via peer "intro")
                  "edited-at" 0))

(defn empty-notebook
  "Notebook with a seed doc and empty history."
  [peer]
  (v/hash-map-via peer
                  "doc" (default-doc peer)
                  "history" (v/vector-via peer)))

(defn load-or-seed!
  "Load notebook from a root-ref, or CAS-seed defaults. Returns [nb seeded?]."
  [nb-ref]
  (if-let [prior (v/ref-deref nb-ref)]
    [prior false]
    (let [nb (empty-notebook nb-ref)]
      (if (v/ref-cas! nb-ref nil nb)
        [nb true]
        [(v/ref-deref nb-ref) false]))))

(defn current-doc
  "The current document value."
  [nb]
  (v/get nb "doc"))

(defn history
  "History vector of previous docs (oldest first; conj appends). Empty if none."
  [nb]
  (or (v/get nb "history") (v/vector-via nb)))

(defn version-count
  "Current plus previous snapshots."
  [nb]
  (inc (v/count (history nb))))

(defn doc-at
  "Document at version index. 0 = current, 1 = previous, 2 = the one before that."
  [nb idx]
  (let [hist (history nb)
        n (v/count hist)]
    (cond
      (neg? idx)
      (throw (ex-info "version index must be >= 0" {:idx idx}))
      (zero? idx) (current-doc nb)
      (> idx n)
      (throw (ex-info "version index out of range"
                      {:idx idx :versions (inc n)}))
      :else (v/nth hist (- n idx)))))

(defn title [doc] (v/as-str (v/get doc "title")))
(defn body [doc] (v/as-str (v/get doc "body")))
(defn edited-at [doc] (or (v/native (v/get doc "edited-at")) 0))
(defn tags [doc] (v/get doc "tags"))

(defn- bump [doc]
  (v/assoc doc "edited-at" (inc (edited-at doc))))

(defn commit
  "Push current doc onto history and install `(edit-doc current)` as current."
  [nb edit-doc]
  (let [doc (current-doc nb)]
    (-> nb
        (v/assoc "doc" (edit-doc doc))
        (v/assoc "history" (v/conj (history nb) doc)))))

(defn set-title
  "New notebook whose current doc has a new title (body hash unchanged)."
  [nb title]
  (commit nb (fn [doc] (v/assoc (bump doc) "title" title))))

(defn set-body
  "New notebook whose current doc has a new body."
  [nb text]
  (commit nb (fn [doc] (v/assoc (bump doc) "body" text))))

(defn add-tag
  "Append a tag to the current doc."
  [nb name]
  (commit nb (fn [doc]
               (v/assoc (bump doc) "tags" (v/conj (tags doc) name)))))

(defn restore
  "Install historical doc `idx` as current. The doc value is reused
   (same content hash); current is pushed onto history."
  [nb idx]
  (let [old (doc-at nb idx)]
    (-> nb
        (v/assoc "doc" old)
        (v/assoc "history" (v/conj (history nb) (current-doc nb))))))

(defn field-hash
  "Content hash of document field k, or nil."
  [doc k]
  (when-let [x (v/get doc k)]
    (v/dacite-hash x)))

(defn field-changed?
  "True if field k differs by content hash."
  [a b k]
  (not= (field-hash a k) (field-hash b k)))

(defn diff-keys
  "Keys among title/body/tags/edited-at whose hashes differ."
  [a b]
  (filterv #(field-changed? a b %) ["title" "body" "tags" "edited-at"]))

(defn short-hex
  [h]
  (when h
    (subs (store/hash->hex h) 0 12)))

(defn node-count
  "Number of entries in the value's content store (all versions)."
  [v]
  (count (store/s-snapshot (v/dacite-store v))))

(defn measure-sharing
  "Apply a title-only edit then a body rewrite. Returns node deltas and
   whether the body hash survived the title change."
  [nb]
  (let [body-h0 (field-hash (current-doc nb) "body")
        n0 (node-count nb)
        nb1 (set-title nb "Title-only edit")
        n1 (node-count nb1)
        body-h1 (field-hash (current-doc nb1) "body")
        long-body (str seed-body " " (apply str (repeat 80 \z)))
        nb2 (set-body nb1 long-body)
        n2 (node-count nb2)]
    {:title-only (- n1 n0)
     :body-rewrite (- n2 n1)
     :body-shared? (= body-h0 body-h1)
     :before n0
     :after-title n1
     :after-body n2}))

(defn render-doc
  "Plain-text document. Body via pr-str so a long string is not dumped."
  [doc]
  (let [ts (or (v/seq (tags doc)) ())]
    (str "title:      " (title doc) "\n"
         "edited-at:  " (edited-at doc) "\n"
         "tags:       " (str/join ", " (map v/as-str ts)) "\n"
         "body:       " (v/pr-str (v/get doc "body") 80) "\n"
         "doc:        " (short-hex (v/dacite-hash doc)) "\n")))

(defn render
  [nb]
  (str (render-doc (current-doc nb))
       "history:    " (v/count (history nb)) " previous\n"
       "root:       " (store/hash->hex (v/dacite-hash nb)) "\n"))

(defn render-list
  [nb]
  (str "versions (" (version-count nb) "):\n"
       (apply str
              (map (fn [i]
                     (let [d (doc-at nb i)]
                       (str "  " i ". [" (edited-at d) "] "
                            (title d) "  "
                            (short-hex (v/dacite-hash d)) "…\n")))
                   (range (version-count nb))))))

(defn render-diff
  [nb ia ib]
  (let [a (doc-at nb ia)
        b (doc-at nb ib)
        ks (diff-keys a b)]
    (str "diff " ia " → " ib "\n"
         (if (empty? ks)
           "  (identical)\n"
           (apply str
                  (map (fn [k]
                         (if (= k "body")
                           (str "  body:  " (short-hex (field-hash a k))
                                " → " (short-hex (field-hash b k))
                                (if (field-changed? a b k)
                                  "  (changed)\n"
                                  "  (same)\n"))
                           (str "  " k ":  "
                                (v/pr-str (v/get a k) 40) " → "
                                (v/pr-str (v/get b k) 40) "\n")))
                       ks))))))

(defn render-bench
  [m]
  (str "sharing bench\n"
       "  title-only:   +" (:title-only m) " nodes\n"
       "  body-rewrite: +" (:body-rewrite m) " nodes\n"
       "  body shared after title edit: " (:body-shared? m) "\n"))

;; =============================================================================
;; Store
;; =============================================================================

(def default-path
  "target/dacite-notes")

#?(:org.babashka/nbb
   (defn open-file
     [path]
     (store/rooted-store (host-store/file-store path)
                         (store/file-root-cell path)))
   :cljs
   (defn open-file
     [_path]
     (throw (js/Error. "notes/open-file is for JVM/nbb file backends only")))
   :default
   (defn open-file
     [path]
     (store/rooted-store (store/file-store path)
                         (store/file-root-cell path))))

#?(:clj
   (defn open-remote
     [url]
     (store/remote-rooted-store url))
   :default
   (defn open-remote
     [_url]
     (throw (ex-info "remote notes store is JVM-only (java.net.http)" {}))))

(defn open-mem
  []
  (store/rooted-store (store/mem-store)))

#?(:org.babashka/nbb
   (defn reset-store-dir!
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil))
   :cljs
   (defn reset-store-dir! [_path] nil)
   :default
   (defn reset-store-dir!
     [path]
     (let [content (store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil)))

(defn parse-path
  [s]
  (mapv (fn [seg]
          (if (re-matches #"-?\d+" seg)
            #?(:clj (Long/parseLong seg)
               :cljs (js/parseInt seg 10))
            seg))
        (str/split (str s) #"\.")))

(defn parse-int
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--reset|-r] [show|list|get PATH|set FIELD VAL…|add-tag NAME|diff A B|restore N|bench]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :path default-path
                :url nil
                :cmd "show"
                :cmd-args []}]
      (if-not (seq args)
        acc
        (let [a (first args)
              more (rest args)]
          (cond
            (or (= a "--reset") (= a "-r"))
            (recur more (assoc acc :reset? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (#{"show" "list" "get" "set" "add-tag" "diff" "restore" "bench"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "show" :cmd-args (vec args))))))))

(defn open-store
  [{:keys [url path]}]
  (if url
    (open-remote url)
    (open-file path)))

;; =============================================================================
;; Main
;; =============================================================================

(defn- print! [s]
  (print s)
  (flush))

(defn -main [& args]
  (let [{:keys [reset? path url cmd cmd-args] :as opts} (parse-args args)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local file store only" {:url url})))
    (when reset?
      (reset-store-dir! path)
      (println "reset store at" path))
    (let [rs (open-store opts)
          nb-ref (v/root-ref rs)
          [_ seeded?] (load-or-seed! nb-ref)]
      (when seeded?
        (println (if url
                   (str "seeded remote at " url)
                   (str "seeded new store at " path))))
      (case cmd
        "show"
        (print! (render (v/ref-deref nb-ref)))

        "list"
        (print! (render-list (v/ref-deref nb-ref)))

        "get"
        (let [ks (parse-path (or (first cmd-args) ""))]
          (when (empty? ks)
            (throw (ex-info "get requires a path, e.g. title or tags.0" {})))
          (let [x (v/get-in (current-doc (v/ref-deref nb-ref)) ks)]
            (println (if (and (v/dacite-value? x)
                              (#{"string" "i64" "bool"} (v/value-type x)))
                       (v/as-str x)
                       (v/pr-str x 80)))))

        "set"
        (let [field (first cmd-args)
              text (str/join " " (rest cmd-args))]
          (when (or (nil? field) (str/blank? text))
            (throw (ex-info "set requires FIELD VALUE" {:args cmd-args})))
          (let [nb' (case field
                      "title" (v/ref-swap! nb-ref set-title text)
                      "body" (v/ref-swap! nb-ref set-body text)
                      (throw (ex-info "set field must be title or body" {:field field})))]
            (print! (render nb'))))

        "add-tag"
        (let [name (or (first cmd-args)
                       (throw (ex-info "add-tag requires a name" {})))]
          (print! (render (v/ref-swap! nb-ref add-tag name))))

        "diff"
        (let [a (parse-int (or (first cmd-args) "0"))
              b (parse-int (or (second cmd-args) "1"))]
          (print! (render-diff (v/ref-deref nb-ref) a b)))

        "restore"
        (let [idx (parse-int (or (first cmd-args)
                                 (throw (ex-info "restore requires a version index" {}))))]
          (print! (render (v/ref-swap! nb-ref restore idx))))

        "bench"
        (let [nb (v/ref-deref nb-ref)
              m (measure-sharing nb)]
          (print! (render-bench m)))))))
