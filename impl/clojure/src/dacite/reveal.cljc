(ns dacite.reveal
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

   The walk, the clock, and ack/release are `dacite.reveal.engine`.
   This namespace reads the store for them: a node entry, HAMT order,
   and the wire-item size."
  (:require [dacite.reveal.engine :as engine]
            [dacite.store :as store]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(defn- nodes
  "Node source over `store` for `dacite.reveal.engine`."
  [store]
  {:node-entry
   (fn [h]
     (let [n (store/require-node store h)
           data (types/entry-data n)]
       {:type (types/entry-type n)
        :root (when (map? data) (:root data))
        :child-hashes (into [] (remove nil?) (or (types/child-hashes n) []))}))
   :assoc-order
   (fn [root kind]
     (let [entries (hamt/hamt-entries store root)]
       (if (= kind "set")
         (map first entries)
         (concat (map first entries) (map second entries)))))
   :node-bytes
   (fn [h]
     (let [node (store/require-node store h)]
       (bin/byte-len (bin/encode-item {:enc :node :hash h :entry node}))))})

(defn page-order
  "Hashes a default reveal of `h` would send, in order.

   The client can focus a child as soon as an earlier page has named
   that child's hash. Bodies follow the shape's guess: first-most for
   sequences, keys then values for a map, element bodies for a set."
  [store h]
  (engine/page-order (nodes store) h))

(defn omit-sent
  "Hashes from `pages` that this session has not already sent, in order."
  [pages sent]
  (engine/omit-sent pages sent))

(defn interest
  "Interest at `age`: `peak * exp(-age / tau)`. `tau` must be positive.
   `age` and `tau` share a time unit."
  [peak tau age]
  (engine/interest peak tau age))

(defn shares
  "Byte-per-second share of `:r-max` for each reveal still in the sum.

   `reveals` is a sequence of maps with `:id` and `:opened-at`, and
   optional `:cancelled?` and `:done?`. Time `t` uses the same unit as
   `:tau`. Interest below `:floor` pauses that reveal. Cancel and done
   drop it immediately. The share is `r-max * I / max(peak, sum I)`.
   The shares fill `:r-max` while total interest is at least `:peak`.
   Below that each reveal is paid `r-max * I / peak`, so one reveal
   alone decays from the ceiling."
  [reveals t params]
  (engine/shares reveals t params))

(defn next-reveal
  "Id of the reveal that receives the next page, or nil when none remain.

   The largest share wins. Equal shares go to the request with the
   later `:opened-at`."
  [reveals t params]
  (engine/next-reveal reveals t params))

(defn page-bytes
  "Wire-v1 item bytes of the node at `h`.

   That item is the page a reveal sends, whole. The pack chunk envelope
   is a property of today's `GET /node` response, not of the page."
  [store h]
  (engine/page-bytes (nodes store) h))

(defn session
  "An empty reveal session. The clock starts on the first call that
   names a time. Nothing has been offered or held."
  []
  (engine/session))

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
  [session store h t params]
  (engine/open-reveal session (nodes store) h t params))

(defn cancel-reveal
  "Cancel the reveal `id` at time `t`.

   Pages already due by `t` are returned. Later pages of this reveal
   are not sent. The reveal is dropped, so asking again is a new id.
   Hashes already offered stay offered until `ack-pages` or
   `forget-pages`.

   Returns `{:session :pages}`."
  [session store id t params]
  (engine/cancel-reveal session (nodes store) id t params))

(defn ack-pages
  "The client holds `hashes`. Each must be an offer that is not yet held.

   A held hash is not offered again unless `forget-pages`."
  [session hashes]
  (engine/ack-pages session hashes))

(defn forget-pages
  "The client does not hold `hashes`.

   A reset drops an offer the client never kept. An eviction drops a
   hash it no longer keeps. The page is queued again on the reveal that
   offered it, when that reveal is still open. A cancelled reveal is
   already gone; a later `open-reveal` of a value that contains the hash
   sends it."
  [session store hashes]
  (engine/forget-pages session (nodes store) hashes))

(defn take-pages
  "Pages the session would offer from its clock up to time `t`.

   Each page is `{:id :hash :bytes :at}`. `:at` is the time the credit
   covered that page. The page is offered, not held, until `ack-pages`.
   The clock then stands at `t`.

   Returns `{:session :pages}`."
  [session store t params]
  (engine/take-pages session (nodes store) t params))

(defn stalled?
  "True when reveal `id` will not offer another page until it is asked again.

   It still has a page, and either its interest is below the floor or the
   current share cannot cover that page. In the decay regime the remaining
   integral is `rate * tau` bytes. A finished reveal is not stalled."
  [session id t params]
  (engine/stalled? session id t params))
