(ns dacite.store.lens-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.store :as s]
            [dacite.store.lens :as lens]
            [dacite.value :as v]))

(defn- seed-ab
  "Document {\"a\" 1 \"b\" 2} on a mem rooted store."
  []
  (let [rs (s/mem)
        r (v/root rs)
        seed (v/map r "a" 1 "b" 2)]
    (v/cas! r nil seed)
    rs))

(deftest empty-path-is-identity
  (let [rs (s/mem)]
    (is (identical? rs (s/lens rs [])))
    (is (identical? rs (s/lens rs nil)))
    (is (identical? rs (s/lens rs (v/vector rs))))))

(deftest nested-lens-concatenates-path
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
      (is (= 1 (v/realize @(v/root inner)))))))

(deftest missing-path-seeds-via-cas-from-nil
  (let [rs (s/mem)
        la (s/lens rs ["a"])
        x (v/i64 rs 1)]
    (is (nil? (s/root la)))
    (is (true? (s/cas-root! la nil (v/hash x))))
    (is (= (v/hash x) (s/root la)))
    (is (= 1 (v/realize (v/get @(v/root rs) "a"))))))

(deftest disjoint-path-cas-both-succeed
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        lb (s/lens rs ["b"])
        ha (s/root la)
        hb (s/root lb)
        a2 (v/i64 rs 10)
        b2 (v/i64 rs 20)]
    (is (true? (s/cas-root! la ha (v/hash a2))))
    (is (true? (s/cas-root! lb hb (v/hash b2))))
    (let [doc @(v/root rs)]
      (is (= 10 (v/realize (v/get doc "a"))))
      (is (= 20 (v/realize (v/get doc "b")))))))

(deftest same-path-cas-one-fails
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        ha (s/root la)
        a2 (v/i64 rs 10)
        a3 (v/i64 rs 11)]
    (is (true? (s/cas-root! la ha (v/hash a2))))
    (is (false? (s/cas-root! la ha (v/hash a3))))
    (is (= 10 (v/realize @(v/root la))))))

(deftest concurrent-disjoint-cas
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        lb (s/lens rs ["b"])
        ha (s/root la)
        hb (s/root lb)
        a2 (v/i64 rs 10)
        b2 (v/i64 rs 20)
        start (promise)
        fa (future (deref start) (s/cas-root! la ha (v/hash a2)))
        fb (future (deref start) (s/cas-root! lb hb (v/hash b2)))]
    (deliver start true)
    (is (true? @fa))
    (is (true? @fb))
    (let [doc @(v/root rs)]
      (is (= 10 (v/realize (v/get doc "a"))))
      (is (= 20 (v/realize (v/get doc "b")))))))

(deftest swap-on-lens-does-not-clobber-sibling
  (let [rs (seed-ab)
        ra (v/root (s/lens rs ["a"]))
        rb (v/root (s/lens rs ["b"]))]
    (v/swap! ra (fn [x] (v/i64 x 10)))
    (v/swap! rb (fn [x] (v/i64 x 20)))
    (is (= 10 (v/realize @ra)))
    (is (= 20 (v/realize @rb)))))

(deftest watches-skip-sibling-edits
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

(deftest s-get-via-lens-equals-parent
  (let [rs (seed-ab)
        la (s/lens rs ["a"])
        h (s/root la)]
    (is (= (s/s-get rs h) (s/s-get la h)))
    (is (s/s-has? la h))))

(deftest vector-index-path
  (let [rs (s/mem)
        r (v/root rs)]
    (v/cas! r nil (v/vector r 10 20 30))
    (let [l (s/lens rs [1])]
      (is (= 20 (v/realize @(v/root l))))
      (is (true? (s/cas-root! l (s/root l) (v/hash (v/i64 rs 99)))))
      (is (= 99 (v/realize (v/nth @(v/root rs) 1))))
      (is (= 10 (v/realize (v/nth @(v/root rs) 0)))))
    (let [via-i64 (s/lens rs (v/vector rs 1))]
      (is (= 99 (v/realize @(v/root via-i64)))))))

(deftest dacite-vector-path-matches-host-seq
  (let [rs (seed-ab)
        host (s/lens rs ["a"])
        dv (s/lens rs (v/vector rs "a"))]
    (is (= (s/root host) (s/root dv)))
    (is (= 1 (v/realize @(v/root dv))))))

(deftest dacite-value-as-map-key
  (let [rs (s/mem)
        r (v/root rs)
        k (v/map rs "id" 1)
        vk (v/vector rs "x")
        seed (v/map r k "payload" vk 7)]
    (v/cas! r nil seed)
    (let [by-map (s/lens rs (v/vector rs k))
          by-vec (s/lens rs (v/vector rs vk))]
      (is (= "payload" (v/native @(v/root by-map))))
      (is (= 7 (v/realize @(v/root by-vec))))
      (is (true? (s/cas-root! by-map (s/root by-map)
                              (v/hash (v/string rs "next")))))
      (is (= "next" (v/native (v/get @(v/root rs) k)))))))

(deftest file-backed-disjoint-lenses
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "dacite-lens-" (System/currentTimeMillis)))]
    (try
      (let [rs (s/file (.getPath dir) {:reset true})
            r (v/root rs)
            seed (v/map r "books" (v/set r) "indexes" (v/map r "title" (v/vector r)))]
        (v/cas! r nil seed)
        (let [books (s/lens rs (v/vector rs "books"))
              titles (s/lens rs (v/vector rs "indexes" "title"))
              rec (v/map rs "title" "Moby-Dick")
              idx (v/conj @(v/root titles) rec)]
          (is (true? (s/cas-root! books (s/root books) (v/hash (v/conj @(v/root books) rec)))))
          (is (true? (s/cas-root! titles (s/root titles) (v/hash idx))))))
      (let [rs (s/file (.getPath dir))
            books (s/lens rs (v/vector rs "books"))
            titles (s/lens rs (v/vector rs "indexes" "title"))]
        (is (= 1 (v/count @(v/root books))))
        (is (= 1 (v/count @(v/root titles))))
        (is (lens/lens-store? books)))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))
