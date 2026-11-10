(ns dacite.store.durable-checks
  "M3.3: one durable-store check and one peer check, parameterized over the
   nbb file store, nbb LMDB, and IndexedDB.

   The store check is get, put, has?, eviction, a key scan, and a write that is
   still there after the store is closed and opened again. Eviction passes
   `:cache-limit 1` (IndexedDB drops the older node from memory and reads it
   back; the file and LMDB stores have no read cache, so the same assertions
   are the durable contract: a put is not dropped).

   The peer check builds the library fixture, closes the store, opens it
   again, and has another peer read the root over the in-memory transport.
   The client store starts empty. The session is `dacite.peer.link` in both
   directions: the same responder and client every store uses. Entries are
   verified by the client; the reopened store is the only place that held them.

   Run from the repo root: `npm run store:test`.
   LMDB format v1 (JVM-compatible): `npm run lmdb:v1`."
  (:require [dacite.examples.library :as lib]
            [dacite.peer.client :as client]
            [dacite.peer.link :as link]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport.mem :as mem]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(def ^:private transfer-steps
  "Advances of the simulated clock while a transfer runs. A finished transfer
   returns before this. It is a cap, not a duration."
  2000)

(defn- explain [e]
  (str (or (ex-message e) e)
       (when-let [r (:reason (ex-data e))] (str " :" r))))

(defn- bad
  "A problem string when `actual` is not `expected`, otherwise nil."
  [what expected actual]
  (when-not (= expected actual)
    (str what ": " (pr-str actual) " <> " (pr-str expected))))

(defn- keep! [problems msg]
  (when (some? msg) (swap! problems conj msg))
  nil)

(defn- await-store
  "A promise of the value of store call `p`. A settled store promise and a
   `js/Promise` both work, which is what lets one check run on the file store
   and on IndexedDB."
  [p]
  (chain/native p))

(defn- promise?
  [x]
  (and (some? x) (fn? (.-then x))))

(defn- sync-store?
  "True when `st` answers `s-has?` with a settled store promise. IndexedDB
   answers with a `js/Promise` outside `drive`."
  [st]
  (let [p (store/s-has? st (store/hex->hash (apply str (repeat 64 "ab"))))]
    (when (promise? p) (.catch p (fn [_] nil)))
    (not (promise? p))))

(defn- shut [backend st]
  (when (some? st)
    (try ((:close backend) st)
         (catch :default _ nil))))

(defn- i64-node
  "A real i64 node and its hash, from a throwaway memory store."
  [n]
  (let [st (store/mem-store)
        h (v/hash (v/i64 st n))]
    {:hash h :node (store/await (store/s-get st h))}))

(defn- hex [h] (store/hash->hex h))

(defn- node-bytes [node]
  (vec (bin/encode-node-bytes node)))

(defn- same-node?
  "True when both nodes are the same on the wire. Host `=` is not that: a file
   store round trip gives a byte page a new `Uint8Array`, and the wire decoder
   reads a small `hamt/bitmap` as a number where the writer held a `BigInt`.
   Verification compares these bytes."
  [a b]
  (and (some? a) (some? b) (= (node-bytes a) (node-bytes b))))

(defn- lost-count
  "A promise of how many `entries` (hex → node) `st` does not still hold.
   Each hash is read with `s-get`."
  [st entries]
  (reduce (fn [p [hx node]]
            (-> p
                (.then (fn [n]
                         (-> (await-store (store/s-get st (store/hex->hash hx)))
                             (.then (fn [got]
                                      (if (same-node? node got) n (inc n)))))))))
          (js/Promise.resolve 0)
          entries))

(defn- mismatch-count
  "How many of `entries` (hex to node) are not the same node as `(lookup hx)`."
  [entries lookup]
  (count (filter (fn [[hx node]] (not (same-node? node (lookup hx)))) entries)))

;; =============================================================================
;; Store: get, put, has?, entry count, eviction, reopen
;; =============================================================================

(defn- roundtrip [problems backend]
  (let [place ((:fresh backend))
        st ((:open backend) place {})
        {:keys [hash node]} (i64-node 42)
        other (:hash (i64-node 99))]
    (-> (await-store (store/s-get st hash))
        (.then (fn [got]
                 (keep! problems (bad "get before put" nil got))
                 (await-store (store/s-has? st hash))))
        (.then (fn [has]
                 (keep! problems (bad "has? before put" false has))
                 (await-store (store/s-put st hash node))))
        (.then (fn [_] (await-store (store/s-has? st hash))))
        (.then (fn [has]
                 (keep! problems (bad "has? after put" true has))
                 (await-store (store/s-get st hash))))
        (.then (fn [got]
                 (keep! problems (bad "get after put" node got))
                 (await-store (store/s-get st other))))
        (.then (fn [got]
                 (keep! problems (bad "get of another hash" nil got))
                 (await-store (store/local-stats st))))
        (.then (fn [stats]
                 (keep! problems (bad "entry count" 1 (:entries stats)))
                 (await-store (store/s-has? st hash))))
        (.then (fn [has]
                 (keep! problems (bad "the hash is stored" true has))))
        (.finally (fn [] (shut backend st))))))

(defn- eviction
  "Two puts with a one-node cache. The older node is read back: IndexedDB
   evicts it from memory, the file and LMDB stores never dropped it."
  [problems backend]
  (let [place ((:fresh backend))
        st ((:open backend) place {:cache-limit 1})
        a (i64-node 1)
        b (i64-node 2)]
    (-> (await-store (store/s-put st (:hash a) (:node a)))
        (.then (fn [_] (await-store (store/s-put st (:hash b) (:node b)))))
        (.then (fn [_] (await-store (store/s-get st (:hash b)))))
        (.then (fn [got]
                 (keep! problems (bad "newest node" (:node b) got))
                 (await-store (store/s-get st (:hash a)))))
        (.then (fn [got]
                 (keep! problems (bad "evicted node is read back" (:node a) got))
                 (await-store (store/s-has? st (:hash a)))))
        (.then (fn [has]
                 (keep! problems (bad "evicted node has?" true has))
                 (await-store (store/s-has? st (:hash a)))))
        (.then (fn [has-a]
                 (keep! problems (bad "evicted node still stored" true has-a))
                 (await-store (store/s-has? st (:hash b)))))
        (.then (fn [has-b]
                 (keep! problems (bad "newest node still stored" true has-b))
                 (await-store (store/local-stats st))))
        (.then (fn [stats]
                 (keep! problems (bad "entry count after eviction" 2 (:entries stats)))))
        (.finally (fn [] (shut backend st))))))

(defn- reopen
  "Write, close, open the same place, read. The node is still there."
  [problems backend]
  (let [place ((:fresh backend))
        st ((:open backend) place {})
        {:keys [hash node]} (i64-node 7)
        other (:hash (i64-node 8))]
    (-> (await-store (store/s-put st hash node))
        (.then (fn [_]
                 (shut backend st)
                 (let [again ((:open backend) place {})]
                   (-> (await-store (store/s-get again hash))
                       (.then (fn [got]
                                (keep! problems (bad "reopen get" node got))
                                (await-store (store/s-has? again hash))))
                       (.then (fn [has]
                                (keep! problems (bad "reopen has?" true has))
                                (await-store (store/s-has? again other))))
                       (.then (fn [has]
                                (keep! problems (bad "reopen of an unwritten hash" false has))
                                (await-store (store/local-stats again))))
                       (.then (fn [stats]
                                (keep! problems (bad "reopen entry count" 1 (:entries stats)))
                                (keep! problems (bad "reopen still has the hash" true
                                                     (store/await (store/s-has? again hash))))))
                       (.finally (fn [] (shut backend again)))))))
        (.catch (fn [e]
                  (shut backend st)
                  (throw e))))))

(defn- batch-when-offered
  "When `backend` has `:write-batch`, that function stores two nodes.
   A file store has no batch writer. A promise of nil."
  [problems backend]
  (if-let [write (:write-batch backend)]
    (let [place ((:fresh backend))
          st ((:open backend) place {})
          a (i64-node 1)
          b (i64-node 2)
          pairs [[(:hash a) (:node a)] [(:hash b) (:node b)]]]
      (-> (await-store (write st pairs))
          (.then (fn [_] (await-store (store/s-get st (:hash a)))))
          (.then (fn [got]
                   (when-not (same-node? (:node a) got)
                     (keep! problems "batch did not store the first node"))
                   (await-store (store/s-get st (:hash b)))))
          (.then (fn [got]
                   (when-not (same-node? (:node b) got)
                     (keep! problems "batch did not store the second node"))))
          (.catch (fn [e]
                    (keep! problems (str "batch check threw " (explain e)))))
          (.finally (fn [] (shut backend st)))))
    (js/Promise.resolve nil)))

(defn store-problems
  "Problems of get, put, has?, entry count, eviction and reopen on `backend`:
   `:fresh` makes an empty place, `:open` takes the place and opts
   (`:cache-limit` when the store has a cache), `:close` releases it.
   A promise of `{:problems [...]}`."
  [backend]
  (let [problems (atom [])]
    (-> (roundtrip problems backend)
        (.then (fn [_] (eviction problems backend)))
        (.then (fn [_] (reopen problems backend)))
        (.then (fn [_] (batch-when-offered problems backend)))
        (.then (fn [_] {:problems @problems}))
        (.catch (fn [e]
                  {:problems (conj @problems (str "store check threw " (explain e)))})))))

;; =============================================================================
;; Peer: the library fixture, served from the reopened store
;; =============================================================================

(defn- library-on
  "The library fixture in sync store `st`, and the nodes a full reveal reads."
  [st]
  (let [catalog (lib/seed-library st)
        root (v/hash catalog)
        book (v/nth (lib/title-index catalog) 0)
        entries (into {} (map (fn [h] [(hex h) (store/await (store/s-get st h))]))
                      (reveal/page-order st root))]
    {:root root
     :title (lib/book-title book)
     :page (lib/chapter-page book 0 0 64)
     :entries entries}))

(defn- advance! [{:keys [clock r c ca]} ms]
  (swap! clock + ms)
  (responder/tick! r)
  (client/tick! c)
  (mem/pump! ca))

(defn- run-until!
  "Advance until `(done?)`, at most `transfer-steps` times. When `async?`, yield
   so a store promise can settle between advances. A promise of `(done?)`."
  [net done? async?]
  (if-not async?
    (js/Promise.resolve
     (loop [i 0]
       (if (or (>= i transfer-steps) (done?))
         (done?)
         (do (advance! net 50) (recur (inc i))))))
    (js/Promise.
     (fn [ok]
       (letfn [(step [i]
                 (if (or (>= i transfer-steps) (done?))
                   (ok (done?))
                   (do (advance! net 50)
                       (js/setTimeout #(step (inc i)) 0))))]
         (step 0))))))

(defn- peer-net
  "A responder over `src` and a client with an empty memory store, one
   in-memory connection, the `dacite-peer` channel. Same wiring for every
   durable store."
  [src]
  (let [clock (atom 0)
        [ca cb] (mem/connect {})
        rl (link/links)
        cl (link/links)
        dst (store/mem-store)
        sent (atom 0)
        ropts (link/responder-opts rl)
        r (responder/responder
           (merge {:store src :now (fn [] @clock)}
                  ropts
                  {:send! (fn [peer bs]
                            (swap! sent inc)
                            ((:send! ropts) peer bs))}))
        c (client/client (merge {:store dst :now (fn [] @clock)}
                                (link/client-opts cl)))]
    (link/serve! rl r "client" ca)
    (link/open-client-channel! cl c "server" cb)
    {:clock clock :ca ca :r r :c c :dst dst :sent sent}))

(defn- fetch
  "The client reads `(:root seeded)` from `st`. A promise of nil; problems land
   in the atom. `st` is the only store in the session that holds the value."
  [problems st seeded async?]
  (let [{:keys [root title page entries]} seeded
        {:keys [c dst r sent] :as net} (peer-net st)]
    (keep! problems (bad "client does not already hold the root" false
                         (store/await (store/s-has? dst root))))
    (keep! problems (bad "client requests the root" :requested (client/request! c root)))
    (-> (run-until! net #(client/done? c root) async?)
        (.then (fn [done]
                 (keep! problems (bad "client verified the value" true (boolean done)))
                 (keep! problems (bad "sessions stay open" [:open :open]
                                      [(client/session-status c "server")
                                       (responder/session-status r "client")]))
                 (let [fails (client/failed c)]
                   (when (seq fails)
                     (keep! problems (str "client store failures " (count fails)
                                          " " (explain (:error (first fails)))))))
                 (when-not done
                   (keep! problems (str "transfer stopped after " @sent " responder frames")))
                 (when done
                       (let [got (v/get-value dst root)
                         book (v/nth (lib/title-index got) 0)
                         differ (mismatch-count
                                 entries
                                 (fn [hx]
                                   (store/await (store/s-get dst (store/hex->hash hx)))))]
                     (keep! problems (bad "served title" title (lib/book-title book)))
                     (keep! problems (bad "served page" page (lib/chapter-page book 0 0 64)))
                     (keep! problems (bad "fixture title" lib/seed-title title))
                     (when (pos? differ)
                       (keep! problems (str differ " verified entries differ from the durable store")))))
                 nil)))))

(defn- serve-reopened
  "Open `place` (the fixture is already durable there), read it back, close,
   open again, and serve that second open. The second open is what the peer
   reads, so a cache filled by the check is not what it serves."
  [problems backend place seeded async?]
  (let [current (atom nil)
        shut! (fn [] (shut backend @current) (reset! current nil))
        open! (fn []
                (shut!)
                (let [st ((:open backend) place {})]
                  (reset! current st)
                  st))]
    (-> (let [st (open!)]
          (-> (await-store (store/s-get st (:root seeded)))
              (.then (fn [got]
                       (when-not (same-node? (get (:entries seeded) (hex (:root seeded))) got)
                         (keep! problems "reopened store returns a different library root"))
                       (lost-count st (:entries seeded))))
              (.then (fn [lost]
                       (when (pos? lost)
                         (keep! problems (str "reopen lost " lost " of "
                                              (count (:entries seeded)) " library nodes")))
                       (fetch problems (open!) seeded async?)))))
        (.finally shut!))))

(defn- seed-sync [problems backend place st nodes]
  (let [seeded (library-on st)]
    (reset! nodes (count (:entries seeded)))
    (when (or (not= (:title seeded) lib/seed-title) (empty? (:page seeded)))
      (keep! problems "the library fixture did not seed"))
    (shut backend st)
    (serve-reopened problems backend place seeded false)))

(defn- put-each
  "A promise of nil after `s-put` of every [hash value] pair."
  [st pairs]
  (reduce (fn [p [h node]]
            (-> p (.then (fn [_] (await-store (store/s-put st h node))))))
          (js/Promise.resolve nil)
          pairs))

(defn- seed-async [problems backend place st nodes]
  ;; A vector of pairs, not a map: a hash is a vector of BigInts, and SCI cannot
  ;; use that as a map key. Each pair is one `s-put`.
  (let [seeded (library-on (store/mem-store))
        pairs (mapv (fn [[hx node]] [(store/hex->hash hx) node])
                    (:entries seeded))]
    (reset! nodes (count (:entries seeded)))
    (when (or (not= (:title seeded) lib/seed-title) (empty? (:page seeded)))
      (keep! problems "the library fixture did not seed"))
    (-> (put-each st pairs)
        (.then (fn [_]
                 (shut backend st)
                 (serve-reopened problems backend place seeded true)))
        (.catch (fn [e]
                  (shut backend st)
                  (throw e))))))

(defn peer-problems
  "Problems of serving the library fixture from `backend` after a close and
   reopen. A promise of `{:problems [...] :nodes n}`. `n` is how many nodes the
   reveal holds, nil when seeding did not get that far."
  [backend]
  (let [problems (atom [])
        nodes (atom nil)
        place ((:fresh backend))
        st ((:open backend) place {})
        result (fn [] {:problems @problems :nodes @nodes})]
    (-> (js/Promise.resolve
         (if (sync-store? st)
           (seed-sync problems backend place st nodes)
           (seed-async problems backend place st nodes)))
        (.then (fn [_] (result)))
        (.catch (fn [e]
                  (shut backend st)
                  (keep! problems (str "peer check threw " (explain e)))
                  (result))))))
