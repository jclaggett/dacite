(ns dacite.store.lens-checks
  "Lens checks (a rooted store at a nested path) that run on the JVM and
   on nbb.

   These were the bodies of `dacite.store.lens-test`. The JVM runs them from
   that namespace, one deftest each under its old name. nbb runs them from
   `dacite.store.store-units-cljs-test` (`npm run store-units:test`).
   `concurrent-disjoint-cas` stays JVM only: it races two threads (`future`),
   and nbb has one thread.

   `is` is `dacite.store.check`."
  (:require [dacite.store :as s]
            [dacite.store.check :refer [is]]
            [dacite.store.lens :as lens]
            [dacite.value :as v]))

(defn- deref*
  "The value of root ref `r`. On nbb a SCI type has no IDeref, so `@r` is
   `v/deref` there."
  [r]
  #?(:clj @r :cljs (v/deref r)))

(defn seed-ab
  "Document {\"a\" 1 \"b\" 2} on a mem rooted store."
  []
  (let [rs (s/mem)
        r (v/root rs)
        seed (v/map r "a" 1 "b" 2)]
    (v/cas! r nil seed)
    rs))

(defn empty-path-is-identity
  [_]
  (let [rs (s/mem)]
    (is (identical? rs (s/lens rs [])))
    (is (identical? rs (s/lens rs nil)))
    (is (identical? rs (s/lens rs (v/vector rs))))))

(defn nested-lens-concatenates-path
  [_]
  (let [rs (s/mem)
        r (v/root rs)
        seed (v/map r "m" (v/map r "k" 1))]
    (v/cas! r nil seed)
    (let [inner (s/lens (s/lens rs ["m"]) ["k"])
          direct (s/lens rs ["m" "k"])
          via-dv (s/lens (s/lens rs (v/vector rs "m"))
                         (v/vector rs "k"))]
      (is (= (s/root direct) (s/root inner)))
      (is (= (s/root direct) (s/root via-dv)))
      (is (= 1 (v/realize (deref* (v/root inner))))))))

(defn missing-path-seeds-via-cas-from-nil
  [_]
  (let [rs (s/mem)
        la (s/lens rs ["a"])
        x (v/i64 rs 1)]
    (is (nil? (s/root la)))
    (is (true? (s/cas-root! la nil (v/hash x))))
    (is (= (v/hash x) (s/root la)))
    (is (= 1 (v/realize (v/get (deref* (v/root rs)) "a"))))))

(defn disjoint-path-cas-both-succeed
  [_]
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        lb (s/lens rs ["b"])
        ha (s/root la)
        hb (s/root lb)
        a2 (v/i64 rs 10)
        b2 (v/i64 rs 20)]
    (is (true? (s/cas-root! la ha (v/hash a2))))
    (is (true? (s/cas-root! lb hb (v/hash b2))))
    (let [doc (deref* (v/root rs))]
      (is (= 10 (v/realize (v/get doc "a"))))
      (is (= 20 (v/realize (v/get doc "b")))))))

(defn same-path-cas-one-fails
  [_]
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        ha (s/root la)
        a2 (v/i64 rs 10)
        a3 (v/i64 rs 11)]
    (is (true? (s/cas-root! la ha (v/hash a2))))
    (is (false? (s/cas-root! la ha (v/hash a3))))
    (is (= 10 (v/realize (deref* (v/root la)))))))

(defn swap-on-lens-does-not-clobber-sibling
  [_]
  (let [rs (seed-ab)
        ra (v/root (s/lens rs ["a"]))
        rb (v/root (s/lens rs ["b"]))]
    (v/swap! ra (fn [x] (v/i64 x 10)))
    (v/swap! rb (fn [x] (v/i64 x 20)))
    (is (= 10 (v/realize (deref* ra))))
    (is (= 20 (v/realize (deref* rb))))))

(defn watches-skip-sibling-edits
  [_]
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        lb (s/lens rs ["b"])
        seen (atom [])]
    (s/add-root-watch la :w (fn [_k _rs _o n] (swap! seen conj n)))
    (is (true? (s/cas-root! lb (s/root lb) (v/hash (v/i64 rs 20)))))
    (is (empty? @seen))
    (is (true? (s/cas-root! la (s/root la) (v/hash (v/i64 rs 10)))))
    (is (= 1 (count @seen)))
    (is (= (s/root la) (first @seen)))
    (s/remove-root-watch la :w)
    (s/cas-root! la (s/root la) (v/hash (v/i64 rs 11)))
    (is (= 1 (count @seen)))))

