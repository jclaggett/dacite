(ns dacite.reveal-cljs-test
  "nbb side of the reveal engine. Run from the repo root:

     npm run reveal:test

   The cases are the pure functions in `reveal_test.clj` (page order,
   omit-sent, interest, shares, next-reveal) plus the three session-clock
   cases that depend on `next-up` and the exponential integral. The rest
   of the session tests stay on the JVM until M2.3."
  (:require [clojure.string :as str]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]))

(defn- fail!
  [msg data]
  (throw (ex-info msg data)))

(defn- eq!
  [actual expected msg]
  (when-not (= actual expected)
    (fail! msg {:actual actual :expected expected})))

(defn- ok!
  [v msg]
  (when-not v
    (fail! msg {})))

(defn- near!
  [actual expected msg]
  (when-not (< (js/Math.abs (- actual expected)) 1.0e-6)
    (fail! msg {:actual actual :expected expected})))

(defn- throws-msg!
  [f re msg]
  (try
    (f)
    (fail! msg {:expected (str re)})
    (catch :default e
      (when-not (and (ex-message e) (re-find re (ex-message e)))
        (fail! msg {:message (ex-message e)})))))

(defn- type-of [store h]
  (types/entry-type (store/require-node store h)))

(defn- hashes-once?
  "True when `order` lists each hash once. Hex, because a set of BigInt
   hashes throws on nbb once it grows past eight."
  [order]
  (= (count order) (count (set (map store/hash->hex order)))))

(defn- idx [order h]
  (let [order (vec order)]
    (loop [i 0]
      (cond
        (= i (count order)) -1
        (= (nth order i) h) i
        :else (recur (inc i))))))

