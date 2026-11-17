(ns dacite.reveal.pages
  "Reveal pages on the JVM: the writer's page encoder, and the reader's
   check of revealed items against their hashes.

   `encode-page` is the wire-v1 item for one page a `dacite.reveal`
   session offered. `accept-items!` verifies decoded items and puts them,
   holding a collection root until the child it names is stored.

   These were in `dacite.service.reveal`, the HTTP `POST /reveal` writer,
   which the HTTP removal (slice 2) deleted. They are reveal code, not
   HTTP."
  (:require [dacite.peer.verify :as verify]
            [dacite.reveal :as reveal]
            [dacite.store :as store]))

(defn encode-page
  "Wire-v1 item for one page the session has offered.

   The session measured this page by encoding it once and kept those
   bytes on `:wire`. Writing them again would encode the page twice (N11).
   The bytes are `dacite.reveal/encode-page`. This wrapper keeps the JVM
   byte-array return hint."
  ^bytes [store page]
  (reveal/encode-page store page))

(def expected-hash
  "Content hash of a stored node, or nil when a child it needs is absent.
   The portable check lives in `dacite.peer.verify/expected-hash`."
  verify/expected-hash)

(defn- take-ready
  "Store items whose hash can be checked. Return those still waiting.

   A literal or a hash mismatch throws. The mismatched item is not stored."
  [store items]
  (reduce
   (fn [waiting {:keys [enc hash entry] :as item}]
     (when-not (= :node enc)
       (throw (ex-info "reveal page is a node" {:enc enc})))
     (if-let [got (expected-hash store entry)]
       (do
         (when-not (= got hash)
           (throw (ex-info "reveal page hash mismatch"
                           {:expected (store/hash->hex hash)
                            :got (store/hash->hex got)})))
         (store/await (store/s-put store hash entry))
         waiting)
       (conj waiting item)))
   []
   items))

(defn accept-ready!
  "Store every item that can be checked now.

   Returns the items still waiting on a child. A mismatch throws and is
   not stored. An item that waits is not a failure yet."
  [store items]
  (loop [pending (vec items)]
    (if (empty? pending)
      []
      (let [still (take-ready store pending)]
        (if (= (count still) (count pending))
          still
          (recur still))))))

(defn accept-items!
  "Verify each item against its hash, then put it.

   A collection root waits until the child it names is already stored.
   A mismatch throws and is not stored. Returns `store`."
  [store items]
  (let [store (or store (store/mem-store))
        still (accept-ready! store items)]
    (when (seq still)
      (throw (ex-info "reveal page could not be verified"
                      {:hashes (mapv #(store/hash->hex (:hash %)) still)})))
    store))