(defn s-get-via-lens-equals-parent
  [_]
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        h (s/root la)]
    (is (= (s/await (s/s-get rs h)) (s/await (s/s-get la h))))
    (is (s/await (s/s-has? la h)))))

(defn vector-index-path
  [_]
  (let [rs (s/mem)
        r (v/root rs)]
    (v/cas! r nil (v/vector r 10 20 30))
    (let [l (s/lens rs [1])]
      (is (= 20 (v/realize (deref* (v/root l)))))
      (is (true? (s/cas-root! l (s/root l) (v/hash (v/i64 rs 99)))))
      (is (= 99 (v/realize (v/nth (deref* (v/root rs)) 1))))
      (is (= 10 (v/realize (v/nth (deref* (v/root rs)) 0)))))
    (let [via-i64 (s/lens rs (v/vector rs 1))]
      (is (= 99 (v/realize (deref* (v/root via-i64))))))))

(defn dacite-vector-path-matches-host-seq
  [_]
  (let [rs (seed-ab)
        host (s/lens rs ["a"])
        dv (s/lens rs (v/vector rs "a"))]
    (is (= (s/root host) (s/root dv)))
    (is (= 1 (v/realize (deref* (v/root dv)))))))

(defn dacite-value-as-map-key
  [_]
  (let [rs (s/mem)
        r (v/root rs)
        k (v/map rs "id" 1)
        vk (v/vector rs "x")
        seed (v/map r k "payload" vk 7)]
    (v/cas! r nil seed)
    (let [by-map (s/lens rs (v/vector rs k))
          by-vec (s/lens rs (v/vector rs vk))]
      (is (= "payload" (v/native (deref* (v/root by-map)))))
      (is (= 7 (v/realize (deref* (v/root by-vec)))))
      (is (true? (s/cas-root! by-map (s/root by-map)
                              (v/hash (v/string rs "next")))))
      (is (= "next" (v/native (v/get (deref* (v/root rs)) k)))))))

(defn file-backed-disjoint-lenses
  [env]
  (let [dir ((:tmp-dir env) "dacite-lens-")]
    (try
      (let [rs (s/file dir {:reset true})
            r (v/root rs)
            seed (v/map r "books" (v/set r) "indexes" (v/map r "title" (v/vector r)))]
        (v/cas! r nil seed)
        (let [books (s/lens rs (v/vector rs "books"))
              titles (s/lens rs (v/vector rs "indexes" "title"))
              rec (v/map rs "title" "Moby-Dick")
              idx (v/conj (deref* (v/root titles)) rec)]
          (is (true? (s/cas-root! books (s/root books) (v/hash (v/conj (deref* (v/root books)) rec)))))
          (is (true? (s/cas-root! titles (s/root titles) (v/hash idx))))))
      (let [rs (s/file dir)
            books (s/lens rs (v/vector rs "books"))
            titles (s/lens rs (v/vector rs "indexes" "title"))]
        (is (= 1 (v/count (deref* (v/root books)))))
        (is (= 1 (v/count (deref* (v/root titles)))))
        (is (lens/lens-store? books)))
      (finally
        ((:rm-rf env) dir)))))

(def checks
  {"empty-path-is-identity" empty-path-is-identity
   "nested-lens-concatenates-path" nested-lens-concatenates-path
   "missing-path-seeds-via-cas-from-nil" missing-path-seeds-via-cas-from-nil
   "disjoint-path-cas-both-succeed" disjoint-path-cas-both-succeed
   "same-path-cas-one-fails" same-path-cas-one-fails
   "swap-on-lens-does-not-clobber-sibling" swap-on-lens-does-not-clobber-sibling
   "watches-skip-sibling-edits" watches-skip-sibling-edits
   "s-get-via-lens-equals-parent" s-get-via-lens-equals-parent
   "vector-index-path" vector-index-path
   "dacite-vector-path-matches-host-seq" dacite-vector-path-matches-host-seq
   "dacite-value-as-map-key" dacite-value-as-map-key
   "file-backed-disjoint-lenses" file-backed-disjoint-lenses})
