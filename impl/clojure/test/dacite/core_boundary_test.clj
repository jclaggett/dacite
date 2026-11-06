(ns dacite.core-boundary-test
  "Lint for docs/design/core-boundary.md.

   A namespace the inventory marks `core` may require other core
   namespaces, the host contract `dacite.host`, and portable
   `clojure.*` / `cljs.*` libraries. It may not require a host
   namespace or a server-only namespace. Negative cases build that
   graph in memory."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Markers. Comments and strings are removed first, so a docstring may
;; mention js/ or java. without by itself leaving core.
;; ---------------------------------------------------------------------------

(def ^:private interop-patterns
  [#"#\?"
   #"\bjs/"
   #"\bjava\."
   #"\bclojure\.java\b"
   #"\bclojure\.lang\b"
   #":import\b"
   #"\bcljs\."
   #"\bgoog\."
   #"\bbyte-array\b"
   #"\bbytes\?"
   #"\bSystem/"
   #"\bMath/"
   #"\bThread(?:Local)?/"
   #"\bThreadLocal\b"
   #"\bInteger/"
   #"\bLong/"
   #"\bDouble/"
   #"\bCharacter/"])

(defn- strip
  "Drop comments and string contents. An opening quote becomes a space
   so two tokens on either side cannot join."
  [^String text]
  (let [n (count text)
        sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (let [c (nth text i)]
          (cond
            (= c \;)
            (recur (loop [j (inc i)]
                     (if (and (< j n) (not= (nth text j) \newline))
                       (recur (inc j))
                       j)))

            (= c \")
            (do (.append sb \space)
                (recur (loop [j (inc i) esc? false]
                         (cond
                           (>= j n) j
                           esc? (recur (inc j) false)
                           (= (nth text j) \\) (recur (inc j) true)
                           (= (nth text j) \") (inc j)
                           :else (recur (inc j) false)))))

            :else
            (do (.append sb c)
                (recur (inc i)))))))))

(defn- host-interop?
  [source]
  (let [code (strip source)]
    (boolean (some #(re-find % code) interop-patterns))))

(defn- dynamic-load?
  "A core namespace may not load another namespace at runtime."
  [source]
  (let [code (strip source)]
    (boolean (or (re-find #"\brequiring-resolve\b" code)
                 (re-find #"\(require\b" code)
                 (re-find #"\(load(?:-file|-reader)?\b" code)))))

(defn- service-ns?
  [sym]
  (let [s (str sym)]
    (or (= s "dacite.service")
        (str/starts-with? s "dacite.service."))))

(defn- stdlib?
  "Portable Clojure libraries. clojure.java.* and clojure.lang.* are host."
  [lib]
  (let [s (str lib)]
    (and (or (str/starts-with? s "clojure.")
             (str/starts-with? s "cljs."))
         (not (str/starts-with? s "clojure.java"))
         (not (str/starts-with? s "clojure.lang")))))

;; ---------------------------------------------------------------------------
;; ns form: every reader-conditional branch, not only the JVM one.
;; ---------------------------------------------------------------------------

(defn- ns-form-string
  [^String stripped]
  (let [idx (loop [from 0]
              (let [i (.indexOf stripped "(ns" (int from))]
                (cond
                  (neg? i) nil
                  (and (< (+ i 3) (count stripped))
                       (Character/isWhitespace (nth stripped (+ i 3))))
                  i
                  :else (recur (+ i 3)))))]
    (when idx
      (subs stripped idx
            (loop [i idx depth 0]
              (cond
                (>= i (count stripped))
                (throw (ex-info "Unbalanced ns form" {}))
                (= (nth stripped i) \() (recur (inc i) (inc depth))
                (= (nth stripped i) \)) (if (= depth 1)
                                          (inc i)
                                          (recur (inc i) (dec depth)))
                :else (recur (inc i) depth)))))))

(defn- tokenize
  [^String s]
  (let [n (count s)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (nth s i)]
          (cond
            (Character/isWhitespace c) (recur (inc i) acc)
            (#{\( \) \[ \] \{ \}} c) (recur (inc i) (conj acc (str c)))
            (and (= c \#) (< (inc i) n))
            (let [n1 (nth s (inc i))]
              (cond
                (and (= n1 \?) (< (+ i 2) n) (= (nth s (+ i 2)) \@))
                (recur (+ i 3) (conj acc "#?@"))
                (= n1 \?) (recur (+ i 2) (conj acc "#?"))
                (= n1 \_) (recur (+ i 2) (conj acc "#_"))
                (= n1 \{) (recur (+ i 2) (conj acc "#{"))
                :else (recur (inc i) (conj acc "#"))))
            (#{\' \^} c) (recur (inc i) (conj acc (str c)))
            :else
            (let [j (loop [j i]
                      (if (and (< j n)
                               (not (Character/isWhitespace (nth s j)))
                               (not (#{\( \) \[ \] \{ \}} (nth s j))))
                        (recur (inc j))
                        j))]
              (recur j (conj acc (subs s i j))))))))))

(declare parse-one)

(defn- parse-until
  [end tokens]
  (loop [tokens tokens acc []]
    (cond
      (empty? tokens) (throw (ex-info "Unbalanced form" {:end end}))
      (= (first tokens) end) [acc (rest tokens)]
      :else
      (let [[node more] (parse-one tokens)]
        (if (nil? node)
          (recur more acc)
          (recur more (conj acc node)))))))

(defn- parse-one
  [tokens]
  (when (seq tokens)
    (let [t (first tokens)
          more (rest tokens)]
      (case t
        "(" (let [[xs rest-toks] (parse-until ")" more)]
              [{:t :list :xs xs} rest-toks])
        "[" (let [[xs rest-toks] (parse-until "]" more)]
              [{:t :vec :xs xs} rest-toks])
        "{" (let [[xs rest-toks] (parse-until "}" more)]
              [{:t :map :xs xs} rest-toks])
        "#{" (let [[xs rest-toks] (parse-until "}" more)]
               [{:t :set :xs xs} rest-toks])
        "#?" (let [[form rest-toks] (parse-one more)]
               [{:t :cond :xs (if (= :list (:t form)) (:xs form) [form])}
                rest-toks])
        "#?@" (let [[form rest-toks] (parse-one more)]
                [{:t :cond :xs (if (= :list (:t form)) (:xs form) [form])}
                 rest-toks])
        "#_" (let [[_ rest-toks] (parse-one more)]
               [nil rest-toks])
        "#" (parse-one more)
        "'" (parse-one more)
        "^" (let [[_ rest-toks] (parse-one more)]
              (parse-one rest-toks))
        [{:t :tok :s t} more]))))

(defn- kw-tok?
  [node]
  (and (= :tok (:t node)) (str/starts-with? (:s node) ":")))

(defn- sym-tok?
  [node]
  (and (= :tok (:t node))
       (not (kw-tok? node))
       (not (str/starts-with? (:s node) "#"))))

(def ^:private option-kws #{":refer" ":only" ":exclude" ":rename"})

(defn- branch-values
  "Every branch of a reader conditional, including the ones this JVM
   would skip."
  [xs]
  (loop [xs (seq xs) acc []]
    (cond
      (empty? xs) acc
      (kw-tok? (first xs))
      (if (next xs)
        (recur (drop 2 xs) (conj acc (second xs)))
        acc)
      :else (recur (rest xs) (conj acc (first xs))))))

(declare collect-node)

(defn- nested-non-option-vecs
  [xs]
  (loop [xs (seq (rest xs)) acc []]
    (cond
      (empty? xs) acc
      (and (kw-tok? (first xs)) (option-kws (:s (first xs))))
      (recur (drop 2 xs) acc)
      (= :vec (:t (first xs)))
      (recur (rest xs) (conj acc (first xs)))
      :else (recur (rest xs) acc))))

(defn- collect-vec
  [xs]
  (cond
    (empty? xs) []
    (every? #(#{:vec :cond} (:t %)) xs)
    (vec (mapcat collect-node xs))
    (sym-tok? (first xs))
    (if (seq (nested-non-option-vecs xs))
      [{:prefix (symbol (:s (first xs)))}]
      [{:lib (symbol (:s (first xs)))}])
    :else (vec (mapcat collect-node xs))))

(defn- collect-node
  [node]
  (cond
    (nil? node) []
    (= :cond (:t node)) (vec (mapcat collect-node (branch-values (:xs node))))
    (= :vec (:t node)) (collect-vec (:xs node))
    (= :list (:t node)) (vec (mapcat collect-node (:xs node)))
    (sym-tok? node) [{:lib (symbol (:s node))}]
    :else []))

(defn- require-head?
  [node]
  (and (= :list (:t node))
       (kw-tok? (first (:xs node)))
       (#{":require" ":require-macros" ":use"} (:s (first (:xs node))))))

(defn- require-nodes
  [node]
  (cond
    (require-head? node) [node]
    (= :cond (:t node)) (vec (mapcat require-nodes (branch-values (:xs node))))
    (#{:vec :list} (:t node)) (vec (mapcat require-nodes (:xs node)))
    :else []))

(defn analyze-source
  "`:libs` is every libspec head in `:require`, `:require-macros`, and
   `:use`, from every reader-conditional branch. `:prefixes` are prefix
   libspecs, which this lint does not expand."
  [source]
  (if-let [form (ns-form-string (strip source))]
    (let [node (first (parse-one (tokenize form)))
          ;; The list is (ns the-name ...). `ns` is the first symbol.
          name-tok (->> (:xs node) (filter sym-tok?) second)
          found (mapcat collect-node (mapcat require-nodes (:xs node)))]
      {:name (when name-tok (symbol (:s name-tok)))
       :libs (vec (distinct (keep :lib found)))
       :prefixes (vec (distinct (keep :prefix found)))})
    {:name nil :libs [] :prefixes []}))

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------

(defn- core-candidate?
  [sym {:keys [ext source]}]
  (and (= ext ".cljc")
       (not (service-ns? sym))
       (not (host-interop? source))
       (not (dynamic-load? source))))

(defn- dep-allowed?
  [lib core-set]
  (or (contains? core-set lib)
      (= lib 'dacite.host)
      (stdlib? lib)))

(defn derive-classes
  "`files` is `{namespace {:ext \".cljc\" :source \"...\"}}`.
   `dacite.service` and `dacite.service.*` are server-only. A `.cljc`
   namespace with no host markers and no dynamic load is core when
   every Dacite require is core or `dacite.host`. Everything else is
   host."
  [files]
  (let [candidates (into #{}
                         (for [[sym info] files
                               :when (core-candidate? sym info)]
                           sym))
        libs-of (fn [sym] (:libs (analyze-source (:source (get files sym)))))
        core (loop [core candidates]
               (let [next-core (into #{}
                                     (filter (fn [sym]
                                               (every? #(dep-allowed? % core)
                                                       (libs-of sym)))
                                             core))]
                 (if (= next-core core)
                   core
                   (recur next-core))))]
    (into {}
          (for [sym (keys files)]
            [sym (cond
                   (service-ns? sym) :server-only
                   (contains? core sym) :core
                   :else :host)]))))

(defn dependency-problems
  "Problems for namespaces `classes` marks `:core`. This is the lint:
   a core namespace that requires a host or server-only namespace,
   an unknown library, a prefix libspec, or a runtime `require`."
  [classes sources]
  (vec
   (for [[sym source] sources
         :when (= :core (get classes sym))
         :let [info (analyze-source source)]
         problem (concat
                  (when (dynamic-load? source)
                    [{:ns sym :problem :dynamic-load}])
                  (for [prefix (:prefixes info)]
                    {:ns sym :problem :prefix-libspec :lib prefix})
                  (for [lib (:libs info)
                        :when (and (not= lib 'dacite.host)
                                   (not (stdlib? lib))
                                   (not= :core (get classes lib)))]
                    {:ns sym
                     :problem :forbidden-dependency
                     :lib lib
                     :class (get classes lib :unknown)}))]
     problem)))

;; ---------------------------------------------------------------------------
;; Inventory document
;; ---------------------------------------------------------------------------

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) (throw (ex-info "Could not find repo root (bb.edn)" {}))
      (.isFile (io/file d "bb.edn")) d
      :else (recur (.getParentFile d)))))

(defn- rel-path
  [root file]
  (-> (.toPath root)
      (.relativize (.toPath file))
      str
      (.replace \\ \/)))

(defn- table-cells
  [line]
  (->> (str/split line #"\|")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- separator-row?
  [cells]
  (and (seq cells) (every? #(re-matches #":?-+" %) cells)))

(defn- unquote-cell
  [s]
  (if (and (str/starts-with? s "`") (str/ends-with? s "`") (> (count s) 1))
    (subs s 1 (dec (count s)))
    s))

(defn read-inventory
  "Parse the namespace tables and the script table in core-boundary.md."
  [text]
  (loop [lines (str/split-lines text)
         fence? false
         mode nil
         namespaces {}
         scripts {}
         problems []]
    (if (empty? lines)
      {:namespaces namespaces :scripts scripts :problems problems}
      (let [line (first lines)
            trim (str/trim line)]
        (cond
          (str/starts-with? trim "```")
          (recur (rest lines) (not fence?) mode namespaces scripts problems)

          fence?
          (recur (rest lines) fence? mode namespaces scripts problems)

          (not (str/starts-with? trim "|"))
          (recur (rest lines) fence? nil namespaces scripts problems)

          :else
          (let [cells (table-cells line)]
            (cond
              (= cells ["Namespace" "Class" "File" "Rationale"])
              (recur (rest lines) fence? :ns namespaces scripts problems)

              (= cells ["File" "Note"])
              (recur (rest lines) fence? :script namespaces scripts problems)

              (separator-row? cells)
              (recur (rest lines) fence? mode namespaces scripts problems)

              (= mode :ns)
              (if (= 4 (count cells))
                (let [[ns-cell class-cell file-cell rationale] cells
                      ns-name (unquote-cell ns-cell)
                      class (unquote-cell class-cell)
                      file (unquote-cell file-cell)]
                  (cond
                    (not (#{"core" "host" "server-only"} class))
                    (recur (rest lines) fence? mode namespaces scripts
                           (conj problems {:problem :bad-row :line trim}))

                    (str/blank? rationale)
                    (recur (rest lines) fence? mode namespaces scripts
                           (conj problems {:problem :blank-rationale :ns ns-name}))

                    (contains? namespaces (symbol ns-name))
                    (recur (rest lines) fence? mode namespaces scripts
                           (conj problems {:problem :duplicate-ns :ns ns-name}))

                    :else
                    (recur (rest lines) fence? mode
                           (assoc namespaces (symbol ns-name)
                                  {:class (keyword class)
                                   :file file
                                   :rationale rationale})
                           scripts
                           problems)))
                (recur (rest lines) fence? mode namespaces scripts
                       (conj problems {:problem :bad-row :line trim})))

              (= mode :script)
              (if (= 2 (count cells))
                (let [[file-cell note] cells
                      file (unquote-cell file-cell)]
                  (recur (rest lines) fence? mode namespaces
                         (assoc scripts file note)
                         problems))
                (recur (rest lines) fence? mode namespaces scripts
                       (conj problems {:problem :bad-row :line trim})))

              :else
              (recur (rest lines) fence? mode namespaces scripts problems))))))))

(defn- source-files
  [root]
  (for [rel ["impl/clojure/src" "impl/clojure/dev"]
        ^java.io.File f (file-seq (io/file root rel))
        :when (and (.isFile f)
                   (re-find #"\.(clj|cljc|cljs)$" (.getName f)))]
    f))

(defn- ext-of
  [^java.io.File f]
  (let [name (.getName f)]
    (subs name (.lastIndexOf name "."))))

(defn tree-problems
  "Empty when the inventory, the derived classes, and the core require
   rule all agree with the source tree."
  []
  (let [root (repo-root)
        doc (slurp (io/file root "docs/design/core-boundary.md"))
        {:keys [namespaces scripts problems]} (read-inventory doc)
        records (for [f (source-files root)]
                  (let [source (slurp f)
                        info (analyze-source source)]
                    {:file (rel-path root f)
                     :ext (ext-of f)
                     :source source
                     :name (:name info)
                     :libs (:libs info)
                     :prefixes (:prefixes info)}))
        ns-recs (filter :name records)
        script-recs (remove :name records)
        by-ns (reduce (fn [m rec]
                        (update m (:name rec) (fnil conj []) rec))
                      {}
                      ns-recs)
        files (into {}
                    (for [[sym recs] by-ns
                          :let [rec (first recs)]]
                      [sym {:ext (:ext rec) :source (:source rec)}]))
        derived (derive-classes files)
        classes (into {} (for [[sym {:keys [class]}] namespaces] [sym class]))
        sources (into {} (for [[sym {:keys [source]}] files] [sym source]))]
    (vec
     (concat
      problems
      (for [[sym recs] by-ns
            :when (> (count recs) 1)]
        {:problem :duplicate-file :ns sym})
      (for [sym (sort (keys by-ns))
            :when (not (contains? namespaces sym))]
        {:problem :missing-from-inventory :ns sym :file (:file (first (get by-ns sym)))})
      (for [[sym {:keys [file]}] namespaces
            :when (not (contains? by-ns sym))]
        {:problem :unknown-namespace :ns sym :file file})
      (for [[sym {:keys [file class]}] namespaces
            :let [rec (first (get by-ns sym))]
            :when (and rec (not= file (:file rec)))]
        {:problem :file-mismatch :ns sym :inventory file :tree (:file rec)})
      (for [[sym class] derived
            :let [documented (get classes sym)]
            :when (not= class documented)]
        {:problem :class-mismatch :ns sym :derived class :inventory documented})
      (dependency-problems classes sources)
      (for [rec records
            prefix (:prefixes rec)]
        {:problem :prefix-libspec :ns (:name rec) :lib prefix :file (:file rec)})
      (let [tree-scripts (set (map :file script-recs))
            doc-scripts (set (keys scripts))]
        (concat
         (for [f (sort (set/difference tree-scripts doc-scripts))]
           {:problem :script-missing-from-inventory :file f})
         (for [f (sort (set/difference doc-scripts tree-scripts))]
           {:problem :unknown-script :file f})))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest clj-conditional-requires-are-visible
  (let [root (repo-root)
        libs (fn [rel]
               (set (:libs (analyze-source (slurp (io/file root rel))))))]
    (testing "a :clj-only :require is part of the graph on every host"
      (is (contains? (libs "impl/clojure/src/dacite/value.cljc")
                     'dacite.value.render))
      (is (contains? (libs "impl/clojure/src/dacite/examples/event_log.cljc")
                     'dacite.store.remote)))
    (testing "spliced :clj libspecs are part of the graph"
      (let [got (libs "impl/clojure/src/dacite/value/collections.cljc")]
        (is (contains? got 'dacite.hash))
        (is (contains? got 'dacite.value.render))))))

(deftest core-namespace-must-not-require-a-host-namespace
  (let [classes {'dacite.pure :core
                 'dacite.host :host
                 'dacite.store.file :host}
        sources {'dacite.pure
                 "(ns dacite.pure (:require [dacite.store.file :as file]))"
                 'dacite.store.file "(ns dacite.store.file)"}]
    (is (= [{:ns 'dacite.pure
             :problem :forbidden-dependency
             :lib 'dacite.store.file
             :class :host}]
           (dependency-problems classes sources)))))

(deftest core-namespace-must-not-require-a-server-only-namespace
  (let [classes {'dacite.pure :core
                 'dacite.service :server-only}
        sources {'dacite.pure
                 "(ns dacite.pure (:require [dacite.service :as svc]))"}]
    (is (= [{:ns 'dacite.pure
             :problem :forbidden-dependency
             :lib 'dacite.service
             :class :server-only}]
           (dependency-problems classes sources)))))

(deftest core-may-require-the-host-contract
  (testing "dacite.host is the portability contract, so core may require it"
    (is (= []
           (dependency-problems
            {'dacite.pure :core
             'dacite.host :host}
            {'dacite.pure "(ns dacite.pure (:require [dacite.host :as host]))"}))))
  (testing "a pure .cljc namespace whose only Dacite require is dacite.host is core"
    (is (= {'dacite.pure :core
            'dacite.host :host}
           (derive-classes
            {'dacite.pure {:ext ".cljc"
                           :source "(ns dacite.pure (:require [dacite.host :as host]))"}
             'dacite.host {:ext ".cljc"
                           :source "(ns dacite.host) (defn f [] (java.util.LinkedHashMap.))"}})))))

(deftest reader-conditional-branches-are-all-visible
  (testing "a :cljs branch counts even though this test runs on the JVM"
    (is (= [{:ns 'dacite.pure
             :problem :forbidden-dependency
             :lib 'dacite.store.browser
             :class :host}]
           (dependency-problems
            {'dacite.pure :core
             'dacite.host :host
             'dacite.store.browser :host}
            {'dacite.pure
             "(ns dacite.pure
                (:require [dacite.host :as host]
                          #?(:cljs [dacite.store.browser :as browser])))"}))))
  (testing "a spliced :clj libspec counts"
    (is (= ['dacite.host 'dacite.store.file]
           (:libs (analyze-source
                   "(ns dacite.pure
                      (:require [dacite.host :as host]
                                #?@(:clj [[dacite.store.file :as file]])))"))))))

(deftest host-interop-is-not-core
  (is (= {'dacite.pure :host}
         (derive-classes
          {'dacite.pure {:ext ".cljc"
                         :source "(ns dacite.pure) (defn f [x] (js/Math.exp x))"}})))
  (is (= {'dacite.pure :host}
         (derive-classes
          {'dacite.pure {:ext ".clj"
                         :source "(ns dacite.pure)"}}))))

(deftest service-namespace-is-server-only
  (is (= {'dacite.service :server-only
          'dacite.service.reveal :server-only
          'dacite.store.remote :host}
         (derive-classes
          {'dacite.service {:ext ".clj" :source "(ns dacite.service)"}
           'dacite.service.reveal {:ext ".clj" :source "(ns dacite.service.reveal)"}
           'dacite.store.remote {:ext ".clj" :source "(ns dacite.store.remote)"}}))))

(deftest dynamic-load-is-not-core
  (is (= {'dacite.pure :host}
         (derive-classes
          {'dacite.pure {:ext ".cljc"
                         :source "(ns dacite.pure) (requiring-resolve 'dacite.store.file/file-store)"}})))
  (is (= [{:ns 'dacite.pure :problem :dynamic-load}]
         (dependency-problems
          {'dacite.pure :core}
          {'dacite.pure "(ns dacite.pure) (require 'dacite.store.file)"}))))

(deftest host-may-require-a-server-only-namespace
  (is (= []
         (dependency-problems
          {'dacite.peer.items :host
           'dacite.service.reveal :server-only}
          {'dacite.peer.items
           "(ns dacite.peer.items (:require [dacite.service.reveal :as sr]))"}))))

(deftest inventory-matches-the-tree
  (let [problems (tree-problems)]
    (is (empty? problems) (pr-str problems))))
