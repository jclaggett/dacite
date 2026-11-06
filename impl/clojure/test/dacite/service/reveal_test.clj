(ns dacite.service.reveal-test
  "The reveal stream: item bytes, hash checks, one HTTP/3 session,
   and the root notice beside it."
  (:require [clojure.test :refer [deftest is]]
            [dacite.hash]
            [dacite.reveal :as reveal]
            [dacite.service :as svc]
            [dacite.service.reveal :as rv]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.reveal :as reveal-client]
            [dacite.value :as v]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin])
  (:import [java.io ByteArrayOutputStream]
           [java.util.concurrent TimeUnit]
           [org.eclipse.jetty.client InputStreamResponseListener
            StringRequestContent]
           [org.eclipse.jetty.http HttpVersion]))

(defn- post-reveal
  "POST /reveal and return {:status :type :version :items}."
  [client h3-url h]
  (let [body (wire/write-edn {:hash (store/hash->hex h)})
        listener (InputStreamResponseListener.)
        req (-> (.newRequest client (str h3-url "/reveal"))
                (.method "POST")
                (.body (StringRequestContent. "application/edn" body))
                (.timeout 20 TimeUnit/SECONDS)
                (.idleTimeout 20 TimeUnit/SECONDS))]
    (.send req listener)
    (let [resp (.get listener 20 TimeUnit/SECONDS)
          bs (try
               (.readAllBytes (.getInputStream listener))
               (finally
                 (.close listener)))]
      {:status (.getStatus resp)
       :type (str (.get (.getHeaders resp) "Content-Type"))
       :version (.getVersion resp)
       :items (bin/decode-item-stream bs)})))

(deftest pages-round-trip-as-wire-items
  (let [source (store/mem-store)
        h (v/hash (v/vector source
                            (v/i64 source 1)
                            (v/vector source (v/i64 source 2))))
        order (reveal/page-order source h)
        bytes (let [out (java.io.ByteArrayOutputStream.)]
                (doseq [page order]
                  (.write out (rv/encode-page source {:hash page})))
                (.toByteArray out))
        items (bin/decode-item-stream bytes)
        dest (rv/accept-items! (store/mem-store) items)]
    (is (= order (mapv :hash items)))
    (doseq [page order]
      (is (= (store/require-node source page)
             (store/require-node dest page))))))

