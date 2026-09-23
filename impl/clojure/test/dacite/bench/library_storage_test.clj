(ns dacite.bench.library-storage-test
  "File + LMDB size of the seed catalog: snapshot / gc-live / chunked."
  (:require [clojure.test :refer [deftest is]]
            [dacite.bench.library-storage :as storage]))

(deftest seed-catalog-storage-layouts
  (let [suite (storage/run-suite)
        file (:file suite)
        lmdb (:lmdb suite)]
    (print (storage/render suite))
    (flush)

    (is (= 36 (get-in file [:chunked :entries])))
    (is (= 36 (get-in lmdb [:chunked :entries])))

    (is (< (get-in file [:gc-live :entries])
           (get-in file [:snapshot :entries])))
    (is (< (get-in file [:chunked :entries])
           (get-in file [:gc-live :entries])))
    (is (< (get-in file [:chunked :data-file-bytes])
           (get-in file [:gc-live :data-file-bytes])))
    (is (< (get-in file [:gc-live :data-file-bytes])
           (get-in file [:snapshot :data-file-bytes])))

    (is (< (get-in lmdb [:gc-live :entries])
           (get-in lmdb [:snapshot :entries])))
    (is (= (get-in lmdb [:gc-live :entries])
           (get-in lmdb [:gc-live-fresh :entries])))
    (is (< (get-in lmdb [:chunked :entries])
           (get-in lmdb [:gc-live-fresh :entries])))
    (is (<= (get-in lmdb [:chunked :used-bytes])
            (get-in lmdb [:gc-live-fresh :used-bytes]))
        "page rounding may equalize used-bytes on a tiny catalog")
    (is (<= (get-in lmdb [:gc-live-fresh :used-bytes])
            (get-in lmdb [:snapshot :used-bytes])))

    (is (< (get-in file [:chunked :wire-bytes])
           (get-in file [:gc-live :wire-bytes])))
    (is (= (get-in lmdb [:chunked :wire-bytes])
           (get-in file [:chunked :wire-bytes])))))
