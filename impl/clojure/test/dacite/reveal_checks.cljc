(ns dacite.reveal-checks
  "Reveal engine checks on the JVM and on nbb: the session clock, stalls
   and re-asks, cancel, what is sent once, and the session's bounds. The
   JVM runs them from `dacite.reveal-test`, one deftest each under its old
   name; nbb runs them from `dacite.reveal-cljs-test` (`npm run
   reveal:test`).

   `is` is `dacite.store.check`. Sets of hashes the checks build are keyed
   by hex (SCI cannot hash a BigInt word, see `in?`); the session's own
   sets and maps are keyed by `host/hash-key`. `env` has `:encode-page`,
   the writer's page encoder: `dacite.service.reveal` on the JVM,
   `dacite.reveal` on nbb."
  (:require [dacite.host :as host]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.store.check :refer [is]]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(defn- hex [h] (store/hash->hex h))

(defn- hex-set
  "The hex of each hash in `hs`, as a set."
  [hs]
  (set (map hex hs)))

(defn- in?
  "True when `h` is among the hashes `hs`."
  [hs h]
  (contains? (hex-set hs) (hex h)))

(defn- info-with?
  "True when `(f)` throws an ExceptionInfo whose message matches `re`, as
   `thrown-with-msg?`. Any other throw propagates, so the check reports it."
  [re f]
  (try (f) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (boolean (re-find re (str (ex-message e)))))))

(defn- lone-page-at
  "Time a lone reveal, opened at 0, takes to earn `fraction` pages.

   `r-max` is one page per unit of time. Credit in pages is
   `tau * (1 - exp(-t / tau))`."
  [tau fraction]
  (* (- tau) (Math/log (- 1.0 (/ fraction tau)))))

(defn- hashes-of [pages]
  (mapv :hash pages))

(def ^:private fast
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e12})

(defn one-page-waits-until-its-share-covers-it
  [env]
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
    (is (contains? (get-in due [:session :offered]) (host/hash-key h)))
    (is (not (contains? (get-in due [:session :sent]) (host/hash-key h))))
    (let [again (reveal/open-reveal (:session due) s h at params)
          later (reveal/take-pages (:session again) s 20 params)]
      (is (empty? (:pages later))))))

(defn pages-come-out-in-reveal-order
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 9000 "a"))))
        order (reveal/page-order s h)
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)]
    (is (> (count order) 1))
    (is (= 1 (count (:pages (first (get-in opened [:session :reveals]))))))
    (is (= order (hashes-of (:pages taken))))
    (is (:done? (first (get-in taken [:session :reveals]))))))

