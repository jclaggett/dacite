(ns dacite.value.path-test
  "Field access and path updates — stay on the value, no dac->clj."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest native-field-access
  (let [st (store/mem-store)
        s (v/string st "dark")
        n (v/i64 st 30)
        b (v/bool st true)]
    (is (= "dark" (v/native s)))
    (is (= 30 (v/native n)))
    (is (= true (v/native b)))
    (is (nil? (v/native nil)))
    (is (= "already" (v/native "already")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/native (v/vector st 1 2))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/native (v/map st "k" "v"))))))

(deftest string-char-limit
  (let [st (store/mem-store)
        long-s (v/string st (apply str (repeat 100 \x)))]
    (is (= (apply str (repeat 100 \x)) (v/native long-s)))
    (is (thrown? clojure.lang.ExceptionInfo (v/native long-s 10)))
    (is (thrown? clojure.lang.ExceptionInfo (v/native long-s 10)))
    (binding [v/*string-char-limit* 10]
      (is (thrown? clojure.lang.ExceptionInfo (v/native long-s)))
      (is (thrown? clojure.lang.ExceptionInfo (v/native long-s))))
    (is (= "dark" (v/native (v/string st "dark") 10)))))

(deftest pr-str-renders-long-strings
  (let [st (store/mem-store)
        short-s (v/string st "dark")
        long-s (v/string st (apply str (repeat 100 \x)))]
    (is (= "\"dark\"" (v/pr-str short-s)))
    (is (re-find #"^\"x{10}…\" \(100 chars\)$" (v/pr-str long-s 10)))
    (is (re-find #"^\"x{64}…\" \(100 chars\)$" (v/pr-str long-s)))
    (binding [v/*string-char-limit* 8]
      (is (re-find #"^\"x{8}…\" \(100 chars\)$" (v/pr-str long-s))))
    (is (= "nil" (v/pr-str nil)))
    (is (re-find #"\[1 2 3\]" (v/pr-str (v/vector st 1 2 3))))))

(deftest get-in-assoc-in
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
    (is (thrown? clojure.lang.ExceptionInfo (v/assoc-in cfg [] 1)))))

(deftest update-and-update-in
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

(deftest subvec-shares-leaves-and-bounds
  (let [st (store/mem-store)
        vec (v/vector st 0 1 2 3 4 5 6 7 8 9)
        mid (v/subvec vec 3 7)]
    (is (= 4 (v/count mid)))
    (is (= 3 (v/native (v/nth mid 0))))
    (is (= 6 (v/native (v/nth mid 3))))
    (is (= (v/hash vec) (v/hash (v/subvec vec 0))))
    (is (zero? (v/count (v/subvec vec 4 4))))
    (let [built (v/vector st 3 4 5 6)]
      (is (= (v/hash built) (v/hash mid))
          "slice of the same elements has the same value hash"))
    (is (thrown? clojure.lang.ExceptionInfo (v/subvec vec 3 11)))
    (is (thrown? clojure.lang.ExceptionInfo (v/subvec vec -1 3)))))

(deftest as-bytes-blob
  (let [st (store/mem-store)
        b (v/blob st (byte-array [1 2 3 4]))]
    (is (= [1 2 3 4] (map #(bit-and % 0xFF) (v/as-bytes b))))
    (is (thrown? clojure.lang.ExceptionInfo (v/as-bytes b 2)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/as-bytes (v/string st "nope"))))
    (store/s-delete st (v/hash b))
    (try
      (v/as-bytes b)
      (is false "expected missing throw")
      (catch clojure.lang.ExceptionInfo e
        (is (true? (:dacite/missing (ex-data e))))))))
