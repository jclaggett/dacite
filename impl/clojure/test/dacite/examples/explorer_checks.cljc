(ns dacite.examples.explorer-checks
  "Value explorer: type coverage, seed-once, paged children, on the JVM and
   on nbb. The checks share one seeded gallery (`gallery`).

   These were the bodies of `dacite.examples.explorer-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [clojure.set :as set]
            [dacite.examples.explorer :as ex]
            [dacite.value :as v]))

;; One seeded gallery for every check here. Building it is most of the time
;; on nbb (`page-me` is 512 maps, #63), so it is built once. The checks only
;; read it: `load-or-seed-does-not-overwrite` calls `ex/load-or-seed!` on its
;; root again, which reads the root and writes nothing when it is set.
(def ^:private shared
  (delay (let [r (v/root (ex/open-mem))
               [g seeded?] (ex/load-or-seed! r)]
           {:r r :g g :seeded? seeded?})))

(defn gallery
  "The shared gallery: {:r its root, :g the value, :seeded? true if this
   seed wrote it}. The first call builds it."
  []
  @shared)

;; JUSTIFICATION for the time (rule: a check over about 1.5 s needs one): about
;; 2.2 s on nbb. `ex/collect-types` walks the whole gallery, `page-me`'s 512
;; maps included, at the nbb cost of #63. Its nbb ceiling is in `slow-ms`.
(defn gallery-covers-every-public-type
  [_]
  (let [{:keys [g seeded?]} (gallery)
        types (ex/collect-types g)]
    (is (true? seeded?))
    (is (= "map" (v/type g)))
    (is (= ex/public-types types)
        (str "missing " (set/difference ex/public-types types)
             " extra " (set/difference types ex/public-types)))
    (is (= ex/page-me-count (v/count (v/get g "page-me"))))))

(defn load-or-seed-does-not-overwrite
  [_]
  (let [{:keys [r g]} (gallery)
        h (v/hash g)
        [g2 seeded?] (ex/load-or-seed! r)]
    (is (false? seeded?))
    (is (= h (v/hash g2)))))

(defn row-summary-does-not-seq-children
  [_]
  (let [{:keys [g]} (gallery)
        row (ex/row-summary g)
        s (ex/row-summary (v/get g "string"))
        b (ex/row-summary (v/get g "blob"))
        i64 (ex/row-summary (v/get (v/get g "scalars") "i64"))]
    (is (= :map (:kind row)))
    (is (= (v/count g) (:count row)))
    (is (nil? (:preview row)))
    (is (= "string" (:type s)))
    (is (true? (:truncated? s)))
    (is (= ex/string-preview-chars (count (:preview s))))
    (is (> (:count s) ex/string-preview-chars))
    (is (= "blob" (:type b)))
    (is (true? (:truncated? b)))
    (is (= "i64" (:type i64)))
    (is (= "-64" (:native i64)))))

(defn child-page-bounds-vector
  [_]
  (let [{:keys [g]} (gallery)
        page-me (v/get g "page-me")
        p0 (ex/child-page page-me 0)
        p1 (ex/child-page page-me ex/page-size)]
    (is (= ex/page-me-count (:total p0)))
    (is (= ex/page-size (count (:items p0))))
    (is (false? (:done? p0)))
    (is (= 0 (:label (first (:items p0)))))
    (is (= (dec ex/page-size) (:label (last (:items p0)))))
    (is (= ex/page-size (:offset p1)))
    (is (= ex/page-size (count (:items p1))))
    (is (true? (:done? (ex/child-page page-me (- ex/page-me-count ex/page-size)))))))

(defn child-page-map-keys-are-values
  [_]
  (let [{:keys [g]} (gallery)
        m (v/get g "map")
        {:keys [items total]} (ex/child-page m 0)]
    (is (= 2 total))
    (is (every? #(v/dacite-value? (:label %)) items))
    (is (some #(= "vector" (v/type (:label %))) items))
    (is (some #(= "string" (v/type (:label %))) items))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"gallery-covers-every-public-type" gallery-covers-every-public-type
   "load-or-seed-does-not-overwrite" load-or-seed-does-not-overwrite
   "row-summary-does-not-seq-children" row-summary-does-not-seq-children
   "child-page-bounds-vector" child-page-bounds-vector
   "child-page-map-keys-are-values" child-page-map-keys-are-values})

(def slow-ms
  "nbb ceilings above `check/max-ms`, each with a JUSTIFICATION on its check."
  {"gallery-covers-every-public-type" 4500})
