(ns dacite.rooted-checks
  "Rooted store and root cell checks that run on the JVM and on nbb.

   These were the bodies of `dacite.rooted-test`. The JVM runs them from that
   namespace, one deftest each under its old name, with the JVM file and LMDB
   stores. nbb runs them from `dacite.rooted-cljs-test` (`npm run rooted:test`)
   with its own. The host `env` is the one of `dacite.store-checks`.

   On the JVM a rooted store is also an atom: `@s`, `reset!`, `swap!`,
   `compare-and-set!`, `add-watch`, and `set-validator!`. Those conveniences
   are JVM only by design (see `dacite.rooted`). The JVM checks still use
   them. On nbb the same steps are the portable function API: `rs/root`,
   `rs/set-root!`, `rs/update-root!`, `rs/cas-root!`, `rs/add-root-watch`,
   and `rs/set-root-validator!`.

   A durable root cell on nbb reads a hash back as BigInt words, so those
   compares are by hex.

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing #?(:cljs throws?)]]))

;; The JVM atom conveniences, or the function API on nbb.
(defn- root-of [s] #?(:clj @s :cljs (rs/root s)))
(defn- reset-root! [s h] #?(:clj (reset! s h) :cljs (rs/set-root! s h)))
(defn- swap-root! [s f] #?(:clj (swap! s f) :cljs (rs/update-root! s f)))
(defn- cas! [s expected new]
  #?(:clj (compare-and-set! s expected new) :cljs (rs/cas-root! s expected new)))
(defn- watch! [s k f] #?(:clj (add-watch s k f) :cljs (rs/add-root-watch s k f)))
(defn- unwatch! [s k] #?(:clj (remove-watch s k) :cljs (rs/remove-root-watch s k)))
(defn- validator! [s f]
  #?(:clj (set-validator! s f) :cljs (rs/set-root-validator! s f)))

(defn- invalid-state?
  "True when `(f)` throws the validator's error: IllegalStateException on
   the JVM, an Error on nbb."
  [f]
  #?(:clj (try (f) false (catch IllegalStateException _ true))
     :cljs (throws? #"Invalid reference state" f)))

(defn- hex [h] (when h (store/hash->hex h)))

(defn- in-dir
  "`(f dir)` in a new temp directory, removed after."
  [env f]
  (let [dir ((:tmp-dir env) "dacite-rooted-test-")]
    (try (f dir)
         (finally ((:rm-rf env) dir)))))

;; =============================================================================
;; Root cell
;; =============================================================================

(defn mem-root-cell-test
  [_]
  (testing "mem root cell roundtrip"
    (let [cell (rs/mem-root-cell [1 2 3 4])]
      (is (= [1 2 3 4] (rs/rc-get cell)))
      (rs/rc-put! cell [5 6 7 8])
      (is (= [5 6 7 8] (rs/rc-get cell))))))

(defn lmdb-root-cell-test
  [env]
  (testing "lmdb root cell persists across instances"
    (in-dir env
            (fn [dir]
              (let [lmdb ((:lmdb-store env) ((:join env) dir "lmdb-root-cell"))
                    cell ((:lmdb-root-cell env) lmdb)]
                (try
                  (rs/rc-put! cell [1 2 3 4])
                  (is (= (hex [1 2 3 4]) (hex (rs/rc-get ((:lmdb-root-cell env) lmdb)))))
                  (finally
                    ((:lmdb-close env) lmdb))))))))

;; =============================================================================
;; Rooted store — core ref operations
;; =============================================================================

(defn rooted-store-seed-test
  [_]
  (testing "constructor seeds root from cell"
    (let [s (rs/rooted-store (store/mem-store) (rs/mem-root-cell [1 2 3 4]))]
      (is (= [1 2 3 4] (root-of s))))))

(defn rooted-store-reset-test
  [_]
  (testing "reset! installs a new root"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset-root! s [1 2 3 4])
      (is (= [1 2 3 4] (root-of s))))))

(defn rooted-store-swap-test
  [_]
  (testing "swap! transforms the root"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset-root! s [1 0 0 0])
      (swap-root! s (fn [h] (vec (map inc h))))
      (is (= [2 1 1 1] (root-of s))))))

(defn rooted-store-cas-success-test
  [_]
  (testing "compare-and-set! succeeds when expected matches"
    (let [s (rs/rooted-store (store/mem-store))
          r (do (reset-root! s [1 2 3 4]) (root-of s))]
      (is (true? (cas! s r [5 6 7 8])))
      (is (= [5 6 7 8] (root-of s))))))

(defn rooted-store-cas-conflict-test
  [_]
  (testing "compare-and-set! fails when root moved"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset-root! s [1 2 3 4])
      (is (false? (cas! s [9 9 9 9] [5 6 7 8])))
      (is (= [1 2 3 4] (root-of s))))))

;; =============================================================================
;; Watches and validators
;; =============================================================================

(defn rooted-store-watch-test
  [_]
  (testing "watch fires with the rooted store as ref"
    (let [s (rs/rooted-store (store/mem-store))
          seen (atom nil)]
      (watch! s :test (fn [k r o n] (reset! seen {:k k :r r :o o :n n})))
      (reset-root! s [1 2 3 4])
      (is (= :test (:k @seen)))
      (is (identical? s (:r @seen)))
      (is (nil? (:o @seen)))
      (is (= [1 2 3 4] (:n @seen)))
      (unwatch! s :test)
      (reset! seen nil)
      (reset-root! s [5 6 7 8])
      (is (nil? @seen)))))

(defn rooted-store-validator-test
  [_]
  (testing "validator rejects invalid roots"
    (let [s (rs/rooted-store (store/mem-store))]
      (validator! s vector?)
      (reset-root! s [1 2 3 4])
      (is (invalid-state? #(reset-root! s "not-a-hash"))))))

;; =============================================================================
;; Durability
;; =============================================================================

(defn rooted-store-durability-test
  [env]
  (testing "root survives reopen via lmdb root cell"
    (in-dir env
            (fn [dir]
              (let [lmdb ((:lmdb-store env) ((:join env) dir "lmdb-durable"))
                    cell ((:lmdb-root-cell env) lmdb)]
                (try
                  (let [s1 (rs/rooted-store (store/mem-store) cell)]
                    (reset-root! s1 [1 2 3 4]))
                  (let [s2 (rs/rooted-store (store/mem-store) cell)]
                    (is (= (hex [1 2 3 4]) (hex (root-of s2)))))
                  (finally
                    ((:lmdb-close env) lmdb))))))))

(defn file-root-cell-durability-test
  [env]
  (testing "file root cell persists hex across instances"
    (in-dir env
            (fn [dir]
              (let [path ((:join env) dir "file-root")
                    cell1 (rs/file-root-cell path)]
                (rs/rc-put! cell1 [1 2 3 4])
                (is (= (hex [1 2 3 4]) (hex (rs/rc-get (rs/file-root-cell path)))))
                (let [s1 (rs/rooted-store ((:file-store env) path) (rs/file-root-cell path))]
                  (rs/set-root! s1 [5 6 7 8])
                  (is (= [5 6 7 8] (rs/root s1)))
                  (is (= (hex [5 6 7 8])
                         (hex (rs/root (rs/rooted-store ((:file-store env) path)
                                                        (rs/file-root-cell path))))))))))))

(defn portable-root-api-test
  [_]
  (testing "root / cas-root! / set-root! / update-root!"
    (let [s (rs/rooted-store (store/mem-store))]
      (is (nil? (rs/root s)))
      (is (= [1 2 3 4] (rs/set-root! s [1 2 3 4])))
      (is (true? (rs/cas-root! s [1 2 3 4] [5 6 7 8])))
      (is (= [5 6 7 8] (rs/root s)))
      (is (false? (rs/cas-root! s [0 0 0 0] [9 9 9 9])))
      (is (= [6 7 8 9] (rs/update-root! s (fn [h] (mapv inc h))))))))

;; =============================================================================
;; IStore delegation
;; =============================================================================

(defn rooted-store-delegates-content-test
  [_]
  (testing "content ops delegate to inner store"
    (let [content (store/mem-store)
          s (rs/rooted-store content)]
      (store/await (store/s-put s [1 2 3 4] :value))
      (is (= :value (store/await (store/s-get content [1 2 3 4]))))
      (is (= :value (store/await (store/s-get s [1 2 3 4])))))))

;; =============================================================================
;; push-ref
;; =============================================================================

(defn push-ref-success-test
  [_]
  (testing "push-ref moves target root to source root"
    (let [source (rs/rooted-store (store/mem-store))
          target (rs/rooted-store (store/mem-store))
          r1 [1 2 3 4]
          r2 [5 6 7 8]]
      (reset-root! source r2)
      (reset-root! target r1)
      (is (true? (rs/push-ref source target)))
      (is (= r2 (root-of target))))))

(defn push-ref-cas-conflict-test
  [_]
  (testing "push-ref returns false when target root no longer matches"
    (let [source (rs/rooted-store (store/mem-store))
          target (rs/rooted-store (store/mem-store))
          r1 [1 2 3 4]
          r2 [5 6 7 8]
          r3 [9 9 9 9]]
      (reset-root! source r2)
      (reset-root! target r1)
      (reset-root! target r3)
      (is (false? (cas! target r1 r2)))
      (is (= r3 (root-of target))))))

(def checks
  {"mem-root-cell-test" mem-root-cell-test
   "lmdb-root-cell-test" lmdb-root-cell-test
   "rooted-store-seed-test" rooted-store-seed-test
   "rooted-store-reset-test" rooted-store-reset-test
   "rooted-store-swap-test" rooted-store-swap-test
   "rooted-store-cas-success-test" rooted-store-cas-success-test
   "rooted-store-cas-conflict-test" rooted-store-cas-conflict-test
   "rooted-store-watch-test" rooted-store-watch-test
   "rooted-store-validator-test" rooted-store-validator-test
   "rooted-store-durability-test" rooted-store-durability-test
   "file-root-cell-durability-test" file-root-cell-durability-test
   "portable-root-api-test" portable-root-api-test
   "rooted-store-delegates-content-test" rooted-store-delegates-content-test
   "push-ref-success-test" push-ref-success-test
   "push-ref-cas-conflict-test" push-ref-cas-conflict-test})
