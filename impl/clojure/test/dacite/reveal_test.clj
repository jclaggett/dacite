(ns dacite.reveal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]))

(defn- type-of [store h]
  (types/entry-type (store/require-node store h)))

(defn- idx [order h]
  (.indexOf ^clojure.lang.PersistentVector (vec order) h))

(deftest scalar-is-one-node
  (let [s (store/mem-store)
        n (v/i64 s 42)
        h (v/hash n)]
    (is (= [h] (reveal/page-order s h)))))

(deftest empty-vector-has-no-element-body
  (let [s (store/mem-store)
        h (v/hash (v/vector s))
        order (reveal/page-order s h)]
    (is (= h (first order)))
    (is (every? #(or (= "vector" %) (str/starts-with? % "ft/"))
                (map #(type-of s %) order)))))

(deftest inlined-scalars-stay-on-their-page
  (let [s (store/mem-store)
        nums (mapv #(v/i64 s %) (range 40))
        order (reveal/page-order s (v/hash (apply v/vector s nums)))
        types (mapv #(type-of s %) order)]
    (is (= "vector" (first types)))
    (is (some #(str/starts-with? % "ft/") types))
    (is (not (some #{"i64"} types)))))

(deftest bare-leaf-root-is-still-sent
  (let [s (store/mem-store)
        n (v/i64 s 7)
        order (reveal/page-order s (v/hash (v/vector s n)))]
    (is (= ["vector" "i64"] (mapv #(type-of s %) order)))
    (is (= (v/hash n) (second order)))))

(deftest vector-owns-its-pages-before-a-nested-body
  (let [s (store/mem-store)
        a (v/vector s (v/i64 s 1))
        b (v/vector s (v/i64 s 2))
        outer (v/vector s a b)
        order (reveal/page-order s (v/hash outer))
        ia (idx order (v/hash a))
        ib (idx order (v/hash b))]
    (is (= (v/hash outer) (first order)))
    (is (< 0 ia ib))
    (doseq [page (rest (reveal/page-order s (v/hash a)))]
      (let [ip (idx order page)]
        (is (< ia ip ib))))
    (is (= (count order) (count (set order))))))

(deftest long-string-pages-are-first-most
  (let [s (store/mem-store)
        text (str (apply str (repeat 2000 "a"))
                  (apply str (repeat 2000 "z")))
        h (v/hash (v/string s text))
        order (reveal/page-order s h)
        digits (filterv #(= "ft/digit" (type-of s %)) order)
        leaves (fn [page]
                 (mapv #(v/native (v/wrap-hash s %))
                       (ft/ft-leaves s page)))]
    (is (= "string" (type-of s (first order))))
    (is (> (count digits) 1))
    (is (= \a (first (leaves (first digits)))))
    (is (not (some #{\z} (leaves (first digits)))))
    (is (= \z (peek (leaves (peek digits)))))))

(defn- hash-rank
  "HAMT routing key: 5-bit chunks of the hash from the top."
  [h]
  (mapv #(hamt/hash-chunk h %) (range 52)))

(deftest map-sends-keys-before-values-in-hamt-order
  (let [s (store/mem-store)
        keys [(v/i64 s 1) (v/i64 s 100) (v/i64 s 7)]
        vals [(v/string s "a") (v/string s "b") (v/string s "c")]
        m (apply v/map s (interleave keys vals))
        order (reveal/page-order s (v/hash m))
        ordered (sort-by #(hash-rank (v/hash %)) keys)
        key-at (mapv #(idx order (v/hash %)) ordered)
        val-at (mapv #(idx order (v/hash %))
                     (map (zipmap keys vals) ordered))]
    (is (= (v/hash m) (first order)))
    (is (apply < key-at))
    (is (every? #(< (apply max key-at) %) val-at))
    (is (= (count order) (count (set order))))))

(deftest set-sends-each-element-once
  (let [s (store/mem-store)
        a (v/i64 s 1)
        b (v/i64 s 2)
        c (v/i64 s 3)
        st (v/set s a b c)
        order (reveal/page-order s (v/hash st))
        ordered (sort-by #(hash-rank (v/hash %)) [a b c])
        at (mapv #(idx order (v/hash %)) ordered)]
    (is (apply < at))
    (is (= 1 (count (filter #{(v/hash a)} order))))
    (is (= 1 (count (filter #{(v/hash b)} order))))
    (is (= 1 (count (filter #{(v/hash c)} order))))))

(deftest omit-sent-keeps-unsent-order
  (is (= [:b :d] (reveal/omit-sent [:a :b :c :d] #{:a :c}))))

(deftest interest-decays-from-the-peak
  (is (= 4.0 (reveal/interest 4 10 0)))
  (is (< (Math/abs (- (/ 4.0 Math/E) (reveal/interest 4 10 10))) 1e-9))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tau"
                        (reveal/interest 1 0 1))))

(deftest shares-follow-age-floor-and-cancel
  (testing "the newer request takes the larger share"
    (let [sh (reveal/shares [{:id :old :opened-at 0}
                             {:id :new :opened-at 10}]
                            10
                            {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0})]
      (is (> (:new sh) (:old sh)))
      (is (< (Math/abs (- 100.0 (+ (:new sh) (:old sh)))) 1e-6))))
  (testing "below the floor the reveal drops out and the other takes the ceiling"
    (let [sh (reveal/shares [{:id :old :opened-at 0}
                             {:id :new :opened-at 100}]
                            100
                            {:peak 1.0 :tau 10.0 :floor 0.05 :r-max 80.0})]
      (is (= [:new] (keys sh)))
      (is (< (Math/abs (- 80.0 (:new sh))) 1e-6))))
  (testing "cancel drops that share at once"
    (let [sh (reveal/shares [{:id :a :opened-at 0 :cancelled? true}
                             {:id :b :opened-at 0}]
                            0
                            {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 50.0})]
      (is (= [:b] (keys sh)))
      (is (< (Math/abs (- 50.0 (:b sh))) 1e-9))))
  (testing "no reveal in the sum"
    (is (= {} (reveal/shares [{:id :a :opened-at 0 :done? true}]
                             0
                             {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 10.0}))))
  (testing "a lone reveal decays from the ceiling"
    (let [sh (reveal/shares [{:id :only :opened-at 0}]
                            10
                            {:peak 1.0 :tau 10.0 :floor 0.0 :r-max 100.0})]
      (is (< (Math/abs (- (* 100.0 (Math/exp -1.0)) (:only sh))) 1.0e-6)))))

(deftest next-page-goes-to-the-largest-share
  (let [params {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0}
        reveals [{:id :old :opened-at 0}
                 {:id :new :opened-at 10}]]
    (is (= :new (reveal/next-reveal reveals 10 params)))
    (is (nil? (reveal/next-reveal [{:id :old :opened-at 0 :cancelled? true}]
                                  0 params)))))

(def ^:private fast
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e12})

(defn- hashes-of [pages]
  (mapv :hash pages))

(defn- lone-page-at
  "Time a lone reveal, opened at 0, takes to earn `fraction` pages.

   `r-max` is one page per unit of time. Credit in pages is
   `tau * (1 - exp(-t / tau))`."
  [tau fraction]
  (* (- tau) (Math/log (- 1.0 (/ fraction tau)))))

(deftest one-page-waits-until-its-share-covers-it
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        n (reveal/page-bytes s h)
        tau 100.0
        params {:peak 1.0 :tau tau :floor 0.0 :r-max (double n)}
        at (lone-page-at tau 1.0)
        opened (reveal/open-reveal (reveal/session) s h 0 params)
        early (reveal/take-pages (:session opened) s 0.5 params)
        short (reveal/take-pages (:session early) s 1.0 params)
        due (reveal/take-pages (:session short) s at params)]
    (is (= 1 (:id opened)))
    (is (= 1 (count (:pages (first (get-in opened [:session :reveals]))))))
    (is (empty? (:pages early)))
    (is (empty? (:pages short)))
    (is (= [h] (hashes-of (:pages due))))
    (is (= n (:bytes (first (:pages due)))))
    (is (< (Math/abs (- at (:at (first (:pages due))))) 1.0e-4))
    (is (contains? (get-in due [:session :offered]) h))
    (is (not (contains? (get-in due [:session :sent]) h)))
    (let [again (reveal/open-reveal (:session due) s h at params)
          later (reveal/take-pages (:session again) s 20 params)]
      (is (empty? (:pages later))))))

(deftest pages-come-out-in-reveal-order
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 2000 "a"))))
        order (reveal/page-order s h)
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)]
    (is (> (count order) 1))
    (is (= 1 (count (:pages (first (get-in opened [:session :reveals]))))))
    (is (= order (hashes-of (:pages taken))))
    (is (:done? (first (get-in taken [:session :reveals]))))))

(deftest equal-shares-send-one-page-each
  (let [s (store/mem-store)
        ha (v/hash (v/i64 s 1))
        hb (v/hash (v/i64 s 2))
        n (reveal/page-bytes s ha)
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max (double n)}
        a (reveal/open-reveal (reveal/session) s ha 0 params)
        b (reveal/open-reveal (:session a) s hb 0 params)
        taken (reveal/take-pages (:session b) s 2 params)]
    (is (= [(:id a) (:id b)] (mapv :id (:pages taken))))
    (is (= [ha hb] (hashes-of (:pages taken))))
    (is (every? #(< (Math/abs (- 2.0 (:at %))) 1.0e-6) (:pages taken)))))

(deftest below-the-floor-pauses-until-the-hash-is-asked-again
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        n (double (reveal/page-bytes s h))
        params {:peak 1.0 :tau 1.0 :floor (Math/exp -0.5) :r-max n}
        ;; Each run from the peak to this floor lasts 0.5 and banks
        ;; (1 - exp(-0.5)) pages. Two runs are still short of a page.
        ;; The third covers the rest before that floor.
        banked (* n (- 1.0 (Math/exp -0.5)))
        need (- 1.0 (* 2.0 (- 1.0 (Math/exp -0.5))))
        waited (- (Math/log (- 1.0 need)))
        opened (reveal/open-reveal (reveal/session) s h 0 params)
        paused (reveal/take-pages (:session opened) s 10 params)
        again (reveal/open-reveal (:session paused) s h 10 params)
        still (reveal/take-pages (:session again) s 10.5 params)
        resumed (reveal/open-reveal (:session still) s h 20 params)
        due (reveal/take-pages (:session resumed) s (+ 20.0 waited) params)
        credit (:credit (first (get-in still [:session :reveals])))]
    (is (empty? (:pages paused)))
    (is (empty? (:pages again)))
    (is (empty? (:pages still)))
    (is (= (:id opened) (:id again) (:id resumed)))
    (is (< (Math/abs (- (* 2.0 banked) credit)) 1.0e-4))
    (is (= [h] (hashes-of (:pages due))))
    (is (< (Math/abs (- (+ 20.0 waited) (:at (first (:pages due))))) 1.0e-4))))

(deftest cancel-drops-the-rest-of-that-reveal
  (let [s (store/mem-store)
        ha (v/hash (v/i64 s 1))
        hb (v/hash (v/i64 s 2))
        n (double (reveal/page-bytes s ha))
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max n}
        a (reveal/open-reveal (reveal/session) s ha 0 params)
        b (reveal/open-reveal (:session a) s hb 0 params)
        stopped (reveal/cancel-reveal (:session b) s (:id b) 1 params)
        taken (reveal/take-pages (:session stopped) s 10 params)
        reopened (reveal/open-reveal (:session taken) s hb 10 params)
        rest-pages (reveal/take-pages (:session reopened) s 12 params)]
    (is (empty? (:pages stopped)))
    (is (= [ha] (hashes-of (:pages taken))))
    (is (not= (:id b) (:id reopened)))
    (is (= [hb] (hashes-of (:pages rest-pages))))))

(deftest a-hash-already-sent-is-not-sent-again
  (let [s (store/mem-store)
        a (v/vector s (v/i64 s 1))
        b (v/vector s (v/i64 s 2))
        outer (v/vector s a b)
        sent (reveal/open-reveal (reveal/session) s (v/hash a) 0 fast)
        done (reveal/take-pages (:session sent) s 1 fast)
        held (reveal/ack-pages (:session done) (hashes-of (:pages done)))
        more (reveal/open-reveal held s (v/hash outer) 1 fast)
        pages (reveal/take-pages (:session more) s 2 fast)]
    (is (some #{(v/hash a)} (hashes-of (:pages done))))
    (is (not (some #{(v/hash a)} (hashes-of (:pages pages)))))
    (is (some #{(v/hash outer)} (hashes-of (:pages pages))))
    (is (some #{(v/hash b)} (hashes-of (:pages pages))))))

(deftest a-newer-reveal-gets-the-next-page
  (let [s (store/mem-store)
        book (v/hash (v/string s (apply str (repeat 3000 "a"))))
        chapter (v/hash (v/string s (apply str (repeat 3000 "z"))))
        nbytes (double (reveal/page-bytes s book))
        tau 10.0
        ;; Integral of a lone decaying ceiling reaches one page at tau * ln 2
        ;; when r-max * tau = 2 pages.
        at (* tau (Math/log 2.0))
        params {:peak 1.0 :tau tau :floor 1.0e-9 :r-max (/ nbytes 5.0)}
        opened (reveal/open-reveal (reveal/session) s book 0 params)
        at-first (reveal/take-pages (:session opened) s at params)
        ch (reveal/open-reveal (:session at-first) s chapter at params)
        rates (reveal/shares (get-in ch [:session :reveals]) at params)
        nxt (reveal/take-pages (:session ch) s 1000 params)]
    (is (= 1 (count (:pages at-first))))
    (is (< (Math/abs (- at (:at (first (:pages at-first))))) 1.0e-4))
    (is (> (get rates (:id ch)) (get rates (:id opened))))
    (is (= (:id ch) (:id (first (:pages nxt)))))))

(deftest the-session-clock-moves-forward
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 1))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"clock"
                          (reveal/take-pages (:session opened) s -1 fast)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"r-max"
                          (reveal/take-pages (:session opened) s 1
                                             (assoc fast :r-max -1))))))

(deftest a-multi-page-reveal-resumes-after-the-pages-already-offered
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 2000 "a"))))
        order (reveal/page-order s h)
        tau 1.0e6
        r-max 1000.0
        n0 (double (reveal/page-bytes s (first order)))
        n1 (double (reveal/page-bytes s (second order)))
        cover (fn [bytes]
                (* (- tau) (Math/log (- 1.0 (/ bytes (* r-max tau))))))
        t1 (cover n0)
        t2 (cover (+ n0 n1))
        params {:peak 1.0 :tau tau :floor 0.0 :r-max r-max}
        opened (reveal/open-reveal (reveal/session) s h 0 params)
        one (reveal/take-pages (:session opened) s t1 params)
        again (reveal/open-reveal (:session one) s h t1 params)
        more (reveal/take-pages (:session again) s (+ t2 1.0) params)]
    (is (> (count order) 2))
    (is (= [(first order)] (hashes-of (:pages one))))
    (is (= (:id opened) (:id again)))
    (is (= (second order) (first (hashes-of (:pages more)))))
    (is (not (some #{(first order)} (hashes-of (:pages more)))))))

(deftest releasing-an-offer-sends-that-page-again
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)
        released (reveal/release-pages (:session taken) s [h])
        again (reveal/take-pages released s 2 fast)]
    (is (= [h] (hashes-of (:pages taken))))
    (is (= [h] (hashes-of (:pages again))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"offered"
                          (reveal/ack-pages (reveal/session) [h])))))

(deftest an-acknowledged-page-is-sent-again-after-eviction
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)
        held (reveal/ack-pages (:session taken) [h])
        quiet (reveal/take-pages held s 2 fast)
        evicted (reveal/release-pages held s [h])
        again (reveal/take-pages evicted s 3 fast)]
    (is (contains? (:sent held) h))
    (is (empty? (:pages quiet)))
    (is (= [h] (hashes-of (:pages again))))))

(deftest the-floor-is-a-single-instant
  (let [s (store/mem-store)
        ha (v/hash (v/i64 s 1))
        hb (v/hash (v/i64 s 2))
        params {:peak 1.0 :tau 1.0 :floor (Math/exp -1.0) :r-max 1.0}
        a (reveal/open-reveal (reveal/session) s ha 0 params)
        b (reveal/open-reveal (:session a) s hb 0.5 params)
        at-one (reveal/take-pages (:session b) s 1.0 params)
        credit (fn [sess id]
                 (:credit (first (filter #(= id (:id %))
                                         (:reveals sess)))))
        c1 (credit (:session at-one) (:id b))
        later (reveal/take-pages (:session at-one) s 2.0 params)
        c2 (credit (:session later) (:id b))]
    ;; A's floor is t = 1, a power of two. Stepping past that instant is
    ;; what lets B keep earning. A nudge that stays on t = 1 stops the clock.
    (is (> c2 (+ c1 1.0e-6)))))
