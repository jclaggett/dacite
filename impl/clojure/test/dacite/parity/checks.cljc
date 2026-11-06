(ns dacite.parity.checks
  "Reveal and pack decisions that must match on the JVM and on nbb.

   Given a value and a clock, the page order and the bytes are the golden
   in fixtures/parity/decisions.edn. Pack cases pin the wire bytes of the
   same kind of value. The canonical root hash from dacite.examples.parity
   is in that file too.

   One command runs both hosts and fails if they disagree:

       bin/parity.sh

   Either host alone:

       cd impl/clojure && clojure -M:parity
       npm run parity:test

   JVM suite: deftests in dacite.parity-test call `problems`.
   Regenerate the golden on the JVM after an intentional decision change:

       cd impl/clojure && clojure -M:parity --write

   Session cases stay at eight hashes. On nbb a set or map of BigInt hashes
   throws once it grows past eight; page-order already keys by hex, the
   session does not, and this suite does not change that."
  (:refer-clojure :exclude [abs])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dacite.examples.parity :as parity]
            [dacite.host :as host]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.scalar :as scalar]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]
            #?(:clj [clojure.java.io :as io]
               :cljs [cljs.reader :as reader])
            #?(:cljs ["fs" :as fs])
            #?(:cljs ["path" :as path])))

;; ---------------------------------------------------------------------------
;; Golden file
;; ---------------------------------------------------------------------------

(defn- repo-root
  []
  #?(:clj
     (loop [dir (io/file (System/getProperty "user.dir"))]
       (cond
         (nil? dir)
         (throw (ex-info "repo root not found" {}))
         (and (.isFile (io/file dir "package.json"))
              (.isDirectory (io/file dir "fixtures"))
              (.isDirectory (io/file dir "impl" "clojure")))
         (.getCanonicalPath dir)
         :else (recur (.getParentFile dir))))
     :cljs
     (loop [dir (.resolve path ".")]
       (cond
         (and (.existsSync fs (.join path dir "package.json"))
              (.existsSync fs (.join path dir "fixtures")))
         dir
         :else
         (let [parent (.dirname path dir)]
           (if (= parent dir)
             (throw (ex-info "repo root not found" {:dir dir}))
             (recur parent)))))))

(defn- golden-path
  []
  (str (repo-root) "/fixtures/parity/decisions.edn"))

(defn- read-golden
  []
  (let [path (golden-path)
        text #?(:clj (slurp path)
                :cljs (.readFileSync fs path "utf8"))]
    #?(:clj (edn/read-string text)
       :cljs (reader/read-string text))))

(defn- sorted
  "Maps with keyword keys, in key order, so the golden file is stable."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (sorted v)]) x))
    (vector? x) (mapv sorted x)
    :else x))

#?(:clj
   (defn- write-golden!
     [report]
     (let [path (golden-path)
           _ (.mkdirs (.getParentFile (io/file path)))
           pprint (requiring-resolve 'clojure.pprint/pprint)
           text (with-out-str
                  (println ";; Golden reveal and pack decisions. bin/parity.sh")
                  (println ";; Given a value and a clock, :order and each step's")
                  (println ";; :pages (hash and byte count) match on every host.")
                  (pprint (sorted report)))]
       (spit path text)
       path)))

;; ---------------------------------------------------------------------------
;; Small helpers
;; ---------------------------------------------------------------------------

(def ^:private fast
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e12})

(defn- abs [x]
  (if (neg? x) (- x) x))

(defn- near? [a b eps]
  (< (abs (- (double a) (double b))) eps))

(defn- thrown-msg [f]
  (try
    (f)
    nil
    (catch #?(:clj Throwable :cljs :default) e
      (or (ex-message e) ""))))

(defn- whole
  "A byte count. Both hosts put the same integer in the golden."
  [n]
  (when-not (integer? n)
    (throw (ex-info "expected an integer byte count" {:n n})))
  #?(:clj (long n) :cljs n))

(defn- hex [h]
  (store/hash->hex h))

(defn- idx [hexes target]
  (loop [i 0]
    (cond
      (= i (count hexes)) -1
      (= (nth hexes i) target) i
      :else (recur (inc i)))))

(defn- order-of
  [st h]
  (mapv (fn [page]
          {:hash (hex page)
           :type (types/entry-type (store/require-node st page))
           :bytes (whole (reveal/page-bytes st page))})
        (reveal/page-order st h)))

(defn- pages-of
  [pages]
  (mapv (fn [p]
          {:id (:id p)
           :hash (hex (:hash p))
           :bytes (whole (:bytes p))})
        pages))

(defn- hashes-of
  [pages]
  (mapv :hash (pages-of pages)))

(defn- leaf-text
  [st page]
  (mapv (fn [h]
          (let [n (v/native (v/wrap-hash st h))]
            (cond
              (string? n) n
              #?(:clj (char? n) :cljs false) (str n)
              :else (str n))))
        (ft/ft-leaves st page)))

(defn- hash-rank
  [h]
  (mapv #(hamt/hash-chunk h %) (range 52)))

(defn- lone-page-at
  [tau fraction]
  (* (- tau) (host/log (- 1.0 (/ fraction tau)))))

(defn- key-names
  [m]
  (vec (sort (map #(cond (keyword? %) (name %)
                         (nil? %) "nil"
                         :else (str %))
                  (keys m)))))

(defn- id-name
  [x]
  (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(defn- edn-safe
  "A literal form both hosts can print the same way. A JVM char becomes
   the one-character string nbb already uses."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x)) x
    (integer? x) #?(:clj (long x) :cljs x)
    #?(:clj (char? x) :cljs false) (str x)
    (map? x) (into {} (map (fn [[k v]] [(edn-safe k) (edn-safe v)])) x)
    (sequential? x) (mapv edn-safe x)
    :else (throw (ex-info "pack form is not golden-safe"
                          {:value x
                           :class #?(:clj (str (class x))
                                     :cljs (str (type x)))}))))

(defn- item-wire-bytes
  [item]
  (whole (bin/byte-len (bin/encode-item (bin/pack-item->wire-item item)))))

(defn- wire-bytes
  [items]
  (whole (reduce + 0 (map item-wire-bytes items))))

(defn- chunk-hex
  [items budget]
  (bin/bytes->hex (bin/encode-pack-edn (pack/make-chunk budget items))))

(defn- reveal
  [decision problems]
  {:kind :reveal
   :decision decision
   :problems (vec problems)})

(defn- pack-case
  [decision problems]
  {:kind :pack
   :decision decision
   :problems (vec problems)})

(defn- bad
  [problems ok? msg]
  (if ok? problems (conj problems msg)))

;; ---------------------------------------------------------------------------
;; Reveal: page order
;; ---------------------------------------------------------------------------

(defn- scalar []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        order (order-of st h)]
    (reveal {:id "scalar" :root (hex h) :order order}
            (-> []
                (bad (= 1 (count order)) "a scalar is one node")
                (bad (= (hex h) (:hash (first order))) "the page is the scalar")))))