(defn equal-shares-send-one-page-each
  [env]
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

(defn below-the-floor-pauses-until-the-hash-is-asked-again
  [env]
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

(defn a-lone-reveal-stops-when-its-budget-is-spent
  [env]
  (let [s (store/mem-store)
        ;; 3000 bytes of text. The reveal is 3565 wire-item bytes, so
        ;; r-max * tau = total/4 cannot cover it in one ask.
        h (v/hash (v/string s (apply str (repeat 12000 "x"))))
        order (reveal/page-order s h)
        total (double (reduce + 0 (map #(reveal/page-bytes s %) order)))
        params {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ total 4.0)}
        opened (reveal/open-reveal (reveal/session) s h 0.0 params)
        far (reveal/take-pages (:session opened) s 100.0 params)
        sent (hashes-of (:pages far))]
    (is (< 1 (count order)))
    (is (seq sent))
    (is (< (count sent) (count order)))
    (is (reveal/stalled? (:session far) (:id opened) 100.0 params))
    (loop [session (:session far)
           got (vec sent)
           t 100.0
           n 0]
      (if (= (hex-set order) (hex-set got))
        (is (= (hex-set order) (hex-set got)))
        (do
          (is (< n 8) "a re-ask resumes the pages still missing")
          (let [again (reveal/open-reveal session s h t params)
                more (reveal/take-pages (:session again) s (+ t 100.0) params)
                add (hashes-of (:pages more))]
            (is (seq add))
            (is (empty? (filter #(in? got %) add)))
            (is (= (:id opened) (:id again)))
            (recur (:session more) (into got add) (+ t 100.0) (inc n))))))))

(defn a-re-ask-at-the-stall-banks-the-unspent-integral
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 12000 "x"))))
        order (reveal/page-order s h)
        total (double (reduce + 0 (map #(reveal/page-bytes s %) order)))
        params {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ total 4.0)}
        opened (reveal/open-reveal (reveal/session) s h 0.0 params)
        id (:id opened)
        ;; The third page is 1122 bytes and r-max * tau is total/4.
        ;; Ack at the stall, with the clock still there.
        walk (fn [session t]
               (loop [session session
                      t t
                      emitted []]
                 (if (or (reveal/stalled? session id t params) (>= t 60.0))
                   [session t emitted]
                   (let [nxt (reveal/take-pages session s (+ t 0.05) params)]
                     (recur (:session nxt)
                            (+ t 0.05)
                            (into emitted (hashes-of (:pages nxt))))))))
        [session t got] (walk (:session opened) 0.0)]
    (is (seq got))
    (is (< (count (hex-set got)) (count order)))
    (loop [session session
           t t
           got got
           n 0]
      (if (= (hex-set order) (hex-set got))
        (is (= (hex-set order) (hex-set got)))
        (if (>= n 8)
          (is false "a re-ask at the stall did not finish the value")
          (let [offered (mapv :hash (vals (:offered session)))
                acked (reveal/ack-pages session offered)
                again (reveal/open-reveal acked s h t params)
                [session2 t2 emitted] (walk (:session again) t)
                add emitted]
            (is (seq add) "the re-ask sends a page the stalled clock could not")
            (is (empty? (filter #(in? got %) add)))
            (is (= id (:id again)))
            (when (seq add)
              (recur session2 t2 (into got add) (inc n)))))))))

(defn a-child-focus-sends-nothing-once-the-parent-offered-its-pages
  [env]
  (let [s (store/mem-store)
        child (v/set s (v/i64 s 1) (v/i64 s 2))
        parent (v/vector s child)
        ch (v/hash child)
        ph (v/hash parent)
        order (reveal/page-order s ph)
        opened (reveal/open-reveal (reveal/session) s ph 0 fast)
        done (reveal/take-pages (:session opened) s 1 fast)
        again (reveal/open-reveal (:session done) s ch 1 fast)
        more (reveal/take-pages (:session again) s 2 fast)]
    (is (= 7 (count order)))
    (is (= (hex-set order) (hex-set (hashes-of (:pages done)))))
    (is (every? #(in? order %) (reveal/page-order s ch)))
    (is (empty? (:pages again)))
    (is (empty? (:pages more)))))

(defn cancel-drops-the-rest-of-that-reveal
  [env]
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

(defn a-hash-already-sent-is-not-sent-again
  [env]
  (let [s (store/mem-store)
        a (v/vector s (v/i64 s 1))
        b (v/vector s (v/i64 s 2))
        outer (v/vector s a b)
        sent (reveal/open-reveal (reveal/session) s (v/hash a) 0 fast)
        done (reveal/take-pages (:session sent) s 1 fast)
        held (reveal/ack-pages (:session done) (hashes-of (:pages done)))
        more (reveal/open-reveal held s (v/hash outer) 1 fast)
        pages (reveal/take-pages (:session more) s 2 fast)]
    (is (in? (hashes-of (:pages done)) (v/hash a)))
    (is (not (in? (hashes-of (:pages pages)) (v/hash a))))
    (is (in? (hashes-of (:pages pages)) (v/hash outer)))
    (is (in? (hashes-of (:pages pages)) (v/hash b)))))

(defn a-newer-reveal-gets-the-next-page
  [env]
  (let [s (store/mem-store)
        book (v/hash (v/string s (apply str (repeat 9000 "a"))))
        chapter (v/hash (v/string s (apply str (repeat 9000 "z"))))
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

(defn the-session-clock-moves-forward
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 1))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)]
    (is (info-with? #"clock"
                    #(reveal/take-pages (:session opened) s -1 fast)))
    (is (info-with? #"r-max"
                    #(reveal/take-pages (:session opened) s 1
                                        (assoc fast :r-max -1))))))

(defn a-multi-page-reveal-resumes-after-the-pages-already-offered
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 9000 "a"))))
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
    (is (not (in? (hashes-of (:pages more)) (first order))))))

(defn releasing-an-offer-sends-that-page-again
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)
        released (reveal/forget-pages (:session taken) s [h])
        again (reveal/take-pages released s 2 fast)]
    (is (= [h] (hashes-of (:pages taken))))
    (is (= [h] (hashes-of (:pages again))))
    (let [session (reveal/session)]
      (is (= session (reveal/ack-pages session [h]))
          "an unknown hash is ignored"))))

