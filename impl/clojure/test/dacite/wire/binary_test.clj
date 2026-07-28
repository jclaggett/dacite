(ns dacite.wire.binary-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.wire.binary :as bin]
            [dacite.store :as store]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.types :as types]
            [dacite.store.pack :as pack]))

(deftest fixture-literal-i64-42-matches-and-round-trips
  (let [fx (bin/load-fixture "chunk-literal-i64-42")
        decoded (bin/decode-chunk (:message-bytes fx))
        item (first (:items decoded))
        h (store/hex->hash (:hash-hex fx))]
    (is (= 1 (:version decoded)))
    (is (= :chunk (:msg-type decoded)))
    (is (= 1024 (:budget decoded)))
    (is (= 1 (count (:items decoded))))
    (is (= :literal (:enc item)))
    (is (= h (:hash item)))
    (is (= "i64" (:type (:literal item))))
    (is (= 42 (:body (:literal item))))
    ;; re-encode must match golden bytes
    (let [re (bin/encode-chunk
              {:budget (:budget decoded)
               :items [{:enc :literal
                        :hash (:hash item)
                        :literal (:literal item)}]})]
      (is (= (bin/bytes->hex (:message-bytes fx))
             (bin/bytes->hex re))))
    ;; apply installs value
    (let [st (store/mem-store)
          r (bin/apply-chunk-message! st decoded)]
      (is (= 1 (:applied r)))
      (is (store/s-has? st h))
      (is (= ["i64" 42] (store/s-get st h))))))

(deftest encode-decode-bool-null-empty-vector
  (let [st (store/mem-store)
        cases [["null" (scalar/null-with-store st) {:type "null" :body nil}]
               ["bool" (scalar/bool-with-store st true) {:type "bool" :body true}]
               ["empty-vec" (coll/vector-with-store st)
                {:type "vector" :body []}]]]
    (doseq [[lab v lit] cases]
      (let [h (types/dacite-hash v)
            msg {:budget 1024
                 :items [{:enc :literal :hash h :literal lit}]}
            bs (bin/encode-chunk msg)
            dec (bin/decode-chunk bs)
            st2 (store/mem-store)]
        (is (= 1 (count (:items dec))) lab)
        (is (= lit (:literal (first (:items dec)))) lab)
        (bin/apply-chunk-message! st2 dec)
        (is (store/s-has? st2 h) lab)
        ;; canonical re-encode
        (is (= (bin/bytes->hex bs)
               (bin/bytes->hex (bin/encode-chunk dec))) lab)))))

(deftest node-scalar-round-trip
  (let [st (store/mem-store)
        v (scalar/i64-with-store st 7)
        h (types/dacite-hash v)
        entry (store/s-get st h)
        msg {:budget 0
             :items [{:enc :node :hash h :entry entry}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)
        st2 (store/mem-store)]
    (is (= :node (:enc (first (:items dec)))))
    (is (= entry (:entry (first (:items dec)))))
    (bin/apply-chunk-message! st2 dec)
    (is (= entry (store/s-get st2 h)))
    (is (= (bin/bytes->hex bs)
           (bin/bytes->hex (bin/encode-chunk dec))))))

(deftest nested-vector-literal
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        ;; build nested literal form matching pack style
        lit {:type "vector"
             :body [{:type "i64" :body 1}
                    {:type "i64" :body 2}
                    {:type "i64" :body 3}]}
        msg {:budget 1024
             :items [{:enc :literal :hash h :literal lit}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)
        st2 (store/mem-store)
        _ (bin/apply-chunk-message! st2 dec)]
    (is (store/s-has? st2 h))
    (is (= h (pack/materialize-literal! (store/mem-store)
                                        (:type lit) (:body lit))))
    (is (= (bin/bytes->hex bs)
           (bin/bytes->hex (bin/encode-chunk dec))))))

(deftest collection-node-header-round-trip
  (let [st (store/mem-store)
        v (coll/vector-with-store st 10)
        h (types/dacite-hash v)
        entry (store/s-get st h)
        msg {:budget 0
             :items [{:enc :node :hash h :entry entry}]}
        bs (bin/encode-chunk msg)
        dec (bin/decode-chunk bs)]
    (is (= "vector" (first (:entry (first (:items dec))))))
    (is (= entry (:entry (first (:items dec)))))
    (is (= (bin/bytes->hex bs)
           (bin/bytes->hex (bin/encode-chunk dec))))))

(deftest all-fixtures-decode-and-reencode
  (doseq [id (bin/list-fixture-ids)]
    (let [fx (bin/load-fixture id)
          dec (bin/decode-chunk (:message-bytes fx))
          ;; re-encode from decoded structure
          re (bin/encode-chunk
              {:budget (:budget dec)
               :items (mapv (fn [it]
                              (if (= :literal (:enc it))
                                {:enc :literal
                                 :hash (:hash it)
                                 :literal (:literal it)}
                                {:enc :node
                                 :hash (:hash it)
                                 :entry (:entry it)}))
                            (:items dec))})]
      (is (= (:message-hex fx) (bin/bytes->hex re))
          (str "canonical re-encode " id))
      (let [st (store/mem-store)]
        (bin/apply-chunk-message! st dec)
        (when (:hash-hex fx)
          (is (store/s-has? st (store/hex->hash (:hash-hex fx)))
              (str "hash present " id)))))))

(deftest reject-bad-magic-and-version
  (is (thrown-with-msg? Exception #"bad magic"
                        (bin/decode-chunk (byte-array [0 1 2 3 1 1 0 0 0 0 0 0 0 0 0]))))
  (let [good (:message-bytes (bin/load-fixture "chunk-literal-i64-42"))
        bad (aclone good)]
    (aset-byte bad 4 (unchecked-byte 99)) ; version
    (is (thrown-with-msg? Exception #"unsupported wire version"
                          (bin/decode-chunk bad)))))