(defn- empty-vector []
  (let [st (store/mem-store)
        h (v/hash (v/vector st))
        order (order-of st h)
        types (mapv :type order)]
    (reveal {:id "empty-vector" :root (hex h) :order order}
            (-> []
                (bad (= (hex h) (:hash (first order))) "the vector header is first")
                (bad (every? #(or (= "vector" %) (str/starts-with? % "ft/")) types)
                     "an empty vector has no element body")))))

(defn- inlined-scalars []
  (let [st (store/mem-store)
        nums (mapv #(v/i64 st %) (range 40))
        h (v/hash (apply v/vector st nums))
        order (order-of st h)
        types (mapv :type order)]
    (reveal {:id "inlined-scalars" :root (hex h) :order order}
            (-> []
                (bad (= "vector" (first types)) "the vector header is first")
                (bad (boolean (some #(str/starts-with? % "ft/") types)) "the vector has pages")
                (bad (not (some #{"i64"} types)) "inlined scalars stay on their page")))))

(defn- bare-leaf []
  (let [st (store/mem-store)
        n (v/i64 st 7)
        h (v/hash (v/vector st n))
        order (order-of st h)]
    (reveal {:id "bare-leaf" :root (hex h) :order order}
            (-> []
                (bad (= ["vector" "i64"] (mapv :type order)) "a bare leaf is sent")
                (bad (= (hex (v/hash n)) (:hash (second order))) "the leaf is the element")))))

(defn- nested-vectors []
  (let [st (store/mem-store)
        a (v/vector st (v/i64 st 1))
        b (v/vector st (v/i64 st 2))
        outer (v/vector st a b)
        h (v/hash outer)
        order (reveal/page-order st h)
        hexes (mapv hex order)
        ia (idx hexes (hex (v/hash a)))
        ib (idx hexes (hex (v/hash b)))
        owned (every? (fn [page]
                        (let [ip (idx hexes (hex page))]
                          (< ia ip ib)))
                      (rest (reveal/page-order st (v/hash a))))]
    (reveal {:id "nested-vectors" :root (hex h) :order (order-of st h)}
            (-> []
                (bad (= (hex h) (first hexes)) "the outer vector is first")
                (bad (< 0 ia ib) "nested vectors follow the parent")
                (bad owned "a nested vector owns its pages before the next body")
                (bad (= (count hexes) (count (set hexes))) "a hash is listed once")))))

(defn- long-string []
  (let [st (store/mem-store)
        text (str (apply str (repeat 4096 "a"))
                  (apply str (repeat 4096 "z")))
        h (v/hash (v/string st text))
        order (order-of st h)
        digits (filterv #(= "ft/digit" (:type %)) order)
        leaves (fn [rec]
                 (leaf-text st (store/hex->hash (:hash rec))))
        first-leaves (leaves (first digits))
        last-leaves (leaves (peek digits))]
    (reveal {:id "long-string"
             :root (hex h)
             :order order
             :first-leaf (first first-leaves)
             :last-leaf (peek last-leaves)}
            (-> []
                (bad (= "string" (:type (first order))) "the string header is first")
                (bad (> (count digits) 1) "a long string has more than one page")
                (bad (= "a" (first first-leaves)) "the first page starts with a")
                (bad (not (some #{"z"} first-leaves)) "the first page has no z")
                (bad (= "z" (peek last-leaves)) "the last page ends with z")))))

(defn- map-order []
  (let [st (store/mem-store)
        keys* [(v/i64 st 1) (v/i64 st 100) (v/i64 st 7)]
        vals* [(v/string st "a") (v/string st "b") (v/string st "c")]
        m (apply v/map st (interleave keys* vals*))
        h (v/hash m)
        order (reveal/page-order st h)
        hexes (mapv hex order)
        ordered (sort-by #(hash-rank (v/hash %)) keys*)
        key-at (mapv #(idx hexes (hex (v/hash %))) ordered)
        val-at (mapv #(idx hexes (hex (v/hash %)))
                     (map (zipmap keys* vals*) ordered))]
    (reveal {:id "map-order" :root (hex h) :order (order-of st h)}
            (-> []
                (bad (= (hex h) (first hexes)) "the map header is first")
                (bad (apply < key-at) "keys follow HAMT order")
                (bad (every? #(< (apply max key-at) %) val-at)
                     "every value follows every key")
                (bad (= (count hexes) (count (set hexes))) "a hash is listed once")))))

(defn- set-order []
  (let [st (store/mem-store)
        a (v/i64 st 1)
        b (v/i64 st 2)
        c (v/i64 st 3)
        h (v/hash (v/set st a b c))
        order (reveal/page-order st h)
        hexes (mapv hex order)
        ordered (sort-by #(hash-rank (v/hash %)) [a b c])
        at (mapv #(idx hexes (hex (v/hash %))) ordered)]
    (reveal {:id "set-order" :root (hex h) :order (order-of st h)}
            (-> []
                (bad (apply < at) "set elements follow HAMT order")
                (bad (= 1 (count (filter #{(hex (v/hash a))} hexes))) "a is sent once")
                (bad (= 1 (count (filter #{(hex (v/hash b))} hexes))) "b is sent once")
                (bad (= 1 (count (filter #{(hex (v/hash c))} hexes))) "c is sent once")))))

(defn- omit-sent []
  (let [got (reveal/omit-sent [:a :b :c :d] #{:a :c})]
    (reveal {:id "omit-sent" :kept (mapv id-name got)}
            (bad [] (= [:b :d] got) "omit-sent keeps the unsent order"))))

(defn- interest []
  (let [at-zero (reveal/interest 4 10 0)
        msg (thrown-msg #(reveal/interest 1 0 1))]
    (reveal {:id "interest"
             :at-zero (if (= 4.0 at-zero) 4 :not-peak)
             :tau-zero (boolean (and msg (str/includes? msg "tau")))}
            (-> []
                (bad (= 4.0 at-zero) "interest at age 0 is the peak")
                (bad (near? (reveal/interest 4 10 10) (/ 4.0 (host/exp 1.0)) 1.0e-6)
                     "one tau decays by e")
                (bad (boolean (and msg (str/includes? msg "tau"))) "tau must be positive")))))

(defn- shares []
  (let [params {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0}
        newer (reveal/shares [{:id :old :opened-at 0}
                              {:id :new :opened-at 10}]
                             10
                             params)
        dropped (reveal/shares [{:id :old :opened-at 0}
                                {:id :new :opened-at 100}]
                               100
                               {:peak 1.0 :tau 10.0 :floor 0.05 :r-max 80.0})
        cancelled (reveal/shares [{:id :a :opened-at 0 :cancelled? true}
                                  {:id :b :opened-at 0}]
                                 0
                                 {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 50.0})
        done (reveal/shares [{:id :a :opened-at 0 :done? true}]
                            0
                            {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 10.0})
        lone (reveal/shares [{:id :only :opened-at 0}]
                            10
                            {:peak 1.0 :tau 10.0 :floor 0.0 :r-max 100.0})
        nxt (reveal/next-reveal [{:id :old :opened-at 0}
                                 {:id :new :opened-at 10}]
                                10
                                params)
        none (reveal/next-reveal [{:id :old :opened-at 0 :cancelled? true}]
                                 0
                                 params)]
    (reveal {:id "shares"
             :below-floor (key-names dropped)
             :cancel (key-names cancelled)
             :done (key-names done)
             :next (id-name nxt)
             :cancelled-next (id-name none)}
            (-> []
                (bad (> (:new newer) (:old newer)) "the newer request takes the larger share")
                (bad (near? (+ (:new newer) (:old newer)) 100.0 1.0e-6)
                     "the shares fill the ceiling")
                (bad (= ["new"] (key-names dropped)) "below the floor the reveal drops out")
                (bad (near? (:new dropped) 80.0 1.0e-6) "the other reveal takes the ceiling")
                (bad (= ["b"] (key-names cancelled)) "cancel drops that share at once")
                (bad (near? (:b cancelled) 50.0 1.0e-9) "the remaining reveal takes the ceiling")
                (bad (= {} done) "no reveal in the sum")
                (bad (near? (:only lone) (* 100.0 (host/exp -1.0)) 1.0e-6)
                     "a lone reveal decays from the ceiling")
                (bad (= :new nxt) "the largest share receives the next page")
                (bad (nil? none) "a cancelled reveal receives nothing")))))

;; ---------------------------------------------------------------------------
;; Reveal: a value and a clock
;; ---------------------------------------------------------------------------

(defn- one-page []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        n (reveal/page-bytes st h)
        tau 100.0
        params {:peak 1.0 :tau tau :floor 0.0 :r-max (double n)}
        at (lone-page-at tau 1.0)
        opened (reveal/open-reveal (reveal/session) st h 0 params)
        early (reveal/take-pages (:session opened) st 0.5 params)
        short (reveal/take-pages (:session early) st 1.0 params)
        due (reveal/take-pages (:session short) st at params)
        again (reveal/open-reveal (:session due) st h at params)
        later (reveal/take-pages (:session again) st 20 params)
        due-pages (:pages due)]
    (reveal {:id "one-page"
             :root (hex h)
             :order (order-of st h)
             :steps [{:label "early" :pages (pages-of (:pages early))}
                     {:label "short" :pages (pages-of (:pages short))}
                     {:label "due" :pages (pages-of due-pages)}
                     {:label "later" :pages (pages-of (:pages later))}]}
            (-> []
                (bad (= 1 (:id opened)) "the first reveal is id 1")
                (bad (empty? (:pages early)) "half a unit earns nothing")
                (bad (empty? (:pages short)) "constant-rate time is still short")
                (bad (= [(hex h)] (hashes-of due-pages)) "the integral reaches the page")
                (bad (= (whole n) (:bytes (first due-pages))) "the page bytes are the wire item")
                (bad (near? at (:at (first due-pages)) 1.0e-4) "the page is due at the integral")
                (bad (empty? (:pages later)) "a page already offered is not sent again")))))

(defn- reveal-order []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 9000 "a"))))
        order (reveal/page-order st h)
        opened (reveal/open-reveal (reveal/session) st h 0 fast)
        taken (reveal/take-pages (:session opened) st 1 fast)]
    (reveal {:id "reveal-order"
             :root (hex h)
             :order (order-of st h)
             :steps [{:label "t1" :pages (pages-of (:pages taken))}]}
            (-> []
                (bad (> (count order) 1) "the string has more than one page")
                (bad (= (mapv hex order) (hashes-of (:pages taken)))
                     "pages come out in reveal order")
                (bad (boolean (:done? (first (get-in taken [:session :reveals]))))
                     "the reveal is done")))))

(defn- equal-shares []
  (let [st (store/mem-store)
        ha (v/hash (v/i64 st 1))
        hb (v/hash (v/i64 st 2))
        n (reveal/page-bytes st ha)
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max (double n)}
        a (reveal/open-reveal (reveal/session) st ha 0 params)
        b (reveal/open-reveal (:session a) st hb 0 params)
        taken (reveal/take-pages (:session b) st 2 params)]
    (reveal {:id "equal-shares"
             :roots [(hex ha) (hex hb)]
             :steps [{:label "t2" :pages (pages-of (:pages taken))}]}
            (-> []
                (bad (= [(:id a) (:id b)] (mapv :id (:pages taken)))
                     "equal shares send one page each")
                (bad (= [(hex ha) (hex hb)] (hashes-of (:pages taken)))
                     "the pages are the two scalars")
                (bad (every? #(near? 2.0 (:at %) 1.0e-6) (:pages taken))
                     "both pages are due at t = 2")))))

(defn- below-floor []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        n (double (reveal/page-bytes st h))
        params {:peak 1.0 :tau 1.0 :floor (host/exp -0.5) :r-max n}
        banked (* n (- 1.0 (host/exp -0.5)))
        need (- 1.0 (* 2.0 (- 1.0 (host/exp -0.5))))
        waited (- (host/log (- 1.0 need)))
        opened (reveal/open-reveal (reveal/session) st h 0 params)
        paused (reveal/take-pages (:session opened) st 10 params)
        again (reveal/open-reveal (:session paused) st h 10 params)
        still (reveal/take-pages (:session again) st 10.5 params)
        resumed (reveal/open-reveal (:session still) st h 20 params)
        due (reveal/take-pages (:session resumed) st (+ 20.0 waited) params)
        credit (:credit (first (get-in still [:session :reveals])))]
    (reveal {:id "below-floor"
             :root (hex h)
             :steps [{:label "paused" :pages (pages-of (:pages paused))}
                     {:label "again" :pages (pages-of (:pages again))}
                     {:label "still" :pages (pages-of (:pages still))}
                     {:label "due" :pages (pages-of (:pages due))}]}
            (-> []
                (bad (empty? (:pages paused)) "the first ask pauses below the floor")
                (bad (empty? (:pages again)) "opening again sends nothing at that instant")
                (bad (empty? (:pages still)) "two banks are still short of a page")
                (bad (= (:id opened) (:id again) (:id resumed)) "a re-ask keeps the id")
                (bad (near? (* 2.0 banked) credit 1.0e-4) "two runs bank the unspent integral")
                (bad (= [(hex h)] (hashes-of (:pages due))) "the third ask sends the page")
                (bad (near? (+ 20.0 waited) (:at (first (:pages due))) 1.0e-4)
                     "the page is due when the third bank covers it")))))

(defn- budget []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 12000 "x"))))
        order (reveal/page-order st h)
        total (double (reduce + 0 (map #(reveal/page-bytes st %) order)))
        params {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ total 4.0)}
        opened (reveal/open-reveal (reveal/session) st h 0.0 params)
        far (reveal/take-pages (:session opened) st 100.0 params)
        order-hex (mapv hex order)]
    (loop [session (:session far)
           got (vec (hashes-of (:pages far)))
           bursts [(pages-of (:pages far))]
           problems (cond-> []
                      (not (< 1 (count order))) (conj "expected several pages")
                      (not (seq got)) (conj "the first burst is empty")
                      (not (< (count got) (count order))) (conj "the first burst finished the value")
                      (not (reveal/stalled? (:session far) (:id opened) 100.0 params))
                      (conj "a lone ask stalls before the value is done"))
           t 100.0
           n 0]
      (if (= (set order-hex) (set got))
        (reveal {:id "budget"
                 :root (hex h)
                 :order (order-of st h)
                 :bursts bursts}
                problems)
        (if (>= n 8)
          (reveal {:id "budget" :root (hex h) :order (order-of st h) :bursts bursts}
                  (conj problems "a re-ask did not finish the value"))
          (let [again (reveal/open-reveal session st h t params)
                more (reveal/take-pages (:session again) st (+ t 100.0) params)
                add (hashes-of (:pages more))]
            (recur (:session more)
                   (into got add)
                   (conj bursts (pages-of (:pages more)))
                   (cond-> problems
                     (not (seq add)) (conj "a re-ask sent nothing")
                     (some (set got) add) (conj "a re-ask resent a page")
                     (not= (:id opened) (:id again)) (conj "a re-ask changed the id"))
                   (+ t 100.0)
                   (inc n))))))))

(defn- reask-stall []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 12000 "x"))))
        order (reveal/page-order st h)
        total (double (reduce + 0 (map #(reveal/page-bytes st %) order)))
        params {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ total 4.0)}
        opened (reveal/open-reveal (reveal/session) st h 0.0 params)
        id (:id opened)
        order-hex (set (map hex order))
        walk (fn [session t]
               (loop [session session
                      t t
                      emitted []]
                 (if (or (reveal/stalled? session id t params) (>= t 60.0))
                   [session t emitted]
                   (let [nxt (reveal/take-pages session st (+ t 0.05) params)]
                     (recur (:session nxt)
                            (+ t 0.05)
                            (into emitted (pages-of (:pages nxt))))))))
        [session0 t0 got0] (walk (:session opened) 0.0)]
    (loop [session session0
           t t0
           got (mapv :hash got0)
           bursts [got0]
           problems (cond-> []
                      (not (seq got0)) (conj "the walk sent nothing")
                      (not (< (count (set got0)) (count order)))
                      (conj "the first walk finished the value"))
           n 0]
      (if (= order-hex (set got))
        (reveal {:id "reask-stall"
                 :root (hex h)
                 :order (order-of st h)
                 :bursts bursts}
                problems)
        (if (>= n 8)
          (reveal {:id "reask-stall" :root (hex h) :order (order-of st h) :bursts bursts}
                  (conj problems "a re-ask at the stall did not finish the value"))
          (let [offered (vec (keys (:offered session)))
                acked (reveal/ack-pages session offered)
                again (reveal/open-reveal acked st h t params)
                [session2 t2 emitted] (walk (:session again) t)
                add (mapv :hash emitted)]
            (recur session2
                   t2
                   (into got add)
                   (conj bursts emitted)
                   (cond-> problems
                     (not (seq add)) (conj "the re-ask sends nothing the stall could not")
                     (some (set got) add) (conj "the re-ask resent a page")
                     (not= id (:id again)) (conj "the re-ask changed the id"))
                   (inc n))))))))

(defn- child-focus []
  (let [st (store/mem-store)
        child (v/set st (v/i64 st 1) (v/i64 st 2))
        parent (v/vector st child)
        ch (v/hash child)
        ph (v/hash parent)
        order (reveal/page-order st ph)
        opened (reveal/open-reveal (reveal/session) st ph 0 fast)
        done (reveal/take-pages (:session opened) st 1 fast)
        again (reveal/open-reveal (:session done) st ch 1 fast)
        more (reveal/take-pages (:session again) st 2 fast)
        child-order (set (map hex (reveal/page-order st ch)))]
    (reveal {:id "child-focus"
             :root (hex ph)
             :order (order-of st ph)
             :steps [{:label "parent" :pages (pages-of (:pages done))}
                     {:label "open-child" :pages (pages-of (:pages again))}
                     {:label "child" :pages (pages-of (:pages more))}]}
            (-> []
                (bad (= 7 (count order)) "the parent names seven pages")
                (bad (= (set (map hex order)) (set (hashes-of (:pages done))))
                     "the parent offers every page")
                (bad (every? child-order (map hex (reveal/page-order st ch)))
                     "the child pages are part of the parent")
                (bad (empty? (:pages again)) "opening the child sends nothing new")
                (bad (empty? (:pages more)) "the child focus stays empty")))))

(defn- cancel []
  (let [st (store/mem-store)
        ha (v/hash (v/i64 st 1))
        hb (v/hash (v/i64 st 2))
        n (double (reveal/page-bytes st ha))
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max n}
        a (reveal/open-reveal (reveal/session) st ha 0 params)
        b (reveal/open-reveal (:session a) st hb 0 params)
        stopped (reveal/cancel-reveal (:session b) st (:id b) 1 params)
        taken (reveal/take-pages (:session stopped) st 10 params)
        reopened (reveal/open-reveal (:session taken) st hb 10 params)
        rest-pages (reveal/take-pages (:session reopened) st 12 params)]
    (reveal {:id "cancel"
             :roots [(hex ha) (hex hb)]
             :steps [{:label "cancel" :pages (pages-of (:pages stopped))}
                     {:label "taken" :pages (pages-of (:pages taken))}
                     {:label "rest" :pages (pages-of (:pages rest-pages))}]}
            (-> []
                (bad (empty? (:pages stopped)) "cancel itself sends nothing new")
                (bad (= [(hex ha)] (hashes-of (:pages taken))) "the other reveal still sends")
                (bad (not= (:id b) (:id reopened)) "asking again after cancel is a new id")
                (bad (= [(hex hb)] (hashes-of (:pages rest-pages))) "the new id sends its page")))))

(defn- already-sent []
  ;; Two nested vectors are nine hashes. A session set of raw hashes throws
  ;; on nbb past eight, so this case is a vector of one nested vector and a
  ;; scalar (seven hashes). The rule is the same: a hash already held is not
  ;; offered again. The JVM deftest keeps the nine-hash shape.
  (let [st (store/mem-store)
        a (v/vector st (v/i64 st 1))
        outer (v/vector st a (v/i64 st 2))
        sent (reveal/open-reveal (reveal/session) st (v/hash a) 0 fast)
        done (reveal/take-pages (:session sent) st 1 fast)
        held (reveal/ack-pages (:session done) (mapv :hash (:pages done)))
        more (reveal/open-reveal held st (v/hash outer) 1 fast)
        pages (reveal/take-pages (:session more) st 2 fast)
        done-hex (set (hashes-of (:pages done)))
        more-hex (set (hashes-of (:pages pages)))]
    (reveal {:id "already-sent"
             :root (hex (v/hash outer))
             :order (order-of st (v/hash outer))
             :steps [{:label "part" :pages (pages-of (:pages done))}
                     {:label "rest" :pages (pages-of (:pages pages))}]}
            (-> []
                (bad (contains? done-hex (hex (v/hash a))) "the part is sent")
                (bad (not (contains? more-hex (hex (v/hash a)))) "a held hash is not sent again")
                (bad (contains? more-hex (hex (v/hash outer))) "the outer vector is sent")))))

(defn- newer []
  (let [st (store/mem-store)
        book (v/hash (v/string st (apply str (repeat 9000 "a"))))
        chapter (v/hash (v/string st (apply str (repeat 9000 "z"))))
        nbytes (double (reveal/page-bytes st book))
        tau 10.0
        at (* tau (host/log 2.0))
        params {:peak 1.0 :tau tau :floor 1.0e-9 :r-max (/ nbytes 5.0)}
        opened (reveal/open-reveal (reveal/session) st book 0 params)
        at-first (reveal/take-pages (:session opened) st at params)
        ch (reveal/open-reveal (:session at-first) st chapter at params)
        rates (reveal/shares (get-in ch [:session :reveals]) at params)
        nxt (reveal/take-pages (:session ch) st 1000 params)]
    (reveal {:id "newer"
             :roots [(hex book) (hex chapter)]
             :steps [{:label "first" :pages (pages-of (:pages at-first))}
                     {:label "next" :pages (pages-of (:pages nxt))}]}
            (-> []
                (bad (= 1 (count (:pages at-first))) "the book earns one page")
                (bad (near? at (:at (first (:pages at-first))) 1.0e-4)
                     "that page is due at tau * ln 2")
                (bad (> (get rates (:id ch)) (get rates (:id opened)))
                     "the newer reveal has the larger share")
                (bad (= (:id ch) (:id (first (:pages nxt))))
                     "the newer reveal gets the next page")))))

(defn- clock-rejects []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 1))
        opened (reveal/open-reveal (reveal/session) st h 0 fast)
        clock (thrown-msg #(reveal/take-pages (:session opened) st -1 fast))
        rate (thrown-msg #(reveal/take-pages (:session opened) st 1
                                             (assoc fast :r-max -1)))]
    (reveal {:id "clock-rejects"
             :root (hex h)
             :clock (boolean (and clock (str/includes? clock "clock")))
             :r-max (boolean (and rate (str/includes? rate "r-max")))}
            (-> []
                (bad (boolean (and clock (str/includes? clock "clock")))
                     "the clock does not move backward")
                (bad (boolean (and rate (str/includes? rate "r-max")))
                     "r-max must be non-negative")))))

(defn- resume []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 9000 "a"))))
        order (reveal/page-order st h)
        tau 1.0e6
        r-max 1000.0
        n0 (double (reveal/page-bytes st (first order)))
        n1 (double (reveal/page-bytes st (second order)))
        cover (fn [bytes]
                (* (- tau) (host/log (- 1.0 (/ bytes (* r-max tau))))))
        t1 (cover n0)
        t2 (cover (+ n0 n1))
        params {:peak 1.0 :tau tau :floor 0.0 :r-max r-max}
        opened (reveal/open-reveal (reveal/session) st h 0 params)
        one (reveal/take-pages (:session opened) st t1 params)
        again (reveal/open-reveal (:session one) st h t1 params)
        more (reveal/take-pages (:session again) st (+ t2 1.0) params)
        more-hex (hashes-of (:pages more))]
    (reveal {:id "resume"
             :root (hex h)
             :order (order-of st h)
             :steps [{:label "one" :pages (pages-of (:pages one))}
                     {:label "more" :pages (pages-of (:pages more))}]}
            (-> []
                (bad (> (count order) 2) "the string has more than two pages")
                (bad (= [(hex (first order))] (hashes-of (:pages one)))
                     "the first instant sends the first page")
                (bad (= (:id opened) (:id again)) "resuming keeps the id")
                (bad (= (hex (second order)) (first more-hex))
                     "the next page is the second in reveal order")
                (bad (not (some #{(hex (first order))} more-hex))
                     "the offered page is not sent again")))))

(defn- release []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        opened (reveal/open-reveal (reveal/session) st h 0 fast)
        taken (reveal/take-pages (:session opened) st 1 fast)
        released (reveal/forget-pages (:session taken) st [h])
        again (reveal/take-pages released st 2 fast)
        ack-msg (thrown-msg #(reveal/ack-pages (reveal/session) [h]))]
    (reveal {:id "release"
             :root (hex h)
             :steps [{:label "taken" :pages (pages-of (:pages taken))}
                     {:label "again" :pages (pages-of (:pages again))}]
             :ack-needs-offer (boolean (and ack-msg (str/includes? ack-msg "offered")))}
            (-> []
                (bad (= [(hex h)] (hashes-of (:pages taken))) "the page is offered")
                (bad (= [(hex h)] (hashes-of (:pages again))) "release sends that page again")
                (bad (boolean (and ack-msg (str/includes? ack-msg "offered")))
                     "ack of a page that was not offered fails")))))

(defn- ack-evict []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        opened (reveal/open-reveal (reveal/session) st h 0 fast)
        taken (reveal/take-pages (:session opened) st 1 fast)
        held (reveal/ack-pages (:session taken) [h])
        quiet (reveal/take-pages held st 2 fast)
        evicted (reveal/forget-pages held st [h])
        again (reveal/take-pages evicted st 3 fast)]
    (reveal {:id "ack-evict"
             :root (hex h)
             :steps [{:label "taken" :pages (pages-of (:pages taken))}
                     {:label "quiet" :pages (pages-of (:pages quiet))}
                     {:label "again" :pages (pages-of (:pages again))}]}
            (-> []
                (bad (= [(hex h)] (hashes-of (:pages taken))) "the page is offered")
                (bad (empty? (:pages quiet)) "a held page is not sent again")
                (bad (= [(hex h)] (hashes-of (:pages again)))
                     "eviction sends the page again")))))

(defn- floor-instant []
  (let [st (store/mem-store)
        ha (v/hash (v/i64 st 1))
        hb (v/hash (v/i64 st 2))
        params {:peak 1.0 :tau 1.0 :floor (host/exp -1.0) :r-max 1.0}
        a (reveal/open-reveal (reveal/session) st ha 0 params)
        b (reveal/open-reveal (:session a) st hb 0.5 params)
        at-one (reveal/take-pages (:session b) st 1.0 params)
        credit (fn [sess id]
                 (:credit (first (filter #(= id (:id %)) (:reveals sess)))))
        c1 (credit (:session at-one) (:id b))
        later (reveal/take-pages (:session at-one) st 2.0 params)
        c2 (credit (:session later) (:id b))
        rose (> c2 (+ c1 1.0e-6))]
    (reveal {:id "floor-instant"
             :roots [(hex ha) (hex hb)]
             :credit-rose rose
             :steps [{:label "at-one" :pages (pages-of (:pages at-one))}
                     {:label "later" :pages (pages-of (:pages later))}]}
            (bad [] rose "the floor at a power of two does not stop the other reveal"))))

;; ---------------------------------------------------------------------------
;; Pack
;; ---------------------------------------------------------------------------

(defn- literal-decision
  [id build]
  (let [st (store/mem-store)
        h (v/hash (build st))
        form (pack/literal-of st h)
        st2 (store/mem-store)
        got (pack/materialize-literal! st2 (:type form) (:body form))
        {:keys [items]} (pack/encode-reachable st h)
        root-hex (hex h)]
    (pack-case {:id id
                :root root-hex
                :round-trip (hex got)
                :form (edn-safe form)
                :encodings (mapv #(name (:encoding %)) items)
                :wire-bytes (wire-bytes items)
                :chunk-hex (chunk-hex items pack/default-budget)}
               (bad [] (= root-hex (hex got)) "literal round trip hash differs"))))

(defn- i64-literal []
  (literal-decision "i64" #(v/i64 % 42)))

(defn- string-hello []
  (literal-decision "string-hello" #(v/string % "hello")))

(defn- vector-123 []
  (literal-decision "vector-123" #(v/vector % 1 2 3)))

(defn- vector-strings []
  (literal-decision "vector-strings" #(v/vector % "hello" "world")))

(defn- map-ab []
  (literal-decision "map-ab" #(v/map % "a" 1 "b" 2)))

(defn- set-123 []
  (literal-decision "set-123" #(v/set % 1 2 3)))

(defn- empty-string []
  (literal-decision "empty-string" #(v/string % "")))

(defn- empty-vector-pack []
  (literal-decision "empty-vector-pack" #(v/vector %)))

(defn- empty-map []
  (literal-decision "empty-map" #(v/map %)))

(defn- empty-set []
  (literal-decision "empty-set" #(v/set %)))

(defn- string-100 []
  (literal-decision "string-100" #(v/string % (apply str (repeat 100 "x")))))

(defn- bool-repeat []
  (literal-decision "bool-repeat" #(apply v/vector % (repeat 8 false))))

(defn- mixed-vector []
  (literal-decision "mixed-vector" #(v/vector % 1 2 "z")))

(defn- char-repeat []
  (let [st (store/mem-store)
        chs (mapv #(types/dacite-hash (scalar/dacite-char-with-store st %))
                  (repeat 24 \x))
        h (ft/ft-node-from-value-hashes st chs)
        form (pack/literal-of st h)
        item (pack/encode-item st h (store/await (store/s-get st h)))
        st2 (store/mem-store)
        got (pack/materialize-literal! st2 (:type form) (:body form))
        rep (first (:body form))]
    (pack-case {:id "char-repeat"
                :root (hex h)
                :type (:type form)
                :round-trip (hex got)
                :form (edn-safe form)
                :encoding (name (:encoding item))
                :wire-bytes (item-wire-bytes item)}
               (-> []
                   (bad (= (hex h) (hex got)) "char repeat round trip hash differs")
                   (bad (= "repeat" (:type rep)) "equal chars are a repeat")
                   (bad (= "char" (get-in rep [:body :of])) "the repeat is chars")
                   (bad (= 24 (get-in rep [:body :n])) "the repeat is 24 chars")))))

(defn- pack-budget []
  (let [items (mapv (fn [i]
                      (pack/node-item
                       (store/hex->hash (str (apply str (repeat 62 "0"))
                                             (host/byte->hex i)))
                       ["i64" i]))
                    (range 20))
        chunks (pack/pack-items items 200)]
    (pack-case {:id "pack-budget"
                :chunks (count chunks)
                :items (mapv #(count (:items %)) chunks)
                :wire-bytes (whole (reduce + 0 (map #(bin/byte-len (bin/encode-pack-edn %)) chunks)))
                :chunk-hex (mapv #(bin/bytes->hex (bin/encode-pack-edn %)) chunks)}
               (-> []
                   (bad (pos? (count chunks)) "the budget produces a chunk")
                   (bad (= 20 (reduce + (map #(count (:items %)) chunks)))
                        "every item is in some chunk")))))

(defn- large-string []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 12000 "x"))))
        entry (store/await (store/s-get st h))
        item (pack/encode-item st h entry)
        {:keys [items covered]} (pack/encode-reachable st h)
        encodings (mapv #(name (:encoding %)) items)]
    (pack-case {:id "large-string"
                :root (hex h)
                :root-encoding (name (:encoding item))
                :fits-literal (boolean (pack/fits-literal? st h))
                :oversized (boolean (pack/clearly-oversized? entry pack/default-budget))
                :items (count items)
                :literals (count (filter #{"literal"} encodings))
                :nodes (count (filter #{"node"} encodings))
                :chunks (count (pack/pack-items items))
                :covered (count covered)
                :wire-bytes (wire-bytes items)}
               (-> []
                   (bad (= :node (:encoding item)) "a long string root is a node item")
                   (bad (false? (boolean (pack/fits-literal? st h))) "the literal does not fit")
                   (bad (pos? (count (filter #{"node"} encodings))) "the walk emits a node")
                   (bad (pos? (count (filter #{"literal"} encodings)))
                        "a shared leaf is still a literal")
                   (bad (> (count items) 1) "the walk expands the spine")))))

(defn- vector-tight []
  (let [st (store/mem-store)
        value (apply v/vector st (map #(str "item-" %) (range 40)))
        h (v/hash value)
        entry (store/await (store/s-get st h))
        item (pack/encode-item st h entry 200)
        {:keys [items]} (pack/encode-reachable st h #{} 200)
        encodings (mapv #(name (:encoding %)) items)]
    (pack-case {:id "vector-tight"
                :root (hex h)
                :root-encoding (name (:encoding item))
                :items (count items)
                :literals (count (filter #{"literal"} encodings))
                :nodes (count (filter #{"node"} encodings))
                :wire-bytes (wire-bytes items)}
               (-> []
                   (bad (= :node (:encoding item)) "a tight budget keeps the vector a node")
                   (bad (pos? (count (filter #{"literal"} encodings)))
                        "the short strings are literals")))))

(defn- canonical []
  (let [st (store/mem-store)
        h (v/hash (parity/canonical-value st))
        {:keys [items covered]} (pack/encode-reachable st h)
        root-hex (hex h)
        same (= root-hex (parity/canonical-hex))]
    (pack-case {:id "canonical"
                :root root-hex
                :same-root same
                :items (count items)
                :encodings (mapv #(name (:encoding %)) items)
                :chunks (count (pack/pack-items items))
                :covered (count covered)
                :wire-bytes (wire-bytes items)
                :chunk-hex (chunk-hex items pack/default-budget)}
               (bad [] same "canonical value hash differs from dacite.examples.parity"))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(def ^:private case-table
  [["scalar" scalar]
   ["empty-vector" empty-vector]
   ["inlined-scalars" inlined-scalars]
   ["bare-leaf" bare-leaf]
   ["nested-vectors" nested-vectors]
   ["long-string" long-string]
   ["map-order" map-order]
   ["set-order" set-order]
   ["omit-sent" omit-sent]
   ["interest" interest]
   ["shares" shares]
   ["one-page" one-page]
   ["reveal-order" reveal-order]
   ["equal-shares" equal-shares]
   ["below-floor" below-floor]
   ["budget" budget]
   ["reask-stall" reask-stall]
   ["child-focus" child-focus]
   ["cancel" cancel]
   ["already-sent" already-sent]
   ["newer" newer]
   ["clock-rejects" clock-rejects]
   ["resume" resume]
   ["release" release]
   ["ack-evict" ack-evict]
   ["floor-instant" floor-instant]
   ["i64" i64-literal]
   ["string-hello" string-hello]
   ["vector-123" vector-123]
   ["vector-strings" vector-strings]
   ["map-ab" map-ab]
   ["set-123" set-123]
   ["empty-string" empty-string]
   ["empty-vector-pack" empty-vector-pack]
   ["empty-map" empty-map]
   ["empty-set" empty-set]
   ["string-100" string-100]
   ["bool-repeat" bool-repeat]
   ["mixed-vector" mixed-vector]
   ["char-repeat" char-repeat]
   ["pack-budget" pack-budget]
   ["large-string" large-string]
   ["vector-tight" vector-tight]
   ["canonical" canonical]])

(defn- run-one
  [id f]
  (try
    (let [out (f)
          got (get-in out [:decision :id])]
      (if (= id got)
        out
        {:kind (:kind out)
         :decision (:decision out)
         :problems (conj (vec (:problems out))
                         (str id " decision is labeled " got))}))
    (catch #?(:clj Throwable :cljs :default) e
      {:kind :reveal
       :decision {:id id :threw true}
       :problems [(str id " threw " (or (ex-message e) e))]})))

(defn- evaluate
  "Run every case, or only the ids in `only`. Behavior problems only;
   the golden is applied by `problems`."
  [only]
  (let [ran (into []
                  (keep (fn [[id f]]
                          (when (or (nil? only) (contains? only id))
                            (run-one id f))))
                  case-table)
        root (parity/canonical-hex)
        reveal-rows (mapv :decision (filter #(and (= :reveal (:kind %)) (:decision %)) ran))
        pack-rows (mapv :decision (filter #(and (= :pack (:kind %)) (:decision %)) ran))]
    {:report {:canonical-root root
              :reveal reveal-rows
              :pack pack-rows}
     :problems (into [] (mapcat :problems) ran)}))

(defn- clip
  [s]
  (let [s (str s)]
    (if (> (count s) 240) (str (subs s 0 240) "...") s)))

(defn- first-diff
  [path a b]
  (cond
    (= a b) nil
    (and (map? a) (map? b))
    (let [ak (set (keys a))
          bk (set (keys b))]
      (or (some (fn [k]
                  (cond
                    (not (contains? bk k)) (str path " missing " (pr-str k))
                    :else (first-diff (str path "/" (pr-str k)) (get a k) (get b k))))
                (keys a))
          (some (fn [k]
                  (when-not (contains? ak k)
                    (str path " unexpected " (pr-str k))))
                (keys b))))
    (and (vector? a) (vector? b))
    (if (not= (count a) (count b))
      (str path " length " (count a) " vs " (count b))
      (some (fn [i]
              (first-diff (str path "[" i "]") (nth a i) (nth b i)))
            (range (count a))))
    :else (str path " got " (clip a) " expected " (clip b))))

(defn- by-id
  [rows]
  (into {} (map (fn [row] [(:id row) row])) rows))

(defn- diff-rows
  [kind got expected only]
  (let [g (by-id got)
        e (by-id expected)
        ids (if only
              (sort only)
              (sort (set (concat (keys g) (keys e)))))]
    (into []
          (keep (fn [id]
                  (cond
                    (and (contains? g id) (not (contains? e id)))
                    (str kind " " id " is not in the golden")

                    (and (contains? e id) (not (contains? g id)))
                    (str kind " " id " missing on this host")

                    (and (contains? g id) (contains? e id) (not= (get g id) (get e id)))
                    (str kind " " (or (first-diff id (get g id) (get e id))
                                      (str id " differs"))))))
          ids)))

(defn- against-golden
  "Behavior problems plus mismatches against the golden. `report` and
   `behavior` come from `evaluate`."
  [report behavior only]
  (let [golden (try
                 (read-golden)
                 (catch #?(:clj Throwable :cljs :default) _e
                   ::missing))]
    (if (= golden ::missing)
      (conj (vec behavior)
            "fixtures/parity/decisions.edn is missing (clojure -M:parity --write)")
      (let [root-problem (when (and (or (nil? only) (contains? only "canonical"))
                                    (not= (:canonical-root report)
                                          (:canonical-root golden)))
                           [(str "canonical root got " (:canonical-root report)
                                 " expected " (:canonical-root golden))])
            ids (when (and (nil? only)
                           (or (not= (mapv :id (:reveal report))
                                     (mapv :id (:reveal golden)))
                               (not= (mapv :id (:pack report))
                                     (mapv :id (:pack golden)))))
                  ["case id order differs from the golden"])]
        (into []
              (concat behavior
                      root-problem
                      ids
                      (diff-rows "reveal" (:reveal report) (:reveal golden) only)
                      (diff-rows "pack" (:pack report) (:pack golden) only)))))))

(defn problems
  "Behavior failures and golden mismatches. `only` is a set of case ids,
   or nil to run the whole suite."
  ([] (problems nil))
  ([only]
   (let [{:keys [report] behavior :problems} (evaluate only)]
     (against-golden report behavior only))))

(defn- canonical-text
  "One string for a decision. Map keys are sorted so both hosts digest
   the same bytes."
  [x]
  (cond
    (nil? x) "nil"
    (true? x) "true"
    (false? x) "false"
    (keyword? x) (str x)
    (string? x) (pr-str x)
    (integer? x) (str #?(:clj (long x) :cljs x))
    (map? x)
    (str "{"
         (str/join ","
                   (map (fn [[k v]]
                          (str (canonical-text k) " " (canonical-text v)))
                        (sort-by (fn [[k _]] (canonical-text k)) (seq x))))
         "}")
    (sequential? x)
    (str "[" (str/join "," (map canonical-text x)) "]")
    :else (throw (ex-info "cannot digest this decision" {:value (str x)}))))

(defn- digest
  [text]
  (host/longs->hex
   (reduce (fn [words b]
             (let [b (host/word b)
                   w0 (host/add64 (host/mul64 (nth words 0) (host/word 131)) b)
                   w1 (host/bxor64 (nth words 1) w0)
                   w2 (host/add64 (nth words 2) (host/word 1))
                   w3 (host/bxor64 (host/mul64 (nth words 3) (host/word 167)) b)]
               [w0 w1 w2 w3]))
           host/zero-hash
           (host/utf8-bytes text))))

(defn -main
  [& args]
  (let [write? (boolean (some #{"--write"} args))
        {:keys [report] behavior :problems} (evaluate nil)]
    (cond
      (and write? (seq behavior))
      (do
        (doseq [p behavior] (println p))
        #?(:clj (System/exit 1)
           :cljs (js/process.exit 1)))

      write?
      (do
        #?(:clj (println (str "wrote " (write-golden! report)))
           :cljs (do (println "--write is JVM-only")
                     (js/process.exit 1)))
        #?(:clj (System/exit 0)))

      :else
      (let [ps (against-golden report behavior nil)
            reveal-n (count (:reveal report))
            pack-n (count (:pack report))]
        (println (str "parity-digest: " (digest (canonical-text report))))
        (println (str "parity-reveal: " reveal-n))
        (println (str "parity-pack: " pack-n))
        (println (str "parity-root: " (:canonical-root report)))
        (if (seq ps)
          (do
            (doseq [p ps] (println p))
            #?(:clj (System/exit 1)
               :cljs (js/process.exit 1)))
          (println "parity ok"))))))
