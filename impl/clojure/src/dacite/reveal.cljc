(ns dacite.reveal
  "Default reveal order and interest shares.

   The asked node comes first. A collection then sends its own pages, so
   every child hash is known, and then the subvalue bodies its shape
   would ask for next. A string, blob, or vector continues first-most.
   A map sends every key body, then every value body, in the HAMT's
   key-hash order. A set sends each element body once, in that order.
   A scalar is one node. An inlined finger-tree leaf stays on its page
   and is not requested again. A hash already listed is not listed again.

   Interest is `peak * exp(-age / tau)`. Shares of `r-max` are those
   interests renormalized. Below the floor a reveal pauses and drops
   out of the sum. Cancel drops it at once.

   No path, header, or frame. `peak`, `tau`, the floor, and `r-max` are
   arguments. This namespace does not choose production values, and it
   does not open a stream."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]))

(def ^:private seq-types #{"string" "blob" "vector"})
(def ^:private assoc-types #{"map" "set"})

(defn- exp
  [x]
  #?(:clj (Math/exp (double x))
     :cljs (js/Math.exp x)))

(defn- structural-type?
  [t]
  (and (string? t)
       (or (str/starts-with? t "ft/")
           (str/starts-with? t "hamt/"))))

(defn- child-hash-vec
  [node]
  (into [] (remove nil?) (or (types/child-hashes node) [])))

(defn- structural-children
  "Child hashes that are finger-tree or HAMT pages, in stored order."
  [store node]
  (filterv (fn [ch]
             (structural-type?
              (types/entry-type (store/require-node store ch))))
           (child-hash-vec node)))

(defn- ref-children
  "Child hashes that are values, not structural pages."
  [store node]
  (into []
        (remove (fn [ch]
                  (structural-type?
                   (types/entry-type (store/require-node store ch)))))
        (child-hash-vec node)))

(defn- all-structural-pages
  "Own pages under a tree root, this page first, then children in stored
   order. A leaf value hash contributes nothing."
  [store h]
  (let [n (store/require-node store h)
        t (types/entry-type n)]
    (if-not (structural-type? t)
      []
      (into [h] (mapcat #(all-structural-pages store %)
                        (structural-children store n))))))

(defn- seq-element-refs
  "Value hashes a sequence page points at, left to right.

   Inlined leaves are not refs, so they are absent. A bare leaf root is
   the one element."
  [store root]
  (let [n (store/require-node store root)]
    (if (structural-type? (types/entry-type n))
      (mapcat (fn [page]
                (ref-children store (store/require-node store page)))
              (all-structural-pages store root))
      [root])))

(defn- assoc-refs
  "Key hashes, then value hashes, in HAMT key-hash order.

   A set stores the element as both, so each element is named once."
  [store root kind]
  (let [entries (hamt/hamt-entries store root)]
    (if (= kind "set")
      (map first entries)
      (concat (map first entries) (map second entries)))))

(defn page-order
  "Hashes a default reveal of `h` would send, in order.

   The client can focus a child as soon as an earlier page has named
   that child's hash. Bodies follow the shape's guess: first-most for
   sequences, keys then values for a map, element bodies for a set."
  [store h]
  (let [seen (volatile! #{})]
    (letfn [(take! [hash]
              (when-not (contains? @seen hash)
                (vswap! seen conj hash)
                hash))
            (fresh-pages [hashes]
              (into [] (keep take!) hashes))
            (walk [hash]
              (if-let [emitted (take! hash)]
                (let [n (store/require-node store hash)
                      t (types/entry-type n)]
                  (cond
                    (contains? seq-types t)
                    (let [root (:root (types/entry-data n))]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages store root))
                                    (mapcat walk (seq-element-refs store root)))))

                    (contains? assoc-types t)
                    (let [root (:root (types/entry-data n))]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages store root))
                                    (mapcat walk (assoc-refs store root t)))))

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
   drop it immediately. The values sum to `:r-max` when any reveal
   remains in the sum."
  [reveals t {:keys [peak tau floor r-max]}]
  (let [weighted (keep (fn [r]
                         (when-not (or (:cancelled? r) (:done? r))
                           (let [age (- t (:opened-at r))
                                 i (interest peak tau age)]
                             (when (>= i floor)
                               [(:id r) i]))))
                       reveals)
        sum (reduce + 0.0 (map second weighted))]
    (if (pos? sum)
      (into {} (map (fn [[id i]] [id (* (double r-max) (/ i sum))]) weighted))
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
