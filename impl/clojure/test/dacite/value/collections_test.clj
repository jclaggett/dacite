(ns dacite.value.collections-test
  "Vectors, strings, blobs, maps, and sets in the value layer, on the JVM.

   The portable bodies are `dacite.value.collections-checks`. nbb runs the
   same checks (`npm run value:test`). One deftest each, so the JVM suite
   still names the test it ran before. Each deftest also keeps its
   assertions on the JVM's native collection interfaces and on the bounded
   toString (dacite.value.render), which a SCI type does not have; seven
   deftests are only that and stay here whole."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v2]
            [dacite.value.collections-checks :as checks]
            [dacite.value.render :as render]))

(def ^:private realize-each checks/realize-each)

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest vector-basics
  (run-check "vector-basics")
  ;; JVM only: native Counted, Indexed, ILookup, and IFn.
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)]
    (is (= 3 (count v)))
    (is (= 2 (v2/realize (nth v 1))))
    (is (= 2 (v2/realize (get v 1))))
    (is (= 2 (v2/realize (v 1))))
    (is (nil? (get v 99)))))

(deftest vector-remove-nth
  (run-check "vector-remove-nth"))

(deftest vector-empty-and-wrap
  (run-check "vector-empty-and-wrap")
  ;; JVM only: native empty and Counted.
  (let [v (v2/vector (store/mem-store) 1 2 3)]
    (is (zero? (count (empty v))))))

(deftest string-basics
  (run-check "string-basics")
  ;; JVM only: native Counted, the bounded toString, and CharSequence.
  (let [str-v (v2/string (store/mem-store) "hello")]
    (is (= 5 (count str-v)))
    (is (= "hello" (str str-v)))
    (is (= \h (.charAt ^CharSequence str-v 0)))))

(deftest blob-basics
  (run-check "blob-basics")
  ;; JVM only: native Counted.
  (let [b (v2/blob (store/mem-store) (byte-array [1 2 3 4]))]
    (is (= 4 (count b)))))

(deftest map-basics
  (run-check "map-basics")
  ;; JVM only: native Counted, ILookup, IFn, and contains?.
  (let [m (v2/map (store/mem-store) "a" 1 "b" 2)]
    (is (= 2 (count m)))
    (is (= 1 (v2/realize (get m "a"))))
    (is (= 2 (v2/realize (m "b"))))
    (is (nil? (get m "z")))
    (is (contains? m "a"))))

(deftest map-insertion-order-independent
  (run-check "map-insertion-order-independent"))

(deftest set-basics
  (run-check "set-basics")
  ;; JVM only: native Counted.
  (let [st (v2/set (store/mem-store) 1 2 3)]
    (is (= 3 (count st)))))

(deftest set-order-independent
  (run-check "set-order-independent"))

(deftest set-operations
  (run-check "set-operations"))

(deftest negative-sets
  (run-check "negative-sets"))

(deftest realize-collections-are-lazy-seqs
  (run-check "realize-collections-are-lazy-seqs"))

(deftest realize-map-yields-realized-pairs
  (run-check "realize-map-yields-realized-pairs"))

(deftest realize-is-deep-with-nested-collections
  (run-check "realize-is-deep-with-nested-collections"))

(deftest realize-empty-collections-are-nil
  (run-check "realize-empty-collections-are-nil")
  ;; JVM only: native empty.
  (let [s (store/mem-store)]
    (is (nil? (v2/realize (empty (v2/vector s 1 2 3)))))))

(deftest bounded-toString-small-collections
  (run-check "bounded-toString-small-collections")
  ;; JVM only: the bounded toString is dacite.value.render.
  (let [s (store/mem-store)]
    (is (= "[1 2 3]" (str (v2/vector s 1 2 3))))
    (is (re-find #"^#\{.+\}$" (str (v2/set s 1 2 3))))
    (is (= "<blob 4 bytes 0x01 02 03 04>" (str (v2/blob s (byte-array [1 2 3 4])))))))

;; JVM only: these assert on the native collection interfaces or on
;; dacite.value.render, which a SCI type does not have.

(deftest vector-conj-is-persistent
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)
        v' (conj v 4)]
    (is (= [1 2 3 4] (realize-each v')))
    (testing "original is unchanged (immutable)"
      (is (= [1 2 3] (realize-each v))))
    (testing "conj keeps the same store"
      (is (identical? (v2/dacite-store v) (v2/dacite-store v'))))))

(deftest vector-assoc-and-stack
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)]
    (is (= [1 9 3] (realize-each (assoc v 1 9))))
    (is (= 3 (v2/realize (peek v))))
    (is (= [1 2] (realize-each (pop v))))))

(deftest map-assoc-dissoc
  (let [s (store/mem-store)
        m (v2/map s "a" 1 "b" 2)
        m2 (assoc m "c" 3)
        m3 (dissoc m "a")]
    (is (= 3 (count m2)))
    (is (= 3 (v2/realize (get m2 "c"))))
    (testing "immutability"
      (is (= 2 (count m))))
    (is (= 1 (count m3)))
    (is (nil? (get m3 "a")))
    (is (= 2 (v2/realize (get m3 "b"))))))

(deftest vector-shape-independence
  (let [s (store/mem-store)
        bulk (apply v2/vector s (range 50))
        incremental (reduce conj (v2/vector s) (range 50))]
    (is (= (v2/hash bulk) (v2/hash incremental)))
    (is (= 50 (count bulk)))))

(deftest bounded-toString-truncates-large-collections
  (let [s (store/mem-store)
        v (apply v2/vector s (range 50))]
    (is (re-find #"… \(50 total\)" (str v)))
    (binding [render/*to-string-element-limit* 5]
      (is (re-find #"\[0 1 2 3 4 … \(50 total\)" (str v))))))

(deftest bounded-toString-truncates-large-strings
  (let [s (store/mem-store)
        long-str (apply str (repeat 100 \x))
        str-v (v2/string s long-str)]
    (is (= "hello" (str (v2/string s "hello"))))
    (binding [render/*to-string-char-limit* 10]
      (let [rendered (str str-v)]
        (is (re-find #"^\"x{10}…\" \(100 chars\)$" rendered))))))

(deftest print-respects-print-length
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3 4 5)]
    (is (= "[1 2 #]" (binding [*print-length* 2] (pr-str v))))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.collections-test)))))
               "every-check-has-a-deftest"
               "vector-conj-is-persistent"
               "vector-assoc-and-stack"
               "map-assoc-dissoc"
               "vector-shape-independence"
               "bounded-toString-truncates-large-collections"
               "bounded-toString-truncates-large-strings"
               "print-respects-print-length"))
      "value/collections_checks.cljc and this file list the same tests"))
