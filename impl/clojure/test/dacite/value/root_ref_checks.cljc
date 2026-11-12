(ns dacite.value.root-ref-checks
  "Root ref checks (a value-level handle over a rooted store) that run on
   the JVM and on nbb.

   These were the bodies of `dacite.value.root-ref-test`. The JVM runs them
   from that namespace, one deftest each under its old name. nbb runs them
   from `dacite.rooted-cljs-test` (`npm run rooted:test`).

   `root-ref-reset-swap-deref` also checks the JVM atom ops on a root ref:
   `@r`, `swap!`, and `reset!`. A root ref is not an atom on nbb, by design
   (see `dacite.value.root-ref`), so there those steps are `v/deref`,
   `v/swap!`, and `root-ref/ref-reset!`.

   `is` is `dacite.store.check`."
  (:require [dacite.store :as store]
            [dacite.store.check :refer [is]]
            [dacite.value :as v]
            #?(:cljs [dacite.value.root-ref :as root-ref])))

;; The JVM atom ops, or the function API on nbb.
(defn- deref* [r] #?(:clj @r :cljs (v/deref r)))
(defn- swap* [r f & args]
  #?(:clj (apply swap! r f args) :cljs (apply v/swap! r f args)))
(defn- reset* [r x] #?(:clj (reset! r x) :cljs (root-ref/ref-reset! r x)))

(defn via-uses-peer-store
  [_]
  (let [st (store/mem-store)
        peer (v/i64 st 1)
        vec (v/vector peer 10 20)
        nested (v/map vec "a" 1 "b" (v/i64 vec 2))]
    (is (= st (v/dacite-store vec)))
    (is (= st (v/dacite-store nested)))
    (is (= 2 (v/count vec)))
    (is (= 2 (v/count nested)))
    (is (= (v/hash (v/vector st 10 20))
           (v/hash vec)))))

(defn via-accepts-istore
  [_]
  (let [st (store/mem-store)
        v (v/vector st 1 2 3)]
    (is (= st (v/dacite-store v)))
    (is (= 3 (v/count v)))))

(defn root-ref-reset-swap-deref
  [_]
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)]
    (is (nil? (v/deref r)))
    (is (nil? (deref* r)))
    (v/cas! r nil (v/vector r))
    (is (zero? (v/count (v/deref r))))
    (v/swap! r v/conj (v/i64 r 42))
    (is (= 1 (v/count (deref* r))))
    (is (= 42 (v/realize (v/nth (deref* r) 0))))
    ;; JVM atom ops (the function API on nbb)
    (swap* r v/conj 7)
    (is (= 2 (v/count (deref* r))))
    (reset* r (v/map r "k" "v"))
    (is (= "map" (v/type (deref* r))))
    (is (= "v" (apply str (v/realize (v/get (deref* r) "k")))))))

(defn root-ref-cas-and-watch
  [_]
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)
        seen (atom [])]
    (v/add-watch r :w (fn [_k _ref old new]
                        (swap! seen conj [(some-> old v/type)
                                          (some-> new v/type)])))
    (let [empty (v/vector r)
          one   (v/conj empty 1)]
      (is (true? (v/cas! r nil empty)))
      (is (false? (v/cas! r nil one))) ; expected still nil, root moved
      (is (true? (v/cas! r empty one))))
    (is (= [[nil "vector"] ["vector" "vector"]] @seen))
    (v/remove-watch r :w)
    (v/cas! r (v/deref r) nil)
    (is (= 2 (count @seen))))) ; no third notification after remove

(defn root-ref-is-store-carrier
  [_]
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)
        v  (v/vector r 1 2)]
    (is (v/root? r))
    (is (= (v/store-of r) rs))
    (is (= rs (v/dacite-store v)))))

(def checks
  {"via-uses-peer-store" via-uses-peer-store
   "via-accepts-istore" via-accepts-istore
   "root-ref-reset-swap-deref" root-ref-reset-swap-deref
   "root-ref-cas-and-watch" root-ref-cas-and-watch
   "root-ref-is-store-carrier" root-ref-is-store-carrier})
