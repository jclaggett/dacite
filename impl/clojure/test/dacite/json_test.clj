(ns dacite.json-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.json :as dj]
            [dacite.store :as store]))

(deftest null-roundtrip
  (store/with-store [_s {}]
    (let [dac (dj/json->dacite "null")]
      (is (= "null" (dj/dacite->json dac))))))

(deftest bool-roundtrip
  (store/with-store [_s {}]
    (is (= "true" (dj/dacite->json (dj/json->dacite "true"))))
    (is (= "false" (dj/dacite->json (dj/json->dacite "false"))))))

(deftest number-roundtrip
  (store/with-store [_s {}]
    (is (= "0" (dj/dacite->json (dj/json->dacite "0"))))
    (is (= "1" (dj/dacite->json (dj/json->dacite "1"))))
    (is (= "-1" (dj/dacite->json (dj/json->dacite "-1"))))
    (is (= "3.14" (dj/dacite->json (dj/json->dacite "3.14"))))))

(deftest string-roundtrip
  (store/with-store [_s {}]
    (is (= "\"hello\"" (dj/dacite->json (dj/json->dacite "\"hello\""))))))

(deftest array-roundtrip
  (store/with-store [_s {}]
    (is (= "[]" (dj/dacite->json (dj/json->dacite "[]"))))
    (is (= "[1,2,3]" (dj/dacite->json (dj/json->dacite "[1,2,3]"))))))

(deftest object-roundtrip
  (store/with-store [_s {}]
    (let [json-in "{\"a\":1,\"b\":true}"
          dac (dj/json->dacite json-in)
          json-out (dj/dacite->json dac)]
      (is (= (dj/json->dacite json-in)
             (dj/json->dacite json-out))))))

(deftest nested-roundtrip
  (store/with-store [_s {}]
    (let [json-in "{\"x\":[1,{\"y\":null},true]}"
          dac (dj/json->dacite json-in)
          json-out (dj/dacite->json dac)]
      (is (= (dj/json->dacite json-in)
             (dj/json->dacite json-out))))))
