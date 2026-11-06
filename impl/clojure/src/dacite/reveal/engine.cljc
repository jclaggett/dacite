(ns dacite.reveal.engine
  "Default reveal order, interest shares, and the session send clock.

   The asked node comes first. A collection then sends its own pages, so
   every child hash is known, and then the subvalue bodies its shape
   would ask for next. A string, blob, or vector continues first-most.
   A map sends every key body, then every value body, in the HAMT's
   key-hash order. A set sends each element body once, in that order.
   A scalar is one node. An inlined finger-tree leaf stays on its page
   and is not requested again. A hash already listed is not listed again.

   Interest is `peak * exp(-age / tau)`. A reveal's share of `r-max` is
   `r-max * I / max(peak, sum I)`. While the open interests add up to at
   least one peak, they fill the ceiling. Below that, a lone reveal's
   rate follows the exponential down from the ceiling. Below the floor
   a reveal pauses and drops out of the sum. Cancel drops it at once.

   `open-reveal`, `cancel-reveal`, and `take-pages` spend those shares.
   Credit accrues by integrating that share. A page goes out whole when
   the credit covers its wire-item size. The next page is read when it
   is about to be sent. Offering a page does not mean the client holds
   it: `ack-pages` records a hold, and `forget-pages` puts a reset or
   evicted page back on its reveal. Asking for a hash that is still
   open resumes the unsent pages and restarts its clock at the peak.
   Credit already earned toward the next page stays.

   The clock integrates this exponential. A different curve would
   replace the integral. No path, header, or frame. `peak`, `tau`, the
   floor, and `r-max` are arguments. This namespace does not choose
   production values, and it does not open a stream.

   Host-neutral. A node source is a map of three functions. `dacite.reveal`
   builds it from a store:

   - `:node-entry`  `(fn [hash] {:type string, :root hash-or-nil, :child-hashes [hash]})`
   - `:assoc-order` `(fn [root kind] hashes)` — key hashes, then value hashes.
     A set names each element once.
   - `:node-bytes`  `(fn [hash] n)` — the wire-item size, before `page-bytes`
     checks that it is positive.

   `exp`, `log`, `next-up`, finiteness, and hash identity are `dacite.host`."
  (:require [clojure.string :as str]
            [dacite.host :as host]))

(def ^:private seq-types #{"string" "blob" "vector"})
(def ^:private assoc-types #{"map" "set"})

(defn- exp
  [x]
  (host/exp x))

(defn- hash-key
  "What `page-order` remembers a hash by. On nbb a hash is a vector of four
   BigInts, which a persistent set cannot hash (`hash` of a BigInt throws once
   a set grows past eight), so the hex string stands for it."
  [h]
  (host/hash-key h))

(def sent-limit
  "Held hashes one session remembers. The oldest are dropped first. The same
   cap as the peer sent-hash record (peer-v1 C21)."
  16384)

(def reveal-limit
  "Reveals one session keeps. A finished reveal whose pages are all
   acknowledged is dropped. A reveal with a page still offered stays, so
   the cap never throws away an in-flight page."
  64)

(defn- node-entry
  [nodes h]
  ((:node-entry nodes) h))

(defn- structural-type?
  [t]
  (and (string? t)
       (or (str/starts-with? t "ft/")
           (str/starts-with? t "hamt/"))))

(defn- structural-children
  "Child hashes that are finger-tree or HAMT pages, in stored order."
  [nodes entry]
  (filterv (fn [ch]
             (structural-type? (:type (node-entry nodes ch))))
           (or (:child-hashes entry) [])))

(defn- ref-children
  "Child hashes that are values, not structural pages."
  [nodes entry]
  (into []
        (remove (fn [ch]
                  (structural-type? (:type (node-entry nodes ch)))))
        (or (:child-hashes entry) [])))

(defn- all-structural-pages
  "Own pages under a tree root, this page first, then children in stored
   order. A leaf value hash contributes nothing."
  [nodes h]
  (let [entry (node-entry nodes h)]
    (if-not (structural-type? (:type entry))
      []
      (into [h] (mapcat #(all-structural-pages nodes %)
                        (structural-children nodes entry))))))

(defn- seq-element-refs
  "Value hashes a sequence page points at, left to right.

   Inlined leaves are not refs, so they are absent. A bare leaf root is
   the one element."
  [nodes root]
  (let [entry (node-entry nodes root)]
    (if (structural-type? (:type entry))
      (mapcat (fn [page]
                (ref-children nodes (node-entry nodes page)))
              (all-structural-pages nodes root))
      [root])))

(defn- assoc-refs
  "Key hashes, then value hashes, in HAMT key-hash order.

   A set stores the element as both, so each element is named once."
  [nodes root kind]
  ((:assoc-order nodes) root kind))

(defn page-order
  "Hashes a default reveal of `h` would send, in order.

   The client can focus a child as soon as an earlier page has named
   that child's hash. Bodies follow the shape's guess: first-most for
   sequences, keys then values for a map, element bodies for a set."
  [nodes h]
  (let [seen (volatile! #{})]
    (letfn [(take! [hash]
              (when-not (contains? @seen (hash-key hash))
                (vswap! seen conj (hash-key hash))
                hash))
            (fresh-pages [hashes]
              (into [] (keep take!) hashes))
            (walk [hash]
              (if-let [emitted (take! hash)]
                (let [entry (node-entry nodes hash)
                      t (:type entry)]
                  (cond
                    (contains? seq-types t)
                    (let [root (:root entry)]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages nodes root))
                                    (mapcat walk (seq-element-refs nodes root)))))

                    (contains? assoc-types t)
                    (let [root (:root entry)]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages nodes root))
                                    (mapcat walk (assoc-refs nodes root t)))))

                    :else [emitted]))
                []))]
      (vec (walk h)))))

