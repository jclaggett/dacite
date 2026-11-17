(ns dacite.value.path-checks
  "Field access and path updates (stay on the value, no dac->clj), on the
   JVM and on nbb.

   These were the bodies of `dacite.value.path-test`. The JVM runs them from
   that namespace, one deftest each under its old name. nbb runs them from
   `dacite.value-cljs-test` (`npm run value:test`). A `thrown?
   clojure.lang.ExceptionInfo` there is `throws-info?` here.

   `pr-str-renders-long-strings` also checked that `v/pr-str` of a vector
   renders its elements. That line stays in the JVM deftest: the collection
   render is `dacite.value.render` (.clj), and nbb prints the type name.

   `is` is `dacite.store.check`."
  (:require [dacite.store :as store]
            [dacite.store.check :refer [is throws-info?]]
            [dacite.value :as v]))

(defn- host-bytes
  "Host bytes for the ints `xs`."
  [xs]
  #?(:clj (byte-array xs) :cljs (js/Uint8Array.from (clj->js xs))))

(defn- ubytes
  "Host bytes as a vector of unsigned ints."
  [bs]
  #?(:clj (mapv #(bit-and % 0xFF) bs) :cljs (vec (js/Array.from bs))))

(defn native-field-access
  [_]
  (let [st (store/mem-store)
        s (v/string st "dark")
        n (v/i64 st 30)
        b (v/bool st true)]
    (is (= "dark" (v/native s)))
    (is (= 30 (v/native n)))
    (is (= true (v/native b)))
    (is (nil? (v/native nil)))
    (is (= "already" (v/native "already")))
    (is (throws-info? #(v/native (v/vector st 1 2))))
    (is (throws-info? #(v/native (v/map st "k" "v"))))))

(defn string-char-limit
  [_]
  (let [st (store/mem-store)
        long-s (v/string st (apply str (repeat 100 \x)))]
    (is (= (apply str (repeat 100 \x)) (v/native long-s)))
    (is (throws-info? #(v/native long-s 10)))
    (is (throws-info? #(v/native long-s 10)))
    (binding [v/*string-char-limit* 10]
      (is (throws-info? #(v/native long-s)))
      (is (throws-info? #(v/native long-s))))
    (is (= "dark" (v/native (v/string st "dark") 10)))))

(defn pr-str-renders-long-strings
  [_]
  (let [st (store/mem-store)
        short-s (v/string st "dark")
        long-s (v/string st (apply str (repeat 100 \x)))]
    (is (= "\"dark\"" (v/pr-str short-s)))
    (is (re-find #"^\"x{10}…\" \(100 chars\)$" (v/pr-str long-s 10)))
    (is (re-find #"^\"x{64}…\" \(100 chars\)$" (v/pr-str long-s)))
    (binding [v/*string-char-limit* 8]
      (is (re-find #"^\"x{8}…\" \(100 chars\)$" (v/pr-str long-s))))
    (is (= "nil" (v/pr-str nil)))))

(defn get-in-assoc-in
  [_]
  (let [st (store/mem-store)
        cfg (v/map st
                   "theme" "dark"
                   "timeout" 30
                   "features" (v/vector st "a" "b"))]
    (is (= "dark" (v/native (v/get-in cfg ["theme"]))))
    (is (= 30 (v/native (v/get-in cfg ["timeout"]))))
    (is (= "a" (v/native (v/get-in cfg ["features" 0]))))
    (is (= :missing (v/get-in cfg ["nope"] :missing)))
    (is (identical? cfg (v/get-in cfg [])))
    (let [cfg' (v/assoc-in cfg ["timeout"] 90)]
      (is (= 90 (v/native (v/get-in cfg' ["timeout"]))))
      (is (= 30 (v/native (v/get-in cfg ["timeout"])))
          "assoc-in is immutable"))
    (let [cfg' (v/assoc-in cfg ["nested" "x"] "y")]
      (is (= "y" (v/native (v/get-in cfg' ["nested" "x"])))))
    (is (throws-info? #(v/assoc-in cfg [] 1)))))

(defn update-and-update-in
  [_]
  (let [st (store/mem-store)
        cfg (v/map st
                   "timeout" 30
                   "features" (v/vector st "a"))]
    (is (= 31 (v/native (v/get (v/update cfg "timeout" (fn [n] (+ (v/native n) 1)))
                               "timeout"))))
    (let [cfg' (v/update cfg "features" v/conj "b")]
      (is (= 2 (v/count (v/get cfg' "features"))))
      (is (= "b" (v/native (v/nth (v/get cfg' "features") 1)))))
    (let [cfg' (v/update-in cfg ["features" 0] (fn [_] "z"))]
      (is (= "z" (v/native (v/get-in cfg' ["features" 0])))))))

(defn slice-shares-leaves-and-bounds
  [_]
  (let [st (store/mem-store)
        vec (v/vector st 0 1 2 3 4 5 6 7 8 9)
        mid (v/slice vec 3 7)]
    (is (= "vector" (v/type mid)))
    (is (= 4 (v/count mid)))
    (is (= 3 (v/native (v/nth mid 0))))
    (is (= 6 (v/native (v/nth mid 3))))
    (is (= (v/hash vec) (v/hash (v/slice vec 0))))
    (is (zero? (v/count (v/slice vec 4 4))))
    (let [built (v/vector st 3 4 5 6)]
      (is (= (v/hash built) (v/hash mid))
          "slice of the same elements has the same value hash"))
    (is (throws-info? #(v/slice vec 3 11)))
    (is (throws-info? #(v/slice vec -1 3)))
    (is (throws-info? #(v/slice (v/map st "k" 1) 0 1)))))

(defn slice-string-preserves-type-and-hash
  [_]
  (let [st (store/mem-store)
        s (v/string st "hello world")
        mid (v/slice s 6 11)]
    (is (= "string" (v/type mid)))
    (is (= "world" (v/native mid)))
    (is (= (v/hash (v/string st "world")) (v/hash mid)))
    (is (= (v/hash s) (v/hash (v/slice s 0))))
    (is (zero? (v/count (v/slice s 3 3))))
    (is (= "" (v/native (v/slice s 3 3))))))

(defn slice-blob-preserves-type-and-hash
  [_]
  (let [st (store/mem-store)
        b (v/blob st (host-bytes [1 2 3 4 5]))
        mid (v/slice b 1 4)]
    (is (= "blob" (v/type mid)))
    (is (= [2 3 4] (ubytes (v/as-bytes mid))))
    (is (= (v/hash (v/blob st (host-bytes [2 3 4]))) (v/hash mid)))
    (is (= (v/hash b) (v/hash (v/slice b 0))))))

(defn as-bytes-blob
  [_]
  (let [st (store/mem-store)
        b (v/blob st (host-bytes [1 2 3 4]))]
    (is (= [1 2 3 4] (ubytes (v/as-bytes b))))
    (is (throws-info? #(v/as-bytes b 2)))
    (is (throws-info? #(v/as-bytes (v/string st "nope"))))
    (store/await (store/s-delete st (v/hash b)))
    (try
      (v/as-bytes b)
      (is false "expected missing throw")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (is (true? (:dacite/missing (ex-data e))))))))

(def checks
  {"native-field-access" native-field-access
   "string-char-limit" string-char-limit
   "pr-str-renders-long-strings" pr-str-renders-long-strings
   "get-in-assoc-in" get-in-assoc-in
   "update-and-update-in" update-and-update-in
   "slice-shares-leaves-and-bounds" slice-shares-leaves-and-bounds
   "slice-string-preserves-type-and-hash" slice-string-preserves-type-and-hash
   "slice-blob-preserves-type-and-hash" slice-blob-preserves-type-and-hash
   "as-bytes-blob" as-bytes-blob})