(defn an-acknowledged-page-is-sent-again-after-eviction
  [env]
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        opened (reveal/open-reveal (reveal/session) s h 0 fast)
        taken (reveal/take-pages (:session opened) s 1 fast)
        held (reveal/ack-pages (:session taken) [h])
        quiet (reveal/take-pages held s 2 fast)
        evicted (reveal/forget-pages held s [h])
        again (reveal/take-pages evicted s 3 fast)]
    (is (contains? (:sent held) (host/hash-key h)))
    (is (empty? (:pages quiet)))
    (is (= [h] (hashes-of (:pages again))))))

(defn the-floor-is-a-single-instant
  [env]
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

(defn a-page-is-encoded-once
  [env]
  ;; N11: the clock used to encode each page to learn its size, and the
  ;; sender encoded it again to write it.
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 9000 "a"))))
        n (atom 0)
        taken (binding [bin/*on-encode* (fn [_] (swap! n inc))]
                (let [opened (reveal/open-reveal (reveal/session) s h 0 fast)]
                  (reveal/take-pages (:session opened) s 1 fast)))
        pages (:pages taken)]
    (is (> (count pages) 1))
    (is (= (count pages) @n) "one encode per page, not two")
    (is (every? #(= (:bytes %) (bin/byte-len (:wire %))) pages))
    (doseq [p pages]
      (is (identical? (:wire p) ((:encode-page env) s p))
          "the writer uses the bytes already encoded"))))

(defn reveals-sent-and-owner-stay-bounded
  [env]
  (let [s (store/mem-store)
        sess (assoc (reveal/session) :sent-limit 2 :reveal-limit 2)
        hs (mapv #(v/hash (v/i64 s %)) (range 4))
        step (fn [session [i h]]
               (let [t (double (* i 2))
                     opened (reveal/open-reveal session s h t fast)
                     taken (reveal/take-pages (:session opened) s (inc t) fast)]
                 (reveal/ack-pages (:session taken) (hashes-of (:pages taken)))))
        end (reduce step sess (map-indexed vector hs))]
    (is (<= (count (:reveals end)) 2))
    (is (<= (count (:sent end)) 2))
    (is (= (count (:sent end)) (count (:sent-order end))))
    (is (every? #(or (contains? (:sent end) %)
                     (contains? (:offered end) %))
                (keys (:owner end))))))

(def checks
  {"one-page-waits-until-its-share-covers-it" one-page-waits-until-its-share-covers-it
   "pages-come-out-in-reveal-order" pages-come-out-in-reveal-order
   "equal-shares-send-one-page-each" equal-shares-send-one-page-each
   "below-the-floor-pauses-until-the-hash-is-asked-again" below-the-floor-pauses-until-the-hash-is-asked-again
   "a-lone-reveal-stops-when-its-budget-is-spent" a-lone-reveal-stops-when-its-budget-is-spent
   "a-re-ask-at-the-stall-banks-the-unspent-integral" a-re-ask-at-the-stall-banks-the-unspent-integral
   "a-child-focus-sends-nothing-once-the-parent-offered-its-pages" a-child-focus-sends-nothing-once-the-parent-offered-its-pages
   "cancel-drops-the-rest-of-that-reveal" cancel-drops-the-rest-of-that-reveal
   "a-hash-already-sent-is-not-sent-again" a-hash-already-sent-is-not-sent-again
   "a-newer-reveal-gets-the-next-page" a-newer-reveal-gets-the-next-page
   "the-session-clock-moves-forward" the-session-clock-moves-forward
   "a-multi-page-reveal-resumes-after-the-pages-already-offered" a-multi-page-reveal-resumes-after-the-pages-already-offered
   "releasing-an-offer-sends-that-page-again" releasing-an-offer-sends-that-page-again
   "an-acknowledged-page-is-sent-again-after-eviction" an-acknowledged-page-is-sent-again-after-eviction
   "the-floor-is-a-single-instant" the-floor-is-a-single-instant
   "a-page-is-encoded-once" a-page-is-encoded-once
   "reveals-sent-and-owner-stay-bounded" reveals-sent-and-owner-stay-bounded})