(defn omit-sent
  "Hashes from `pages` that this session has not already sent, in order."
  [pages sent]
  (into [] (remove #(contains? sent %)) pages))

(defn interest
  "Interest at `age`: `peak * exp(-age / tau)`. `tau` must be positive.
   `age` and `tau` share a time unit."
  [peak tau age]
  (when-not (pos? tau)
    (throw (ex-info "tau must be positive" {:tau tau})))
  (* (double peak) (exp (- (/ (double age) (double tau))))))

(defn shares
  "Byte-per-second share of `:r-max` for each reveal still in the sum.

   `reveals` is a sequence of maps with `:id` and `:opened-at`, and
   optional `:cancelled?` and `:done?`. Time `t` uses the same unit as
   `:tau`. Interest below `:floor` pauses that reveal. Cancel and done
   drop it immediately. The share is `r-max * I / max(peak, sum I)`.
   The shares fill `:r-max` while total interest is at least `:peak`.
   Below that each reveal is paid `r-max * I / peak`, so one reveal
   alone decays from the ceiling."
  [reveals t {:keys [peak tau floor r-max]}]
  (let [weighted (keep (fn [r]
                         (when-not (or (:cancelled? r) (:done? r))
                           (let [age (- t (:opened-at r))
                                 i (interest peak tau age)]
                             (when (>= i floor)
                               [(:id r) i]))))
                       reveals)
        sum (reduce + 0.0 (map second weighted))
        denom (max (double peak) sum)]
    (if (pos? sum)
      (into {} (map (fn [[id i]] [id (* (double r-max) (/ i denom))]) weighted))
      {})))

(defn next-reveal
  "Id of the reveal that receives the next page, or nil when none remain.

   The largest share wins. Equal shares go to the request with the
   later `:opened-at`."
  [reveals t params]
  (let [rate (shares reveals t params)]
    (when (seq rate)
      (let [by-id (into {} (map (juxt :id identity)) reveals)]
        (:id (reduce (fn [best id]
                       (let [r (get rate id)
                             b (get rate (:id best))
                             newer (by-id id)]
                         (cond
                           (> r b) newer
                           (< r b) best
                           (> (:opened-at newer) (:opened-at best)) newer
                           :else best)))
                     (by-id (first (keys rate)))
                     (rest (keys rate))))))))

(defn- log
  [x]
  (host/log x))

(defn- just-after
  "The next instant after `t`. Interest equal to the floor is still in
   the sum, on an instant of no width. Callers that land on that instant
   step past it so the reveal pauses."
  [t]
  (host/next-up t))

(defn- finite-time
  [t]
  (let [t (double t)]
    (when-not (host/finite? t)
      (throw (ex-info "time must be finite" {:t t})))
    t))

(defn- check-params
  [{:keys [peak tau floor r-max]}]
  (when-not (and (number? peak) (pos? peak))
    (throw (ex-info "peak must be positive" {:peak peak})))
  (when-not (and (number? tau) (pos? tau))
    (throw (ex-info "tau must be positive" {:tau tau})))
  (when-not (number? floor)
    (throw (ex-info "floor must be a number" {:floor floor})))
  (when-not (and (number? r-max) (>= r-max 0.0))
    (throw (ex-info "r-max must be non-negative" {:r-max r-max}))))

(defn page-bytes
  "Wire-v1 item bytes of the node at `h`.

   That item is the page a reveal sends, whole. The pack chunk envelope
   is a property of today's `GET /node` response, not of the page."
  [nodes h]
  (let [n ((:node-bytes nodes) h)]
    (when-not (pos? n)
      (throw (ex-info "page has no bytes" {:hash h})))
    n))

(defn session
  "An empty reveal session. The clock starts on the first call that
   names a time. Nothing has been offered or held.

   `:sent` holds at most `:sent-limit` hashes (oldest dropped first) and
   `:owner` only names hashes still sent or offered. A finished reveal
   with nothing left in flight is removed, and idle reveals past
   `:reveal-limit` are dropped oldest first. Those three collections
   therefore stay bounded for the life of the session."
  []
  {:t nil :sent #{} :sent-order [] :offered {} :owner {} :seq 0 :reveals []
   :sent-limit sent-limit :reveal-limit reveal-limit})

(defn- skip-set
  "Hashes that must not be offered again: held, or still in flight."
  [session]
  (into (:sent session) (keys (:offered session))))

(defn- cursor-for
  [h]
  {:stack [[:node h]] :seen #{}})

(defn- cursor-done?
  [cursor]
  (or (nil? cursor) (empty? (:stack cursor))))

(defn- push-frames
  "Push `frames` so the first one is the next to run."
  [stack frames]
  (into stack (reverse frames)))

(defn- pull-page
  "Next hash a default reveal of this cursor would send, skipping `skip`.

   The walk matches `page-order`: the node, then its own structural
   pages, then subvalue bodies. One call reads only what the next hash
   needs. Returns `[hash cursor]`, or `[nil cursor]` when the walk ends."
  [nodes cursor skip]
  (loop [stack (:stack cursor)
         seen (:seen cursor)
         guard 0]
    (when (>= guard 1000000)
      (throw (ex-info "reveal walk exceeded page steps" {})))
    (if (empty? stack)
      [nil {:stack [] :seen seen}]
      (let [frame (peek stack)
            stack (pop stack)
            op (nth frame 0)]
        (case op
          :node
          (let [h (nth frame 1)]
            (if (or (nil? h) (contains? seen h))
              (recur stack seen (inc guard))
              (let [entry (node-entry nodes h)
                    t (:type entry)
                    root (:root entry)
                    seen (conj seen h)
                    follow (cond
                             (contains? seq-types t)
                             [[:struct root] [:seq-bodies root]]
                             (contains? assoc-types t)
                             [[:struct root] [:assoc-bodies root t]]
                             :else [])
                    stack (push-frames stack follow)]
                (if (contains? skip h)
                  (recur stack seen (inc guard))
                  [h {:stack stack :seen seen}]))))

          :struct
          (let [h (nth frame 1)]
            (if (or (nil? h) (contains? seen h))
              (recur stack seen (inc guard))
              (let [entry (node-entry nodes h)
                    t (:type entry)]
                (if-not (structural-type? t)
                  (recur stack seen (inc guard))
                  (let [children (structural-children nodes entry)
                        seen (conj seen h)
                        stack (push-frames stack
                                           (mapv (fn [c] [:struct c]) children))]
                    (if (contains? skip h)
                      (recur stack seen (inc guard))
                      [h {:stack stack :seen seen}]))))))

          :seq-bodies
          (let [root (nth frame 1)
                refs (if (nil? root) [] (vec (seq-element-refs nodes root)))]
            (recur (push-frames stack (mapv (fn [h] [:node h]) refs))
                   seen
                   (inc guard)))

          :assoc-bodies
          (let [root (nth frame 1)
                kind (nth frame 2)
                refs (if (nil? root) [] (vec (assoc-refs nodes root kind)))]
            (recur (push-frames stack (mapv (fn [h] [:node h]) refs))
                   seen
                   (inc guard))))))))

(defn- read-page
  "The page for `h`: its wire size, and the encoded item when the node
   source builds one (`:node-wire`). The size and the bytes come from one
   encode, so a sender that writes `:wire` does not encode the page again
   (N11)."
  [nodes h]
  (let [wire (when-let [f (:node-wire nodes)] (f h))
        n (page-bytes nodes h)]
    (cond-> {:hash h :bytes n}
      wire (assoc :wire wire))))

(defn- ensure-reveal
  "Queue the next unsent page, when this reveal does not already have one."
  [reveal nodes skip]
  (cond
    (or (:cancelled? reveal) (seq (:pages reveal)) (:done? reveal)) reveal
    :else
    (let [[h cursor] (pull-page nodes (:cursor reveal) skip)]
      (if (nil? h)
        (assoc reveal :cursor cursor :pages [] :done? true)
        (assoc reveal :cursor cursor :done? false
               :pages [(read-page nodes h)])))))

(defn- ensure-reveals
  [session nodes]
  (let [skip (skip-set session)]
    (update session :reveals
            (fn [rs]
              (mapv #(ensure-reveal % nodes skip) rs)))))

(defn- drop-skipped-head
  "A page another reveal has since offered or held is no longer this
   reveal's to send."
  [reveal skip nodes]
  (loop [reveal reveal guard 0]
    (when (>= guard 1000000)
      (throw (ex-info "reveal walk exceeded page steps" {})))
    (let [reveal (if (seq (:pages reveal))
                   reveal
                   (ensure-reveal reveal nodes skip))]
      (if-let [page (first (:pages reveal))]
        (if (contains? skip (:hash page))
          (recur (assoc reveal :pages (vec (rest (:pages reveal))))
                 (inc guard))
          reveal)
        reveal))))

(defn- age-of
  [reveal t]
  (- (double t) (double (:opened-at reveal))))

(defn- in-sum?
  [reveal t {:keys [peak tau floor]}]
  (and (not (:cancelled? reveal))
       (not (:done? reveal))
       (seq (:pages reveal))
       (>= (interest peak tau (age-of reveal t)) floor)))

(defn- floor-time
  "Time at which interest equals the floor. Nil when a positive floor
   is never reached."
  [reveal {:keys [peak tau floor]}]
  (cond
    (<= floor 0.0) nil
    (>= floor peak) (double (:opened-at reveal))
    :else (+ (double (:opened-at reveal))
             (* (double tau)
                (log (/ (double peak) (double floor)))))))

(defn- seconds-until-out
  [reveal t params]
  (if-let [limit (floor-time reveal params)]
    (max 0.0 (- limit (double t)))
    ##Inf))

(defn- interest-sum
  [earning t {:keys [peak tau]}]
  (reduce + 0.0 (map #(interest peak tau (age-of % t)) earning)))

(defn- decay-regime?
  "True when total interest is at or below one peak, so each rate follows
   the exponential instead of a constant share of the ceiling."
  [earning t params]
  (<= (interest-sum earning t params) (double (:peak params))))

(defn- seconds-until-decay
  "Time until total interest falls to one peak. Infinite once it has."
  [earning t params]
  (let [peak (double (:peak params))
        sum (interest-sum earning t params)]
    (if (> sum peak)
      (* (double (:tau params)) (log (/ sum peak)))
      ##Inf)))

(defn- exp-span
  "Integral of `exp(-s / tau)` over `dt` seconds, times `tau`.

   That is `tau * (1 - exp(-dt / tau))`. A tiny `dt / tau` underflows
   the exp, so the span is `dt` there."
  [dt tau]
  (let [dt (double dt)
        tau (double tau)
        x (/ dt tau)]
    (if (< x 1.0e-8)
      dt
      (* tau (- 1.0 (exp (- x)))))))

(defn- decay-wait
  "Seconds for a decaying rate to cover `need` more bytes.

   The integral of `rate * exp(-s / tau)` from 0 to infinity is
   `rate * tau`. A page larger than that is never reached on this clock."
  [need rate tau]
  (let [need (double need)
        rate (double rate)
        tau (double tau)]
    (cond
      (<= need 0.0) 0.0
      (not (pos? rate)) ##Inf
      :else
      (let [budget (* rate tau)
            x (/ need budget)]
        (cond
          (>= x 1.0) ##Inf
          (< x 1.0e-8) (/ need rate)
          :else (* (- tau) (log (- 1.0 x))))))))

(defn- page-wait
  [reveal rate decay? tau room]
  (let [need (- (double (:bytes (first (:pages reveal))))
                (double (:credit reveal)))]
    (cond
      (<= need 0.0) 0.0
      (not (pos? rate)) ##Inf
      decay? (decay-wait need rate tau)
      :else
      (let [w (/ need (double rate))]
        (if (<= w room)
          w
          (+ room (decay-wait (- need (* (double rate) room)) rate tau)))))))

(defn- earlier
  [a b]
  (if (< a b) a b))

(defn- accrue
  "Add `dt` seconds of credit. In `:fill` the share is constant. In
   `:decay` the share at the start of the step is `rate` and then follows
   `exp(-s / tau)`."
  [session rates dt mode tau]
  (let [dt (double dt)
        factor (if (= mode :decay)
                 (exp-span dt tau)
                 dt)]
    (-> session
        (update :t + dt)
        (update :reveals
                (fn [rs]
                  (mapv (fn [r]
                          (if-let [rate (get rates (:id r))]
                            (update r :credit + (* factor (double rate)))
                            r))
                        rs))))))

(defn- emit-page
  "Offer the next page. The client does not hold it until `ack-pages`."
  [session reveal t]
  (let [page (first (:pages reveal))
        credit (max 0.0 (- (double (:credit reveal))
                           (double (:bytes page))))
        pages (vec (rest (:pages reveal)))
        done? (and (empty? pages) (cursor-done? (:cursor reveal)))
        reveal' (assoc reveal :credit credit :pages pages :done? done?
                       :cursor (if done?
                                 {:stack [] :seen #{}}
                                 (:cursor reveal)))]
    {:session (-> session
                  (update :offered assoc (:hash page)
                          {:id (:id reveal) :bytes (:bytes page) :hash (:hash page)})
                  (update :owner assoc (:hash page) (:id reveal))
                  (update :reveals
                          (fn [rs]
                            (mapv (fn [r]
                                    (if (= (:id reveal) (:id r)) reveal' r))
                                  rs))))
     :page (cond-> {:id (:id reveal)
                    :hash (:hash page)
                    :bytes (:bytes page)
                    :at (double t)}
             (:wire page) (assoc :wire (:wire page)))}))

(defn- advance
  "Move the session clock forward to `t`, offering every page whose
   credit covers it at or before that time.

   While total interest stays at or above one peak, shares are constant
   between membership changes and credit grows by `rate * dt`. Below
   that peak the exponential is integrated. A different curve does not
   drop into this step."
  [session nodes t params]
  (let [t (finite-time t)]
    (cond
      (nil? (:t session)) [(assoc session :t t) []]
      (< t (:t session))
      (throw (ex-info "session clock moves forward"
                      {:t t :at (:t session)}))
      :else
      (loop [session session
             out []
             steps 0]
        (when (>= steps 1000000)
          (throw (ex-info "reveal session exceeded page steps" {})))
        (let [skip (skip-set session)
              session (update (ensure-reveals session nodes) :reveals
                              (fn [rs]
                                (mapv #(drop-skipped-head % skip nodes) rs)))
              t-now (double (:t session))
              earning (filterv #(in-sum? % t-now params) (:reveals session))
              rates (shares earning t-now params)
              ready (filterv (fn [r]
                               (>= (+ (:credit r) 1.0e-6)
                                   (double (:bytes (first (:pages r))))))
                             earning)]
          (cond
            (and (seq ready) (<= t-now t))
            (let [id (next-reveal ready t-now params)
                  chosen (first (filter #(= id (:id %)) earning))
                  emitted (emit-page session chosen t-now)]
              (recur (:session emitted)
                     (conj out (:page emitted))
                     (inc steps)))

            (>= t-now t)
            [session out]

            (empty? earning)
            [(assoc session :t t) out]

            :else
            (let [decay? (decay-regime? earning t-now params)
                  tau (double (:tau params))
                  room (seconds-until-decay earning t-now params)
                  dt-page (reduce earlier ##Inf
                                  (map (fn [r]
                                         (page-wait r (get rates (:id r) 0.0)
                                                    decay? tau room))
                                       earning))
                  dt-out (reduce earlier ##Inf
                                 (map #(seconds-until-out % t-now params)
                                      earning))
                  dt (earlier (earlier (earlier dt-page dt-out) room)
                              (- t t-now))]
              (if (pos? dt)
                (recur (accrue session rates dt
                               (if decay? :decay :fill) tau)
                       out
                       (inc steps))
                (let [nudged (min t (just-after t-now))]
                  (if (= nudged t-now)
                    (throw (ex-info "session clock could not move"
                                    {:t t-now}))
                    (recur (assoc session :t nudged) out (inc steps))))))))))))

(defn- with-pages
  [session pages]
  {:session session :pages pages})

(defn- restart-clock
  [session id t]
  (update session :reveals
          (fn [rs]
            (mapv (fn [r]
                    (if (= id (:id r))
                      (assoc r :opened-at (double t))
                      r))
                  rs))))

(defn- inf?
  [x]
  (host/infinite? x))

(defn- remaining-integral
  "Bytes `reveal` would still earn on this clock.

   Zero when the next page is still reachable, or when the reveal is
   below the floor and has nothing left to earn. The session clock does
   not move. A page goes out only when credit covers it."
  [session reveal t params]
  (let [id (:id reveal)
        earning (filterv #(in-sum? % t params) (:reveals session))]
    (if-not (some #(= id (:id %)) earning)
      0.0
      (let [rates (shares earning t params)
            rate (double (get rates id 0.0))
            decay? (decay-regime? earning t params)
            tau (double (:tau params))
            room (seconds-until-decay earning t params)
            wait (page-wait reveal rate decay? tau room)
            horizon (seconds-until-out reveal t params)]
        (cond
          (not (inf? wait)) 0.0
          (not (pos? rate)) 0.0
          decay? (if (inf? horizon)
                   (* rate tau)
                   (* rate (exp-span (max 0.0 horizon) tau)))
          (<= horizon room) (* rate (max 0.0 horizon))
          :else
          (let [boundary (+ (double t) (double room))
                later (filterv #(in-sum? % boundary params)
                               (:reveals session))
                rate-at (double (get (shares later boundary params) id 0.0))
                horizon-at (seconds-until-out reveal boundary params)
                decay (if (inf? horizon-at)
                        (* rate-at tau)
                        (* rate-at (exp-span (max 0.0 horizon-at) tau)))]
            (+ (* rate (double room)) decay)))))))

(defn- bank-tail
  "Add the unspent integral of a clock that can no longer cover its page.

   A re-ask replaces that clock. The remainder is kept as credit, so a
   page larger than one `r-max * tau` is covered across asks."
  [session id t params]
  (let [reveal (first (filter #(= id (:id %)) (:reveals session)))
        extra (if reveal (remaining-integral session reveal t params) 0.0)]
    (if (pos? extra)
      (update session :reveals
              (fn [rs]
                (mapv (fn [r]
                        (if (= id (:id r))
                          (update r :credit + extra)
                          r))
                      rs)))
      session)))

(defn- reveal-credit
  [session id]
  (:credit (first (filter #(= id (:id %)) (:reveals session)))))

(defn- snap-covered
  "A grant that lands within a fraction of a byte covers the page.

   The closed integral is `rate * tau`, and float division can stop a
   hair short of a whole page."
  [session id]
  (update session :reveals
          (fn [rs]
            (mapv (fn [r]
                    (let [page (first (:pages r))
                          credit (double (:credit r))
                          bytes (double (:bytes page 0.0))]
                      (if (and (= id (:id r))
                               page
                               (< credit bytes)
                               (>= (+ credit 1.0e-4) bytes))
                        (assoc r :credit bytes)
                        r)))
                  rs))))

(defn- exhaust-clock
  "The integral just added is the rest of this clock.

   `opened-at` moves back by many `tau`, so the rate is nothing and a
   later tick does not pay those bytes again. Floor 0 still counts the
   reveal as earning, which lets a covered page go out."
  [session id t params]
  (let [opened (- (double t) (* 64.0 (double (:tau params))))]
    (update session :reveals
            (fn [rs]
              (mapv (fn [r]
                      (if (= id (:id r))
                        (assoc r :opened-at opened)
                        r))
                    rs)))))

(defn- live-reveal
  "The open reveal of `h`, including one that has paused or finished.
   A cancelled reveal is removed and is not reused."
  [session h]
  (first (filter #(and (= h (:hash %)) (not (:cancelled? %)))
                 (:reveals session))))

(defn- slim-done
  "A finished walk does not keep the hashes it already visited."
  [reveal]
  (if (:done? reveal)
    (assoc reveal :cursor {:stack [] :seen #{}} :pages [])
    reveal))

(declare trim-reveals)

(defn open-reveal
  "Open a reveal of `h` at time `t`, or resume one already open.

   Pages due by `t` from reveals already open are returned first. The
   new reveal starts with no new credit at that instant, and only its
   next page is read. Resuming restarts the interest clock at the peak
   and keeps credit already earned toward the next page. When the old
   clock can no longer cover that page, the remainder of its integral
   is added first. The new clock's limit is added in the same way when
   the page sits on that limit, so the exponential does not have to
   arrive there. A hash already offered or held in this session is not
   queued again.

   Returns `{:session :pages :id}`."
  [session nodes h t params]
  (check-params params)
  (let [t (finite-time t)
        [session pages] (advance session nodes t params)
        existing (live-reveal session h)]
    (if existing
      (let [id (:id existing)
            closed (bank-tail session id t params)
            restarted (restart-clock closed id t)
            granted (snap-covered (bank-tail restarted id t params) id)]
        {:session (trim-reveals
                   (if (and (> (double (reveal-credit granted id))
                               (double (reveal-credit restarted id)))
                            (<= (double (:floor params)) 0.0))
                     (exhaust-clock granted id t params)
                     granted))
         :pages pages
         :id id})
      (let [id (inc (:seq session))
            reveal (-> {:id id
                        :hash h
                        :opened-at t
                        :pages []
                        :cursor (cursor-for h)
                        :credit 0.0
                        :cancelled? false
                        :done? false}
                       (ensure-reveal nodes (skip-set session))
                       slim-done)]
        {:session (trim-reveals
                   (-> session
                       (assoc :seq id :t t)
                       (update :reveals conj reveal)))
         :pages pages
         :id id}))))

(defn cancel-reveal
  "Cancel the reveal `id` at time `t`.

   Pages already due by `t` are returned. Later pages of this reveal
   are not sent. The reveal is dropped, so asking again is a new id.
   Hashes already offered stay offered until `ack-pages` or
   `forget-pages`.

   Returns `{:session :pages}`."
  [session nodes id t params]
  (check-params params)
  (when-not (some #(= id (:id %)) (:reveals session))
    (throw (ex-info "no such reveal" {:id id})))
  (let [t (finite-time t)
        [session pages] (advance session nodes t params)]
    {:session (update session :reveals
                      (fn [rs]
                        (into [] (remove #(= id (:id %))) rs)))
     :pages pages}))

(defn- remember-sent
  "Record `hashes` as held. Past `:sent-limit`, the oldest held hash is
   dropped, and so is its `:owner` entry when nothing still offers it."
  [session hashes]
  (let [limit (:sent-limit session sent-limit)]
    (reduce (fn [session h]
              (let [k h]
                (if (contains? (:sent session) k)
                  session
                  (let [session (-> session
                                    (update :sent conj k)
                                    (update :sent-order conj k))]
                    (if (<= (count (:sent-order session)) limit)
                      session
                      (let [old (first (:sent-order session))]
                        (-> session
                            (assoc :sent-order (into [] (rest (:sent-order session))))
                            (update :sent disj old)
                            (update :owner (fn [owner]
                                             (if (contains? (:offered session) old)
                                               owner
                                               (dissoc owner old)))))))))))
            session
            hashes)))

(defn- trim-reveals
  "While the session holds more than `:reveal-limit` reveals, drop the oldest
   one that has nothing in flight (finished, or idle with no page queued).
   A reveal with a page queued or still offered stays, so `forget-pages` can
   requeue onto it. Under the cap, finished reveals stay too: an eviction
   forgets a page back onto the reveal that sent it."
  [session]
  (let [busy (set (map :id (vals (:offered session))))
        limit (:reveal-limit session reveal-limit)
        droppable? (fn [r]
                     (and (not (contains? busy (:id r)))
                          (or (:done? r) (empty? (:pages r)))))]
    (assoc session :reveals
           (loop [rs (:reveals session)]
             (if (or (<= (count rs) limit) (not-any? droppable? rs))
               rs
               (let [victim (:id (first (filter droppable? rs)))]
                 (recur (into [] (remove #(= victim (:id %))) rs))))))))

(defn ack-pages
  "The client holds the offered hashes among `hashes`.

   A hash that is not an outstanding offer is ignored. That includes a
   hash already held and a hash this session has never offered. An ack
   is a hint and is never an error (N10). A held hash is not offered
   again unless `forget-pages`. Held hashes past `:sent-limit` are
   forgotten oldest first."
  [session hashes]
  (let [known (into [] (filter #(contains? (:offered session) %)) hashes)]
    (if (empty? known)
      session
      (trim-reveals
       (-> session
           (remember-sent known)
           (update :offered (fn [offered]
                              (reduce dissoc offered known))))))))

(defn forget-pages
  "The client does not hold `hashes`.

   A reset drops an offer the client never kept. An eviction drops a
   hash it no longer keeps. The page is queued again on the reveal that
   offered it, when that reveal is still open. A cancelled reveal is
   already gone; a later `open-reveal` of a value that contains the hash
   sends it. A hash that was neither offered nor held is ignored (N10)."
  [session nodes hashes]
  (reduce (fn [session h]
            (let [k h]
              (if-not (or (contains? (:offered session) k)
                          (contains? (:sent session) k))
                ;; Unknown hashes are ignored, as with `ack-pages` (N10).
                session
                (let [id (get (:owner session) k)
                      raw (or (:hash (get (:offered session) k)) h)
                      page (read-page nodes raw)
                      session (-> session
                                  (update :offered dissoc k)
                                  (update :sent disj k)
                                  (update :sent-order (fn [o] (into [] (remove #(= k %)) o)))
                                  (update :owner dissoc k))]
                  (update session :reveals
                          (fn [rs]
                            (mapv (fn [r]
                                    (if (and (= id (:id r))
                                             (not (some #(= raw (:hash %))
                                                        (:pages r))))
                                      (-> r
                                          (update :pages #(into [page] %))
                                          (assoc :done? false))
                                      r))
                                  rs)))))))
          session
          hashes))

(defn take-pages
  "Pages the session would offer from its clock up to time `t`.

   Each page is `{:id :hash :bytes :at}`. `:at` is the time the credit
   covered that page. The page is offered, not held, until `ack-pages`.
   The clock then stands at `t`.

   Returns `{:session :pages}`."
  [session nodes t params]
  (check-params params)
  (let [[session pages] (advance session nodes (finite-time t) params)]
    (with-pages session pages)))

(defn stalled?
  "True when reveal `id` will not offer another page until it is asked again.

   It still has a page, and either its interest is below the floor or the
   current share cannot cover that page. In the decay regime the remaining
   integral is `rate * tau` bytes. A finished reveal is not stalled."
  [session id t params]
  (let [t (finite-time t)
        reveal (first (filter #(= id (:id %)) (:reveals session)))]
    (boolean
     (and reveal
          (not (:done? reveal))
          (not (:cancelled? reveal))
          (seq (:pages reveal))
          (let [earning (filterv #(in-sum? % t params) (:reveals session))]
            (if-not (some #(= id (:id %)) earning)
              true
              (let [rates (shares earning t params)
                    decay? (decay-regime? earning t params)
                    tau (double (:tau params))
                    room (seconds-until-decay earning t params)
                    wait (page-wait reveal (get rates id 0.0) decay? tau room)]
                (inf? wait))))))))
