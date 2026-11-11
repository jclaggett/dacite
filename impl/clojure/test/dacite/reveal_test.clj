(ns dacite.reveal-test
  "The reveal engine. The session clock, stall, re-ask, cancel, sent-once,
   and bound tests are checks in `dacite.reveal-checks`, shared with nbb
   (`npm run reveal:test`); each runs here as a deftest under its old name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.reveal :as reveal]
            [dacite.reveal-checks :as checks]
            [dacite.reveal.session-checks :as session-checks]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]))

(def ^:private env
  "Host pieces the checks take (see dacite.reveal-checks)."
  {:encode-page sr/encode-page})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(defn- type-of [store h]
  (types/entry-type (store/require-node store h)))

(defn- idx [order h]
  (.indexOf ^clojure.lang.PersistentVector (vec order) h))

(def ^:private fast
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e12})

(defn- hashes-of [pages]
  (mapv :hash pages))

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
        text (str (apply str (repeat 4096 "a"))
                  (apply str (repeat 4096 "z")))
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

(deftest one-page-waits-until-its-share-covers-it
  (run-check "one-page-waits-until-its-share-covers-it"))

(deftest pages-come-out-in-reveal-order
  (run-check "pages-come-out-in-reveal-order"))

(deftest equal-shares-send-one-page-each
  (run-check "equal-shares-send-one-page-each"))

(deftest below-the-floor-pauses-until-the-hash-is-asked-again
  (run-check "below-the-floor-pauses-until-the-hash-is-asked-again"))

(deftest a-lone-reveal-stops-when-its-budget-is-spent
  (run-check "a-lone-reveal-stops-when-its-budget-is-spent"))

(deftest a-re-ask-at-the-stall-banks-the-unspent-integral
  (run-check "a-re-ask-at-the-stall-banks-the-unspent-integral"))

(deftest a-child-focus-sends-nothing-once-the-parent-offered-its-pages
  (run-check "a-child-focus-sends-nothing-once-the-parent-offered-its-pages"))

(deftest cancel-drops-the-rest-of-that-reveal
  (run-check "cancel-drops-the-rest-of-that-reveal"))

;; JVM only for now: on nbb the outer vector's reveal (9 pages) throws, the
;; session's walk keeps raw hashes in a set (see parity.md).
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
  (run-check "a-newer-reveal-gets-the-next-page"))

(deftest the-session-clock-moves-forward
  (run-check "the-session-clock-moves-forward"))

(deftest a-multi-page-reveal-resumes-after-the-pages-already-offered
  (run-check "a-multi-page-reveal-resumes-after-the-pages-already-offered"))

(deftest releasing-an-offer-sends-that-page-again
  (run-check "releasing-an-offer-sends-that-page-again"))

(deftest an-acknowledged-page-is-sent-again-after-eviction
  (run-check "an-acknowledged-page-is-sent-again-after-eviction"))

(defn- session-check
  [f]
  (let [problems (:problems (f))]
    (is (empty? problems) (pr-str problems))))

(deftest duplicate-request-on-the-same-hash
  (session-check session-checks/duplicate-request))

(deftest paused-reveal-ends-without-an-idle-timeout
  (session-check session-checks/paused-reveal-ends))

(deftest a-lone-page-larger-than-the-budget-completes
  (session-check session-checks/page-larger-than-budget))

(deftest focused-child-receives-pages-already-offered-by-the-parent
  (session-check session-checks/focused-child-receives-offered-pages))

(deftest ack-of-an-unknown-hash-is-ignored
  (session-check session-checks/ack-ignores-unknown))

(deftest the-floor-is-a-single-instant
  (run-check "the-floor-is-a-single-instant"))

(deftest a-page-is-encoded-once
  (run-check "a-page-is-encoded-once"))

(deftest reveals-sent-and-owner-stay-bounded
  (run-check "reveals-sent-and-owner-stay-bounded"))

(deftest every-check-has-a-deftest
  (is (every? #(-> (ns-interns 'dacite.reveal-test) (get (symbol %)) meta :test)
              (keys checks/checks))
      "each check in reveal_checks.cljc has a deftest here"))
