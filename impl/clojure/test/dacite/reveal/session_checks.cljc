(ns dacite.reveal.session-checks
  "M4.1 acceptance checks for the reveal session.

   The session clock is the only clock here. Nothing waits on a wall
   timeout. JVM (`reveal_test.clj`), nbb (`npm run reveal:test`), and the
   parity golden (`dacite.parity.checks`) all run these.

   Each function returns `{:problems :decision}`. `:problems` is empty
   when the check holds. `:decision` is the host-neutral record the
   parity golden pins: strings, booleans, and integers."
  (:require [dacite.host :as host]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]))

(def ^:private fast
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e12})

(defn- whole
  [n]
  (when-not (integer? n)
    (throw (ex-info "expected an integer" {:n n})))
  #?(:clj (long n) :cljs n))

(defn- hex [h]
  (store/hash->hex h))

(defn- hashes-of [pages]
  (mapv :hash pages))

(defn- pages-of [pages]
  (mapv (fn [p]
          {:id (whole (:id p))
           :hash (hex (:hash p))
           :bytes (whole (:bytes p))})
        pages))

(defn- cover
  "Session time a lone decaying reveal needs to earn `bytes`.

   `r-max * tau * (1 - exp(-t / tau)) = bytes`."
  [bytes r-max tau]
  (* (- (double tau))
     (host/log (- 1.0 (/ (double bytes) (* (double r-max) (double tau)))))))

(defn duplicate-request
  "N1. A second open of a hash that is already revealing keeps that
   reveal. It does not open another, and the pages still go out once."
  []
  (let [st (store/mem-store)
        h (v/hash (v/vector st (v/i64 st 1) (v/i64 st 2)))
        order (reveal/page-order st h)
        params {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1000.0}
        opened (reveal/open-reveal (reveal/session) st h 0 params)
        id (:id opened)
        again (reveal/open-reveal (:session opened) st h 0 params)
        live (filterv #(= h (:hash %)) (get-in again [:session :reveals]))
        done (reveal/take-pages (:session again) st 100 params)
        got (hashes-of (:pages done))
        order-hex (mapv hex order)
        got-hex (mapv hex got)]
    {:decision {:id "duplicate-request"
                :reveal-id (whole id)
                :again-id (whole (:id again))
                :live (whole (count live))
                :open-pages (whole (count (:pages again)))
                :order order-hex
                :pages (pages-of (:pages done))}
     :problems (cond-> []
                 (not= id (:id again))
                 (conj "a duplicate request replaced the reveal id")

                 (not= 1 (count live))
                 (conj "a duplicate request opened a second reveal")

                 (seq (:pages again))
                 (conj "opening the duplicate sent a page at that instant")

                 (not= order-hex got-hex)
                 (conj "the reveal did not send its pages once, in order"))}))

(defn paused-reveal-ends
  "N2a. Once interest is below the floor the reveal is stalled: a later
   step, however far, sends nothing. The caller ends the response then.
   There is no idle timeout in the session."
  []
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 4000 "x"))))
        order (reveal/page-order st h)
        total (double (reduce + 0 (map #(reveal/page-bytes st %) order)))
        params {:peak 1.0 :tau 0.2 :floor 0.1 :r-max (/ total 0.4)}
        opened (reveal/open-reveal (reveal/session) st h 0 params)
        id (:id opened)
        paused (reveal/take-pages (:session opened) st 10 params)
        later (reveal/take-pages (:session paused) st 10000 params)
        rev (first (filter #(= id (:id %)) (get-in later [:session :reveals])))
        resumed (reveal/open-reveal (:session later) st h 10000 params)
        stalled (reveal/stalled? (:session paused) id 10 params)
        still (reveal/stalled? (:session later) id 10000 params)
        n-paused (count (:pages paused))
        n-later (count (:pages later))]
    {:decision {:id "paused-ends"
                :stalled stalled
                :still-stalled still
                :paused-pages (whole n-paused)
                :later-pages (whole n-later)
                :done (:done? rev)
                :kept-id (= id (:id resumed))
                :order (whole (count order))
                :pages (pages-of (:pages paused))}
     :problems (cond-> []
                 (not stalled)
                 (conj "a paused reveal is not stalled, so a caller would wait")

                 (pos? n-later)
                 (conj "a step far past the pause sent another page")

                 (or (nil? rev) (:done? rev))
                 (conj "the pause removed the reveal")

                 (not= id (:id resumed))
                 (conj "asking again after the pause replaced the reveal")

                 (not (< 0 n-paused (count order)))
                 (conj "the pause did not end on a proper prefix"))}))

(defn page-larger-than-budget
  "N2b. One page bigger than `r-max * tau` is not sent on the first ask.
   Later asks bank the unspent integral and the page completes."
  []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        n (double (reveal/page-bytes st h))
        params {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ n 2.5)}
        opened (reveal/open-reveal (reveal/session) st h 0.0 params)
        id (:id opened)
        far (reveal/take-pages (:session opened) st 100.0 params)
        [bursts got]
        (loop [session (:session far)
               t 100.0
               bursts [(count (:pages far))]
               got (vec (hashes-of (:pages far)))]
          (if (= [h] got)
            [bursts got]
            (if (>= (count bursts) 5)
              [bursts got]
              (let [again (reveal/open-reveal session st h t params)
                    more (reveal/take-pages (:session again) st (+ t 100.0) params)
                    add (hashes-of (:pages more))]
                (recur (:session more)
                       (+ t 100.0)
                       (conj bursts (count add))
                       (into got add))))))
        stalled (reveal/stalled? (:session far) id 100.0 params)]
    {:decision {:id "page-over-budget"
                :stalled stalled
                :bursts (mapv whole bursts)
                :pages (mapv hex got)}
     :problems (cond-> []
                 (not stalled)
                 (conj "the first ask did not stall")

                 (not= [0 0 1] bursts)
                 (conj "the page was not covered across asks")

                 (not= [h] got)
                 (conj "the lone page did not complete"))}))