(defn- hash-rank
  [h]
  (mapv #(hamt/hash-chunk h %) (range 52)))

(defn- scalar-is-one-node
  []
  (let [s (store/mem-store)
        n (v/i64 s 42)
        h (v/hash n)]
    (eq! (reveal/page-order s h) [h] "a scalar is one node")))

(defn- empty-vector-has-no-element-body
  []
  (let [s (store/mem-store)
        h (v/hash (v/vector s))
        order (reveal/page-order s h)]
    (eq! (first order) h "the vector header is first")
    (ok! (every? #(or (= "vector" %) (str/starts-with? % "ft/"))
                 (map #(type-of s %) order))
         "an empty vector has no element body")))

(defn- inlined-scalars-stay-on-their-page
  []
  (let [s (store/mem-store)
        nums (mapv #(v/i64 s %) (range 40))
        order (reveal/page-order s (v/hash (apply v/vector s nums)))
        types (mapv #(type-of s %) order)]
    (eq! (first types) "vector" "the vector header is first")
    (ok! (some #(str/starts-with? % "ft/") types) "the vector has pages")
    (ok! (not (some #{"i64"} types)) "inlined scalars stay on their page")))

(defn- bare-leaf-root-is-still-sent
  []
  (let [s (store/mem-store)
        n (v/i64 s 7)
        order (reveal/page-order s (v/hash (v/vector s n)))]
    (eq! (mapv #(type-of s %) order) ["vector" "i64"] "a bare leaf is sent")
    (eq! (second order) (v/hash n) "the leaf is the element")))

(defn- vector-owns-its-pages-before-a-nested-body
  []
  (let [s (store/mem-store)
        a (v/vector s (v/i64 s 1))
        b (v/vector s (v/i64 s 2))
        outer (v/vector s a b)
        order (reveal/page-order s (v/hash outer))
        ia (idx order (v/hash a))
        ib (idx order (v/hash b))]
    (eq! (first order) (v/hash outer) "the outer vector is first")
    (ok! (< 0 ia ib) "nested vectors follow the parent")
    (doseq [page (rest (reveal/page-order s (v/hash a)))]
      (let [ip (idx order page)]
        (ok! (< ia ip ib) "a nested vector owns its pages before the next body")))
    (ok! (hashes-once? order) "a hash is listed once")))

(defn- long-string-pages-are-first-most
  []
  (let [s (store/mem-store)
        text (str (apply str (repeat 4096 "a"))
                  (apply str (repeat 4096 "z")))
        h (v/hash (v/string s text))
        order (reveal/page-order s h)
        digits (filterv #(= "ft/digit" (type-of s %)) order)
        leaves (fn [page]
                 (mapv #(v/native (v/wrap-hash s %))
                       (ft/ft-leaves s page)))
        first-leaves (leaves (first digits))
        last-leaves (leaves (peek digits))]
    (eq! (type-of s (first order)) "string" "the string header is first")
    (ok! (> (count digits) 1) "a long string has more than one page")
    (eq! (first first-leaves) "a" "the first page starts with a")
    (ok! (not (some #{"z"} first-leaves)) "the first page has no z")
    (eq! (peek last-leaves) "z" "the last page ends with z")))

(defn- map-sends-keys-before-values-in-hamt-order
  []
  (let [s (store/mem-store)
        keys [(v/i64 s 1) (v/i64 s 100) (v/i64 s 7)]
        vals [(v/string s "a") (v/string s "b") (v/string s "c")]
        m (apply v/map s (interleave keys vals))
        order (reveal/page-order s (v/hash m))
        ordered (sort-by #(hash-rank (v/hash %)) keys)
        key-at (mapv #(idx order (v/hash %)) ordered)
        val-at (mapv #(idx order (v/hash %))
                     (map (zipmap keys vals) ordered))]
    (eq! (first order) (v/hash m) "the map header is first")
    (ok! (apply < key-at) "keys follow HAMT order")
    (ok! (every? #(< (apply max key-at) %) val-at) "every value follows every key")
    (ok! (hashes-once? order) "a hash is listed once")))

(defn- set-sends-each-element-once
  []
  (let [s (store/mem-store)
        a (v/i64 s 1)
        b (v/i64 s 2)
        c (v/i64 s 3)
        st (v/set s a b c)
        order (reveal/page-order s (v/hash st))
        ordered (sort-by #(hash-rank (v/hash %)) [a b c])
        at (mapv #(idx order (v/hash %)) ordered)]
    (ok! (apply < at) "set elements follow HAMT order")
    (eq! (count (filter #{(v/hash a)} order)) 1 "a is sent once")
    (eq! (count (filter #{(v/hash b)} order)) 1 "b is sent once")
    (eq! (count (filter #{(v/hash c)} order)) 1 "c is sent once")))

(defn- omit-sent-keeps-unsent-order
  []
  (eq! (reveal/omit-sent [:a :b :c :d] #{:a :c}) [:b :d]
       "omit-sent keeps the unsent order"))

(defn- interest-decays-from-the-peak
  []
  (eq! (reveal/interest 4 10 0) 4.0 "interest at age 0 is the peak")
  (near! (reveal/interest 4 10 10) (/ 4.0 js/Math.E) "one tau decays by e")
  (throws-msg! #(reveal/interest 1 0 1) #"tau" "tau must be positive"))

(defn- shares-follow-age-floor-and-cancel
  []
  (let [sh (reveal/shares [{:id :old :opened-at 0}
                           {:id :new :opened-at 10}]
                          10
                          {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0})]
    (ok! (> (:new sh) (:old sh)) "the newer request takes the larger share")
    (near! (+ (:new sh) (:old sh)) 100.0 "the shares fill the ceiling"))
  (let [sh (reveal/shares [{:id :old :opened-at 0}
                           {:id :new :opened-at 100}]
                          100
                          {:peak 1.0 :tau 10.0 :floor 0.05 :r-max 80.0})]
    (eq! (vec (keys sh)) [:new] "below the floor the reveal drops out")
    (near! (:new sh) 80.0 "the other reveal takes the ceiling"))
  (let [sh (reveal/shares [{:id :a :opened-at 0 :cancelled? true}
                           {:id :b :opened-at 0}]
                          0
                          {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 50.0})]
    (eq! (vec (keys sh)) [:b] "cancel drops that share at once")
    (near! (:b sh) 50.0 "the remaining reveal takes the ceiling"))
  (eq! (reveal/shares [{:id :a :opened-at 0 :done? true}]
                      0
                      {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 10.0})
       {}
       "no reveal in the sum")
  (let [sh (reveal/shares [{:id :only :opened-at 0}]
                          10
                          {:peak 1.0 :tau 10.0 :floor 0.0 :r-max 100.0})]
    (near! (:only sh) (* 100.0 (js/Math.exp -1.0))
           "a lone reveal decays from the ceiling")))

(defn- next-page-goes-to-the-largest-share
  []
  (let [params {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0}
        reveals [{:id :old :opened-at 0}
                 {:id :new :opened-at 10}]]
    (eq! (reveal/next-reveal reveals 10 params) :new
         "the largest share receives the next page")
    (eq! (reveal/next-reveal [{:id :old :opened-at 0 :cancelled? true}]
                             0 params)
         nil
         "a cancelled reveal receives nothing")))

(defn- lone-decay
  []
  (let [sh (reveal/shares [{:id :only :opened-at 0}]
                          1
                          {:peak 1.0 :tau 1.0 :floor 0.0 :r-max 100.0})]
    (near! (:only sh) (* 100.0 (js/Math.exp -1.0))
           "a lone reveal decays from the ceiling")))

(defn- floor-at-a-power-of-two
  []
  (let [s (store/mem-store)
        ha (v/hash (v/i64 s 1))
        hb (v/hash (v/i64 s 2))
        params {:peak 1.0 :tau 1.0 :floor (js/Math.exp -1.0) :r-max 1.0}
        a (reveal/open-reveal (reveal/session) s ha 0 params)
        b (reveal/open-reveal (:session a) s hb 0.5 params)
        at-one (reveal/take-pages (:session b) s 1.0 params)
        credit (fn [sess id]
                 (:credit (first (filter #(= id (:id %))
                                         (:reveals sess)))))
        c1 (credit (:session at-one) (:id b))
        later (reveal/take-pages (:session at-one) s 2.0 params)
        c2 (credit (:session later) (:id b))]
    (when-not (> c2 (+ c1 1.0e-6))
      (fail! "the floor at t = 1 must not stop the other reveal"
             {:before c1 :after c2}))))

(defn- page-is-later-than-the-constant-rate
  []
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        n (double (reveal/page-bytes s h))
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max n}
        opened (reveal/open-reveal (reveal/session) s h 0 params)
        short (reveal/take-pages (:session opened) s 1.0 params)
        at (* -100.0 (js/Math.log (- 1.0 (/ 1.0 100.0))))
        due (reveal/take-pages (:session short) s at params)]
    (eq! (count (:pages short)) 0 "constant-rate time is still short")
    (eq! (count (:pages due)) 1 "the integral reaches the page")
    (eq! (:hash (first (:pages due))) h "the page is the scalar")))

(def ^:private cases
  [scalar-is-one-node
   empty-vector-has-no-element-body
   inlined-scalars-stay-on-their-page
   bare-leaf-root-is-still-sent
   vector-owns-its-pages-before-a-nested-body
   long-string-pages-are-first-most
   map-sends-keys-before-values-in-hamt-order
   set-sends-each-element-once
   omit-sent-keeps-unsent-order
   interest-decays-from-the-peak
   shares-follow-age-floor-and-cancel
   next-page-goes-to-the-largest-share
   lone-decay
   floor-at-a-power-of-two
   page-is-later-than-the-constant-rate])

(defn -main
  [& _]
  (doseq [c cases]
    (c))
  (println "reveal cljs tests passed")
  (println (str (count cases) " cases")))
