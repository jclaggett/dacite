(ns dacite.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.cache :as cache]
            [dacite.cache-checks :as checks]
            [dacite.hash :as hash]))

(deftest portable-checks
  (is (empty? (checks/problems))))

(deftest hit-and-miss-are-not-confused
  (let [c (cache/lru 2)]
    (cache/put! c :k nil)
    (is (nil? (cache/lookup c :k :nf)) "a cached nil is a hit")
    (is (= :nf (cache/lookup c :other :nf)))))

(deftest threads-share-a-cache-safely
  (testing "8 threads over a small cache: the bound holds and every value is right"
    (let [c (cache/lru 100)
          errors (atom 0)
          run (fn [seed]
                (future
                  (dotimes [i 20000]
                    (let [k (mod (+ (* 7919 i) seed) 700)
                          v (cache/memo c k (fn [] (* k k)))]
                      (when-not (= v (* k k)) (swap! errors inc))
                      (when (> (cache/size c) 100) (swap! errors inc))))))]
      (run! deref (mapv run (range 8)))
      (is (zero? @errors))
      (is (<= (cache/size c) 100)))))

(deftest ref->state-is-correct-across-threads
  (let [refs (mapv (fn [i] (hash/state->ref (hash/fuse-str (str "t" i)))) (range 40))
        want (mapv #(hash/fuse-bytes (hash/longs->bytes %)) refs)
        c (cache/lru 8)
        bad (atom 0)]
    (run! deref
          (mapv (fn [s]
                  (future
                    (dotimes [i 400]
                      (let [j (mod (+ (* 13 i) s) 40)]
                        (when-not (= (nth want j) (hash/ref->state-in c (nth refs j)))
                          (swap! bad inc))))))
                (range 8)))
    (is (zero? @bad))
    (is (<= (cache/size c) 8))))