(deftest a-bad-page-hash-is-refused
  (let [source (store/mem-store)
        h (v/hash (v/i64 source 7))
        item (first (bin/decode-item-stream (rv/encode-page source {:hash h})))
        wrong (assoc item :hash (v/hash (v/i64 source 8)))
        err (try
              (rv/accept-items! (store/mem-store) [wrong])
              nil
              (catch clojure.lang.ExceptionInfo e e))]
    (is (some? err))
    (is (re-find #"hash mismatch" (.getMessage err)))))

(deftest http3-reveal-shares-one-connection
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/map rooted
                         (v/string rooted "a") (v/i64 rooted 1)
                         (v/string rooted "b") (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        {:keys [h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        primary (reveal-client/open-client {:trust-all true})
        fresh (reveal-client/open-client {:trust-all true})]
    (try
      (let [first (post-reveal (:client primary) h3-url h)
            again (post-reveal (:client primary) h3-url h)
            anew (post-reveal (:client fresh) h3-url h)
            pack (svc/handle-request rooted "GET"
                                     (str "/node/" (store/hash->hex h))
                                     nil
                                     {:accept bin/content-type-chunk-v1})]
        (is (= HttpVersion/HTTP_3 (:version first)))
        (is (= 200 (:status first)))
        (is (= rv/content-type (:type first)))
        (is (< 1 (count order)))
        (is (= order (mapv :hash (:items first))))
        (rv/accept-items! (store/mem-store) (:items first))
        (is (= 200 (:status again)))
        (is (empty? (:items again))
            "a page offered on this connection is not sent again")
        (is (= order (mapv :hash (:items anew)))
            "a new connection has its own session")
        (is (= 200 (:status pack)))
        (is (= bin/content-type-chunk-v1 (:content-type pack))))
      (finally
        ((:close! primary))
        ((:close! fresh))
        (stop!)))))

(deftest remote-get-reads-the-reveal-stream
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/map rooted
                         (v/string rooted "a") (v/i64 rooted 1)
                         (v/string rooted "b") (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        child (first (remove #(= h %) order))
        {:keys [base-url h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        primary (reveal-client/open-client {:trust-all true})
        fresh (reveal-client/open-client {:trust-all true})
        via-reveal (remote/remote-store base-url
                                        {:reveal {:url h3-url
                                                  :client (:client primary)}})
        again (remote/remote-store base-url
                                   {:reveal {:url h3-url
                                             :client (:client fresh)}})]
    (try
      (let [node (store/await (store/s-get via-reveal h))]
        (is (= (store/require-node rooted h) node))
        (is (= (set order)
               (set (store/await (store/s-keys (:pack-local via-reveal))))))
        ((:close! primary))
        (is (= (store/require-node rooted child)
               (store/await (store/s-get via-reveal child)))
            "a page this stream stored is a later local read")
        (is (= (store/require-node rooted h)
               (store/await (store/s-get again h))))
        (is (= (set order)
               (set (store/await (store/s-keys (:pack-local again))))))
        (let [packed (remote/remote-store base-url)
              pack (svc/handle-request rooted "GET"
                                       (str "/node/" (store/hash->hex h))
                                       nil
                                       {:accept bin/content-type-chunk-v1})]
          (is (= (store/require-node rooted h)
                 (store/await (store/s-get packed h))))
          (is (= 200 (:status pack)))
          (is (= bin/content-type-chunk-v1 (:content-type pack)))))
      (finally
        ((:close! primary))
        ((:close! fresh))
        (stop!)))))

(defn- open-reveal
  "POST /reveal and return the response once its headers are in.
   The body stays open on `:in`."
  [client h3-url h]
  (let [body (wire/write-edn {:hash (store/hash->hex h)})
        listener (InputStreamResponseListener.)
        req (-> (.newRequest client (str h3-url "/reveal"))
                (.method "POST")
                (.version HttpVersion/HTTP_3)
                (.body (StringRequestContent. "application/edn" body))
                (.timeout 20 TimeUnit/SECONDS)
                (.idleTimeout 20 TimeUnit/SECONDS))]
    (.send req listener)
    (let [resp (.get listener 20 TimeUnit/SECONDS)]
      {:status (.getStatus resp)
       :type (str (.get (.getHeaders resp) "Content-Type"))
       :version (.getVersion resp)
       :in (.getInputStream listener)
       :listener listener})))

(defn- wait-for-other-page
  "True once `pack` holds some page and does not yet hold `avoid`."
  [pack avoid]
  (loop [n 0]
    (let [have (set (store/await (store/s-keys pack)))]
      (cond
        (contains? have avoid) false
        (seq have) true
        (>= n 200) :timeout
        :else (do (Thread/sleep 20)
                  (recur (inc n)))))))

(deftest focused-child-is-its-own-stream
  (let [rooted (svc/make-demo-rooted)
        inner (v/vector rooted (v/i64 rooted 2))
        child (v/hash inner)
        parent (v/hash (v/vector rooted (v/i64 rooted 1) inner))
        order (reveal/page-order rooted parent)
        child-order (reveal/page-order rooted child)
        total (reduce + 0 (map #(reveal/page-bytes rooted %) order))
        ;; The whole parent takes about two seconds, so the response is
        ;; still open when the child request is sent.
        r-max (/ (double total) 2.0)
        {:keys [h3-url stop!]} (svc/start-server! {:port 0
                                                   :rooted rooted
                                                   :reveal {:peak 1.0
                                                            :tau 1.0e6
                                                            :floor 0.0
                                                            :r-max r-max}})
        primary (reveal-client/open-client {:trust-all true})
        parent-items (atom [])
        child-items (atom [])
        parent-head (atom nil)
        saw-page (promise)
        parent-eof (promise)]
    (try
      (future
        (try
          (let [opened (open-reveal (:client primary) h3-url parent)]
            (reset! parent-head (select-keys opened [:status :type :version]))
            (try
              (when-not (= 200 (:status opened))
                (throw (ex-info "parent reveal failed" opened)))
              (reveal-client/read-items!
               (:in opened)
               (fn [item]
                 (swap! parent-items conj item)
                 (when-not (realized? saw-page)
                   (deliver saw-page true))))
              (deliver parent-eof (System/nanoTime))
              (finally
                (.close (:listener opened)))))
          (catch Throwable t
            (when-not (realized? saw-page) (deliver saw-page t))
            (when-not (realized? parent-eof) (deliver parent-eof t)))))
      (let [gate (deref saw-page 15000 :timeout)]
        (is (true? gate))
        (when (true? gate)
          (let [sent (System/nanoTime)
                opened (open-reveal (:client primary) h3-url child)]
            (try
              (is (= HttpVersion/HTTP_3 (:version @parent-head)))
              (is (= 200 (:status @parent-head)))
              (is (= rv/content-type (:type @parent-head)))
              (is (= HttpVersion/HTTP_3 (:version opened)))
              (is (= 200 (:status opened)))
              (is (= rv/content-type (:type opened)))
              (reveal-client/read-items! (:in opened)
                                         #(swap! child-items conj %))
              (finally
                (.close (:listener opened))))
            (let [eof (deref parent-eof 15000 :timeout)
                  parent-hashes (mapv :hash @parent-items)
                  child-hashes (mapv :hash @child-items)
                  dest (rv/accept-items! (store/mem-store)
                                         (concat @parent-items @child-items))]
              (is (number? eof))
              (is (< sent eof)
                  "the parent response was still open")
              (is (< 1 (count child-order)))
              (is (seq parent-hashes))
              (is (seq child-hashes))
              (is (empty? (filter (set parent-hashes) child-hashes))
                  "a page offered on one stream is not sent on the other")
              (is (every? (set child-order) child-hashes))
              (is (= (set order) (set (concat parent-hashes child-hashes))))
              (is (= (store/require-node rooted parent)
                     (store/require-node dest parent)))
              (is (= (store/require-node rooted child)
                     (store/require-node dest child)))))))
      (finally
        ((:close! primary))
        (stop!)))))

(deftest remote-get-focuses-a-child-while-the-parent-streams
  (let [rooted (svc/make-demo-rooted)
        inner (v/vector rooted (v/i64 rooted 2))
        child (v/hash inner)
        parent (v/hash (v/vector rooted (v/i64 rooted 1) inner))
        order (reveal/page-order rooted parent)
        total (reduce + 0 (map #(reveal/page-bytes rooted %) order))
        r-max (/ (double total) 2.0)
        {:keys [base-url h3-url stop!]} (svc/start-server!
                                         {:port 0
                                          :rooted rooted
                                          :reveal {:peak 1.0
                                                   :tau 1.0e6
                                                   :floor 0.0
                                                   :r-max r-max}})
        primary (reveal-client/open-client {:trust-all true})
        via (remote/remote-store base-url
                                 {:reveal {:url h3-url
                                           :client (:client primary)}})]
    (try
      (let [parent-p (store/s-get via parent)
            ready (wait-for-other-page (:pack-local via) child)]
        (is (true? ready)
            "the parent stream has a page, and the child is not local yet")
        (when (true? ready)
          (is (= (store/require-node rooted child)
                 (store/await (store/s-get via child))))
          (is (= (store/require-node rooted parent)
                 (store/await parent-p)))))
      (finally
        ((:close! primary))
        (stop!)))))

(deftest a-child-read-waits-for-pages-the-parent-already-offered
  (let [rooted (svc/make-demo-rooted)
        child (v/set rooted (v/i64 rooted 1) (v/i64 rooted 2))
        ch (v/hash child)
        parent (v/hash (v/vector rooted child))
        {:keys [h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        primary (reveal-client/open-client {:trust-all true})
        local (store/mem-store)
        waiting (atom [])
        inflight (atom 0)
        items (atom [])
        read-done (promise)
        hold (promise)
        sibling-wait (promise)
        session {:url h3-url
                 :client (:client primary)
                 :waiting waiting
                 :inflight inflight
                 :sibling-wait sibling-wait}]
    (try
      (swap! inflight inc)
      (future
        (try
          (let [opened (open-reveal (:client primary) h3-url parent)]
            (try
              (reveal-client/read-items! (:in opened)
                                         #(swap! items conj %))
              (deliver read-done true)
              (deref hold)
              (swap! waiting
                     (fn [pending]
                       (rv/accept-ready! local (into pending @items))))
              (finally
                (.close (:listener opened)))))
          (catch Throwable t
            (when-not (realized? read-done)
              (deliver read-done t)))
          (finally
            (swap! inflight dec))))
      (is (true? (deref read-done 15000 :timeout))
          "the parent stream has the child's pages")
      (let [root-item (first (filter #(= ch (:hash %)) @items))
            side (store/mem-store)
            still (rv/accept-ready! side [root-item])
            child-p (future (reveal-client/fetch! session ch local))]
        (is (some? root-item))
        (is (seq still)
            "the set root waits on structural pages")
        (is (nil? (store/await (store/s-get side ch))))
        (is (true? (deref sibling-wait 15000 :timeout))
            "the child waits for the parent stream")
        (deliver hold true)
        (is (= (store/require-node rooted ch)
               (deref child-p 15000 :timeout)))
        (is (= (store/require-node rooted ch)
               (store/await (store/s-get local ch)))))
      (finally
        (deliver hold true)
        ((:close! primary))
        (stop!)))))

(deftest root-notice-stays-up-beside-reveals
  (let [rooted (svc/make-demo-rooted)
        inner (v/vector rooted (v/i64 rooted 2))
        child (v/hash inner)
        parent (v/hash (v/vector rooted (v/i64 rooted 1) inner))
        notice (v/hash (v/i64 rooted 9))
        order (reveal/page-order rooted parent)
        total (reduce + 0 (map #(reveal/page-bytes rooted %) order))
        ;; JUSTIFICATION for the time (rule: a test over about 3 s is broken
        ;; unless it says why). Most of it is starting a real HTTP/3 server
        ;; and client (QUIC handshakes, a root-notice stream). The pacing
        ;; is the point: the whole parent takes about half a second, so both
        ;; reveal bodies are still open when the root changes. A reveal that
        ;; ends sooner could not show the notice arriving beside it.
        r-max (* 2.0 total)
        {:keys [base-url h3-url stop!]} (svc/start-server!
                                         {:port 0
                                          :rooted rooted
                                          :reveal {:peak 1.0
                                                   :tau 1.0e6
                                                   :floor 0.0
                                                   :r-max r-max}})
        primary (reveal-client/open-client {:trust-all true})
        events (remote/remote-store base-url)
        parent-items (atom [])
        child-items (atom [])
        parent-head (atom nil)
        saw-page (promise)
        parent-eof (promise)
        first-ev (promise)
        noticed (promise)
        watch (remote/watch-root
               events
               (fn [h]
                 (cond
                   (not (realized? first-ev)) (deliver first-ev h)
                   (= h notice) (deliver noticed (System/nanoTime)))))]
    (try
      (is (nil? (deref first-ev 5000 :timeout))
          "the root notice is up before either reveal")
      (future
        (try
          (let [opened (open-reveal (:client primary) h3-url parent)]
            (reset! parent-head (select-keys opened [:status :type :version]))
            (try
              (when-not (= 200 (:status opened))
                (throw (ex-info "parent reveal failed" opened)))
              (reveal-client/read-items!
               (:in opened)
               (fn [item]
                 (swap! parent-items conj item)
                 (when-not (realized? saw-page)
                   (deliver saw-page true))))
              (deliver parent-eof (System/nanoTime))
              (finally
                (.close (:listener opened)))))
          (catch Throwable t
            (when-not (realized? saw-page) (deliver saw-page t))
            (when-not (realized? parent-eof) (deliver parent-eof t)))))
      (let [gate (deref saw-page 15000 :timeout)]
        (is (true? gate))
        (when (true? gate)
          (let [opened (open-reveal (:client primary) h3-url child)]
            (try
              (is (true? (remote/remote-cas-root! events nil notice)))
              (let [at (deref noticed 5000 :timeout)]
                (reveal-client/read-items! (:in opened)
                                           #(swap! child-items conj %))
                (let [eof (deref parent-eof 15000 :timeout)
                      dest (rv/accept-items! (store/mem-store)
                                             (concat @parent-items
                                                     @child-items))]
                  (is (= HttpVersion/HTTP_3 (:version @parent-head)))
                  (is (= 200 (:status @parent-head)))
                  (is (= rv/content-type (:type @parent-head)))
                  (is (= HttpVersion/HTTP_3 (:version opened)))
                  (is (= 200 (:status opened)))
                  (is (= rv/content-type (:type opened)))
                  (is (number? at))
                  (is (number? eof))
                  (is (< at eof)
                      "the new root arrived while the parent reveal was still open")
                  (is (seq @parent-items))
                  (is (seq @child-items))
                  (is (= (store/require-node rooted parent)
                         (store/require-node dest parent)))
                  (is (= (store/require-node rooted child)
                         (store/require-node dest child)))))
              (finally
                (.close (:listener opened)))))))
      (finally
        ((:stop! watch))
        ((:close! primary))
        (stop!)))))

(defn- read-reveal
  "Read one open reveal to EOF. Deliver `done` with the head, or a throwable.

   When `opened-p` is a promise, deliver it once the response headers
   are in and the body is still open."
  [client h3-url h items opened-p done]
  (try
    (let [opened (open-reveal client h3-url h)]
      (when opened-p
        (deliver opened-p true))
      (when-not (= 200 (:status opened))
        (throw (ex-info "reveal failed" opened)))
      (try
        (reveal-client/read-items! (:in opened) #(swap! items conj %))
        (deliver done {:status (:status opened)
                       :type (:type opened)
                       :version (:version opened)
                       :at (System/nanoTime)})
        (finally
          (.close (:listener opened)))))
    (catch Throwable t
      (when (and opened-p (not (realized? opened-p)))
        (deliver opened-p t))
      (when-not (realized? done)
        (deliver done t)))))

(deftest a-second-post-for-the-same-hash-does-not-clobber-the-first
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/vector rooted (v/i64 rooted 1) (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        total (reduce + 0 (map #(reveal/page-bytes rooted %) order))
        r-max (/ (double total) 2.0)
        {:keys [h3-url stop!]} (svc/start-server! {:port 0
                                                   :rooted rooted
                                                   :reveal {:peak 1.0
                                                            :tau 1.0e6
                                                            :floor 0.0
                                                            :r-max r-max}})
        primary (reveal-client/open-client {:trust-all true})
        a-items (atom [])
        b-items (atom [])
        a-open (promise)
        a-done (promise)
        b-done (promise)]
    (try
      (future (read-reveal (:client primary) h3-url h a-items a-open a-done))
      (is (true? (deref a-open 15000 :timeout))
          "the first response is open")
      (future (read-reveal (:client primary) h3-url h b-items nil b-done))
      (let [a (deref a-done 15000 :timeout)
            b (deref b-done 15000 :timeout)
            a-hashes (mapv :hash @a-items)
            b-hashes (mapv :hash @b-items)
            dest (rv/accept-items! (store/mem-store)
                                   (concat @a-items @b-items))]
        (is (map? a))
        (is (map? b))
        (is (= HttpVersion/HTTP_3 (:version a) (:version b)))
        (is (= 200 (:status a) (:status b)))
        (is (= rv/content-type (:type a) (:type b)))
        (is (empty? (filter (set a-hashes) b-hashes)))
        (is (= (set order) (set (concat a-hashes b-hashes))))
        (is (= (store/require-node rooted h)
               (store/require-node dest h))))
      (finally
        ((:close! primary))
        (stop!)))))

(defn- stored-hashes
  [pack]
  (set (store/await (store/s-keys pack))))

(defn- held-hashes
  "Pages stored, plus items still waiting on a child."
  [pack waiting]
  (into (stored-hashes pack) (map :hash) @waiting))

(deftest a-paused-reveal-ends-and-a-re-ask-resumes
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/vector rooted (v/i64 rooted 1) (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        total (double (reduce + 0 (map #(reveal/page-bytes rooted %) order)))
        {:keys [h3-url stop!]} (svc/start-server!
                                {:port 0
                                 :rooted rooted
                                 :reveal {:peak 1.0
                                          :tau 0.2
                                          :floor 0.1
                                          :r-max (/ total 0.4)}})
        primary (reveal-client/open-client {:trust-all true})
        local (store/mem-store)
        session {:url h3-url
                 :client (:client primary)
                 :waiting (atom [])}]
    (try
      (is (= 5 (count order)))
      ;; One run from the peak to this floor banks
      ;; r-max * tau * (1 - floor/peak) = 0.45 of the bytes. The re-ask
      ;; starts another run. Two runs are 0.9 of the bytes, so the value
      ;; finishes on a later ask, not on the second fetch.
      (let [started (System/nanoTime)
            _ (reveal-client/fetch! session h local)
            elapsed (/ (- (System/nanoTime) started) 1.0e9)
            n (count (held-hashes local (:waiting session)))]
        (is (< elapsed 10.0) "the pause ends the response")
        (is (< 0 n (count order)))
        ;; A re-ask can bank credit and end before the next page fits.
        ;; The value still finishes within a few asks.
        ;; How many re-asks it takes depends on the clock (the reveal is paced),
        ;; so the loop only collects; the assertions after it are the same
        ;; three on every run.
        (let [{:keys [done? kept?]}
              (loop [tries 0
                     prev (held-hashes local (:waiting session))
                     kept? true]
                (cond
                  (= (set order) (stored-hashes local)) {:done? true :kept? kept?}
                  (>= tries 4) {:done? false :kept? kept?}
                  :else
                  (do
                    (reveal-client/fetch! session h local)
                    (let [now (held-hashes local (:waiting session))]
                      (recur (inc tries) now (and kept? (every? now prev)))))))]
          (is done? "a re-ask finishes the value")
          (is kept? "a re-ask does not drop a page already held")
          (is (or (not done?)
                  (= (store/require-node rooted h)
                   (store/await (store/s-get local h)))))))
      (finally
        ((:close! primary))
        (stop!)))))

(deftest a-reveal-past-its-budget-ends-and-a-re-ask-resumes
  (let [rooted (svc/make-demo-rooted)
        text (apply str (repeat 12000 "x"))
        h (v/hash (v/string rooted text))
        order (reveal/page-order rooted h)
        total (double (reduce + 0 (map #(reveal/page-bytes rooted %) order)))
        {:keys [h3-url stop!]} (svc/start-server!
                                {:port 0
                                 :rooted rooted
                                 ;; budget = r-max * tau = a quarter of the value,
                                 ;; sent in about a quarter of a second
                                 :reveal {:peak 1.0
                                          :tau 0.25
                                          :floor 0.0
                                          :r-max (* total 1.0)}})
        primary (reveal-client/open-client {:trust-all true})
        local (store/mem-store)
        session {:url h3-url
                 :client (:client primary)
                 :waiting (atom [])}]
    (try
      (let [started (System/nanoTime)
            _ (reveal-client/fetch! session h local)
            elapsed (/ (- (System/nanoTime) started) 1.0e9)
            n (count (held-hashes local (:waiting session)))]
        (is (< elapsed 10.0) "the budget ends the response")
        (is (< 0 n (count order)))
        ;; One ask banks the unspent integral. The next page can be larger
        ;; than what that ask sends, so a round trip may add no bytes.
        ;; How many re-asks it takes depends on the clock (the reveal is paced),
        ;; so the loop only collects; the assertions after it are the same
        ;; three on every run.
        (let [{:keys [done? kept?]}
              (loop [tries 0
                     prev (held-hashes local (:waiting session))
                     kept? true]
                (cond
                  (= (set order) (stored-hashes local)) {:done? true :kept? kept?}
                  (>= tries 8) {:done? false :kept? kept?}
                  :else
                  (do
                    (reveal-client/fetch! session h local)
                    (let [now (held-hashes local (:waiting session))]
                      (recur (inc tries) now (and kept? (every? now prev)))))))]
          (is done? "a re-ask finishes the value")
          (is kept? "a re-ask does not drop a page already held")
          (is (or (not done?)
                  (= \x (v/realize (v/nth (v/wrap-hash local h) 11999)))))))
      (finally
        ((:close! primary))
        (stop!)))))

(deftest a-bad-fuse-state-in-an-item-is-a-bad-entry
  ;; The peer item reader turns the decoder's :bad-state into the peer
  ;; protocol's bad-entry (docs/spec/peer-v1.md section 3.3), keeping the
  ;; decoder's reason in :detail; other errors pass through.
  (let [st (store/mem-store)
        m (apply v/vector st (map #(v/string st (str "line " % " the quick brown fox")) (range 300)))
        h (v/hash m)
        order (reveal/page-order st h)
        pick (fn [x] (let [e (store/await (store/s-get st x))] (when (= "ft/digit" (first e)) [x e])))
        [hh entry] (some pick order)
        item (bin/encode-item {:enc :node :hash hh :entry entry})
        split-items @#'reveal-client/split-items
        state (vec (dacite.hash/state->bytes (:elements-fuse (:measure (second entry)))))
        bs (byte-array item)
        off (first (filter #(= state (mapv (fn [i] (bit-and 0xff (aget bs (+ % i)))) (range 128)))
                           (range (- (alength bs) 127))))]
    (is (some? off))
    (is (= 1 (count (first (split-items bs)))) "the untouched item reads")
    (aset bs (int off) (unchecked-byte (bit-xor 1 (aget bs (int off)))))
    (let [e (try (split-items bs) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :bad-entry (:reason (ex-data e))))
      (is (= :bad-state (:detail (ex-data e))))
      (is (= :bad-state (:reason (ex-data (ex-cause e))))))))

(defn- body-hashes
  [out]
  (mapv :hash (bin/decode-item-stream (.toByteArray ^ByteArrayOutputStream out))))

(defn- reveal-call
  "Run `f` (`begin-reveal!`) on its own thread.

   `f` returns as soon as the reveal finishes, pauses, or spends its
   budget. The 3 s bound fails the test if that call is instead waiting
   on the 20 s idle timeout. On the timeout path, stop the connection
   so the thread is not left parked on the promise."
  [conn f]
  (let [p (promise)
        t (Thread. #(deliver p (try [:ok (f)]
                                    (catch Throwable e [:err (ex-message e)]))))]
    (.start t)
    (let [r (deref p 3000 [:timeout])]
      (when (= [:timeout] r)
        (rv/stop-conn! conn))
      r)))

(deftest a-duplicate-request-does-not-clobber-the-first
  (let [s (store/mem-store)
        h (v/hash (v/vector s (v/i64 s 1) (v/i64 s 2)
                            (v/string s (apply str (repeat 2000 "y")))))
        order (reveal/page-order s h)
        conn (rv/start-conn s rv/default-params)
        a-out (ByteArrayOutputStream.)
        b-out (ByteArrayOutputStream.)
        gate (promise)
        run (fn [out]
              (deref gate)
              (reveal-call conn #(rv/begin-reveal! conn h out)))]
    (try
      (let [a (future (run a-out))
            b (future (run b-out))]
        (Thread/sleep 20)
        (deliver gate true)
        (let [a-r (deref a 4000 [:timeout])
              b-r (deref b 4000 [:timeout])
              ah (body-hashes a-out)
              bh (body-hashes b-out)]
          (is (= [:ok :ok] a-r))
          (is (= [:ok :ok] b-r))
          (is (empty? (filter (set ah) bh)))
          (is (= (set order) (set (concat ah bh))))
          (is (some #(and (= h (:hash %)) (:done? %))
                    (:reveals @(:session conn))))))
      (finally
        (rv/stop-conn! conn)))))

(deftest a-paused-reveal-ends-without-the-idle-timeout
  (let [s (store/mem-store)
        h (v/hash (v/string s (apply str (repeat 4000 "x"))))
        order (reveal/page-order s h)
        total (double (reduce + 0 (map #(reveal/page-bytes s %) order)))
        conn (rv/start-conn s {:peak 1.0 :tau 0.2 :floor 0.1 :r-max (/ total 0.4)})
        out (ByteArrayOutputStream.)]
    (try
      (let [r (reveal-call conn #(rv/begin-reveal! conn h out))
            n (count (body-hashes out))
            rev (first (filter #(= h (:hash %)) (:reveals @(:session conn))))]
        (is (= [:ok :ok] r))
        (is (< 0 n (count order)))
        (is (some? rev))
        (is (not (:done? rev))))
      (finally
        (rv/stop-conn! conn)))))

(deftest a-lone-reveal-larger-than-its-budget-completes
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 7))
        n (double (reveal/page-bytes s h))
        conn (rv/start-conn s {:peak 1.0 :tau 1.0 :floor 0.0 :r-max (/ n 2.5)})]
    (try
      (let [result (loop [i 0 got #{}]
                     (cond
                       (contains? got h) {:ok true :got got :calls i}
                       (>= i 6) {:ok false :got got :calls i}
                       :else
                       (let [out (ByteArrayOutputStream.)
                             r (reveal-call conn #(rv/begin-reveal! conn h out))]
                         (if (= [:ok :ok] r)
                           (recur (inc i) (into got (set (body-hashes out))))
                           {:ok false :got got :calls i :err r}))))]
        (is (:ok result) (pr-str result))
        (is (contains? (:got result) h))
        (is (<= 1 (:calls result) 6)))
      (finally
        (rv/stop-conn! conn)))))