(defn focused-child-receives-offered-pages
  "N3. Pages the parent has already offered are the child's: they are
   not sent again, and every page of the child is either in that offer
   or sent on the child reveal."
  []
  (let [st (store/mem-store)
        child (v/set st (v/i64 st 1) (v/i64 st 2))
        parent (v/vector st child)
        ch (v/hash child)
        ph (v/hash parent)
        order (reveal/page-order st ph)
        child-order (reveal/page-order st ch)
        params {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1000.0}
        prefix-bytes (reduce + 0.0 (map #(double (reveal/page-bytes st %))
                                        (take 2 order)))
        t2 (cover prefix-bytes 1000.0 1.0e6)
        opened (reveal/open-reveal (reveal/session) st ph 0 params)
        one (reveal/take-pages (:session opened) st t2 params)
        again (reveal/open-reveal (:session one) st ch t2 params)
        more (reveal/take-pages (:session again) st (+ t2 5.0) params)
        offered (set (hashes-of (:pages one)))
        child-sent (hashes-of (concat (:pages again) (:pages more)))
        all (concat (hashes-of (:pages one)) child-sent)
        child-hex (set (map hex child-order))
        all-hex (set (map hex all))]
    {:decision {:id "child-receives"
                :order (mapv (fn [page]
                               {:hash (hex page)
                                :bytes (whole (reveal/page-bytes st page))})
                             order)
                :prefix (pages-of (:pages one))
                :open-child (pages-of (:pages again))
                :child (pages-of (:pages more))}
     :problems (cond-> []
                 (not= 7 (count order))
                 (conj "the parent does not name seven pages")

                 (not (contains? offered ch))
                 (conj "the parent did not offer the child root")

                 (some #{ch} child-sent)
                 (conj "the child root was sent again")

                 (not (every? all-hex child-hex))
                 (conj "a child page was neither offered by the parent nor sent")

                 (not= (count all) (count (set (map hex all))))
                 (conj "a page was sent twice"))}))

(defn ack-ignores-unknown
  "N10. `ack-pages` and `forget-pages` ignore a hash the session does
   not know. An offer named in the same ack is still held. A second ack
   of a held hash changes nothing."
  []
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 42))
        other (v/hash (v/i64 st 99))
        session (reveal/session)
        opened (reveal/open-reveal session st h 0 fast)
        taken (reveal/take-pages (:session opened) st 1 fast)
        unknown-ack (reveal/ack-pages session [other])
        unknown-forget (reveal/forget-pages session st [other])
        mixed (reveal/ack-pages (:session taken) [h other])
        second (reveal/ack-pages mixed [h])
        quiet (reveal/take-pages mixed st 2 fast)]
    {:decision {:id "unknown-ack"
                :unknown-ack-ignored (= session unknown-ack)
                :unknown-forget-ignored (= session unknown-forget)
                :held (and (contains? (:sent mixed) (host/hash-key h))
                           (not (contains? (:offered mixed) (host/hash-key h))))
                :second-ack-ignored (= mixed second)
                :quiet-pages (whole (count (:pages quiet)))}
     :problems (cond-> []
                 (not= session unknown-ack)
                 (conj "ack of an unknown hash changed the session")

                 (not= session unknown-forget)
                 (conj "forget of an unknown hash changed the session")

                 (not (contains? (:sent mixed) (host/hash-key h)))
                 (conj "a known offer in a mixed ack was not held")

                 (contains? (:offered mixed) (host/hash-key h))
                 (conj "a held hash is still offered")

                 (contains? (:sent mixed) (host/hash-key other))
                 (conj "an unknown hash was recorded as held")

                 (not= mixed second)
                 (conj "acking a held hash changed the session")

                 (seq (:pages quiet))
                 (conj "a held page was sent again"))}))
