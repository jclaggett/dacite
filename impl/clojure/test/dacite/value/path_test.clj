(ns dacite.value.path-test
  "Field access and path updates — stay on the value, no dac->clj."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest native-and-as-str
  (let [st (store/mem-store)
        s (v/string-with-store st "dark")
        n (v/i64-with-store st 30)
        b (v/bool-with-store st true)]
    (is (= "dark" (v/native s)))
    (is (= "dark" (v/as-str s)))
    (is (= 30 (v/native n)))
    (is (= "30" (v/as-str n)))
    (is (= true (v/native b)))
    (is (nil? (v/native nil)))
    (is (nil? (v/as-str nil)))
    (is (= "already" (v/as-str "already")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/native (v/vector-with-store st 1 2))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/as-str (v/hash-map-via st "k" "v"))))))

(deftest string-char-limit
  (let [st (store/mem-store)
        long-s (v/string-with-store st (apply str (repeat 100 \x)))]
    (is (= (apply str (repeat 100 \x)) (v/native long-s)))
    (is (thrown? clojure.lang.ExceptionInfo (v/native long-s 10)))
    (is (thrown? clojure.lang.ExceptionInfo (v/as-str long-s 10)))
    (binding [v/*string-char-limit* 10]
      (is (thrown? clojure.lang.ExceptionInfo (v/native long-s)))
      (is (thrown? clojure.lang.ExceptionInfo (v/as-str long-s))))
    (is (= "dark" (v/native (v/string-with-store st "dark") 10)))))

(deftest pr-str-renders-long-strings
  (let [st (store/mem-store)
        short-s (v/string-with-store st "dark")
        long-s (v/string-with-store st (apply str (repeat 100 \x)))]
    (is (= "\"dark\"" (v/pr-str short-s)))
    (is (re-find #"^\"x{10}…\" \(100 chars\)$" (v/pr-str long-s 10)))
    (is (re-find #"^\"x{64}…\" \(100 chars\)$" (v/pr-str long-s)))
    (binding [v/*string-char-limit* 8]
      (is (re-find #"^\"x{8}…\" \(100 chars\)$" (v/pr-str long-s))))
    (is (= "nil" (v/pr-str nil)))
    (is (re-find #"\[1 2 3\]" (v/pr-str (v/vector-with-store st 1 2 3))))))

(deftest get-in-assoc-in
  (let [st (store/mem-store)
        cfg (v/hash-map-via st
                            "theme" "dark"
                            "timeout" 30
                            "features" (v/vector-via st "a" "b"))]
    (is (= "dark" (v/as-str (v/get-in cfg ["theme"]))))
    (is (= 30 (v/native (v/get-in cfg ["timeout"]))))
    (is (= "a" (v/as-str (v/get-in cfg ["features" 0]))))
    (is (= :missing (v/get-in cfg ["nope"] :missing)))
    (is (identical? cfg (v/get-in cfg [])))
    (let [cfg' (v/assoc-in cfg ["timeout"] 90)]
      (is (= 90 (v/native (v/get-in cfg' ["timeout"]))))
      (is (= 30 (v/native (v/get-in cfg ["timeout"])))
          "assoc-in is immutable"))
    (let [cfg' (v/assoc-in cfg ["nested" "x"] "y")]
      (is (= "y" (v/as-str (v/get-in cfg' ["nested" "x"])))))
    (is (thrown? clojure.lang.ExceptionInfo (v/assoc-in cfg [] 1)))))

(deftest update-and-update-in
  (let [st (store/mem-store)
        cfg (v/hash-map-via st
                            "timeout" 30
                            "features" (v/vector-via st "a"))]
    (is (= 31 (v/native (v/get (v/update cfg "timeout" (fn [n] (+ (v/native n) 1)))
                               "timeout"))))
    (let [cfg' (v/update cfg "features" v/conj "b")]
      (is (= 2 (v/count (v/get cfg' "features"))))
      (is (= "b" (v/as-str (v/nth (v/get cfg' "features") 1)))))
    (let [cfg' (v/update-in cfg ["features" 0] (fn [_] "z"))]
      (is (= "z" (v/as-str (v/get-in cfg' ["features" 0])))))))

(deftest subvec-shares-leaves-and-bounds
  (let [st (store/mem-store)
        vec (v/vector-with-store st 0 1 2 3 4 5 6 7 8 9)
        mid (v/subvec vec 3 7)]
    (is (= 4 (v/count mid)))
    (is (= 3 (v/native (v/nth mid 0))))
    (is (= 6 (v/native (v/nth mid 3))))
    (is (= (v/dacite-hash vec) (v/dacite-hash (v/subvec vec 0))))
    (is (zero? (v/count (v/subvec vec 4 4))))
    (let [built (v/vector-with-store st 3 4 5 6)]
      (is (= (v/dacite-hash built) (v/dacite-hash mid))
          "slice of the same elements has the same value hash"))
    (is (thrown? clojure.lang.ExceptionInfo (v/subvec vec 3 11)))
    (is (thrown? clojure.lang.ExceptionInfo (v/subvec vec -1 3)))))
