(ns dacite.peer.peer-v1-test
  "Peer protocol v1: golden frames, framing rules, message shapes.

   The fixtures are fixtures/peer-v1/cases, written by
   dev/gen_peer_fixtures.clj. Spec: docs/spec/peer-v1.md."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.conformance :as conf]
            [dacite.peer.examples :as ex]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.wire.binary :as bin]))

(deftest every-fixture-decodes-and-re-encodes
  (let [{:keys [cases problems]} (conf/run-all)]
    (is (>= cases 40) "the manifest lists the cases")
    (is (empty? problems) (pr-str problems))))

(deftest manifest-lists-every-case-directory
  (let [dirs (->> (.listFiles (java.io.File. (str (conf/fixtures-root) "/cases")))
                  (filter #(.isDirectory %))
                  (map #(.getName %))
                  sort)]
    (is (= (vec dirs) (vec (sort (get (conf/manifest) "cases")))))))

(deftest every-example-has-a-fixture
  (let [ids (set (get (conf/manifest) "cases"))]
    (doseq [{:keys [id]} ex/examples]
      (is (contains? ids id) id))
    (testing "every message type has an example"
      (is (= (set (keys msg/shapes)) (set (map :type ex/examples)))))))

(deftest framing-rules
  (is (empty? (conf/run-framing))))

(deftest frame-sizes
  (is (= 16384 frame/max-frame))
  (is (= 16378 frame/max-payload))
  (testing "a frame of exactly max-frame is accepted, one more is not"
    (let [ok (frame/encode-frame {:type :item-stream :stream 1 :flags 1
                                  :payload (byte-array frame/max-payload)})]
      (is (= 16384 (bin/byte-len ok)))
      (is (= 16378 (bin/byte-len (:payload (frame/decode-frame ok)))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (frame/encode-frame {:type :item-stream :stream 1
                                        :payload (byte-array (inc frame/max-payload))})))))
  (testing "the largest control messages fit one frame"
    (let [hs (vec (repeat msg/max-hashes (msg/hash-ref ex/h1)))
          q (msg/encode-message {:type :have? :stream 1 :value {"hashes" hs}})]
      (is (< (bin/byte-len q) frame/max-frame))
      (is (< (bin/byte-len q) 9000)))))

(deftest stream-ids-span-u32
  (let [f (msg/encode-message {:type :cancel :stream 4294967295 :value {}})]
    (is (= 4294967295 (:stream (msg/decode-message f)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (msg/encode-message {:type :cancel :stream 4294967296 :value {}}))))

(deftest stream-id-is-not-the-hash
  ;; N1: the envelope carries a requester-chosen id, so two asks for one
  ;; hash are two streams. Their frames differ only in the envelope.
  (let [v {"hash" (msg/hash-ref ex/h1) "policy" "default"}
        a (msg/encode-message {:type :get :stream 5 :value v})
        b (msg/encode-message {:type :get :stream 9 :value v})]
    (is (not= (bin/bytes->hex a) (bin/bytes->hex b)))
    (is (= (:value (msg/decode-message a)) (:value (msg/decode-message b))))
    (is (not= (:stream (msg/decode-message a)) (:stream (msg/decode-message b))))))

(deftest have-bitmap
  (is (= "a0" (bin/bytes->hex (msg/bitmap [true false true]))))
  (is (= "ff80" (bin/bytes->hex (msg/bitmap (concat (repeat 8 true) [true])))))
  (let [bits (msg/bitmap [false true false false false false false true true])]
    (is (= [false true false false false false false true true]
           (mapv #(msg/bitmap-held? bits %) (range 9))))))

(deftest items-in-a-stream-are-hash-verified
  ;; Hash checks are the store layer's job (dacite.service.reveal for now).
  ;; The framing hands it exactly the items the sender cut.
  (let [text (slurp (str (conf/fixtures-root) "/cases/item-stream-three-frames/frames.hex"))
        frames (mapv bin/hex->bytes (remove empty? (clojure.string/split-lines text)))
        {:keys [items]} (reduce (fn [{:keys [state items]} bs]
                                  (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                    {:state st :items (into items more)}))
                                {:state (frame/reader 7) :items []}
                                frames)
        decoded (bin/decode-item-stream (frame/concat-bytes items))
        st (sr/accept-items! nil decoded)]
    (is (= 3 (count frames)))
    (is (every? #(some? (store/await (store/s-get st (:hash %)))) decoded))
    (testing "a flipped byte in an entry is refused"
      (let [bad (update-in (vec decoded) [0 :hash 0] inc)]
        (is (thrown? clojure.lang.ExceptionInfo (sr/accept-items! nil bad)))))))

;; ---------------------------------------------------------------------------
;; Property: any profile value round-trips and re-encodes to the same bytes.
;; ---------------------------------------------------------------------------

(def gen-hex
  (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789abcdef") 64)))

(def gen-leaf
  (gen/one-of [(gen/return nil)
               gen/boolean
               (gen/fmap long gen/small-integer)
               gen/string-alphanumeric
               (gen/fmap msg/hash-ref gen-hex)]))

;; Blobs appear only as map values (the `have` bitmap). A vector with a
;; blob and another element does not realize in the core today:
;; (msg/encode-value [(byte-array 1) nil]) throws "cannot intern page-body
;; lit item". Reported in the M1.1 PR; not worked around here.
(def gen-blob
  (gen/fmap #(byte-array (map unchecked-byte %)) (gen/vector gen/byte 0 20)))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/fmap set (gen/vector (gen/one-of [gen/string-alphanumeric
                                                         (gen/fmap long gen/small-integer)])
                                            0 4))
                  (gen/map gen/string-alphanumeric inner {:max-elements 4})]))
   gen-leaf))

(defspec blob-fields-round-trip 50
  (prop/for-all [m (gen/map gen/string-alphanumeric gen-blob {:max-elements 3})
                 n gen/small-integer]
    (let [x (assoc m "n" (long n))
          bs (msg/encode-value x)]
      (and (msg/equal-values? x (msg/decode-value bs))
           (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value (msg/decode-value bs))))))))

(defspec any-profile-value-round-trips 150
  (prop/for-all [x gen-value]
    (let [bs (msg/encode-value x)
          back (msg/decode-value bs)]
      (and (msg/equal-values? x back)
           (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value back)))))))

(deftest outside-the-profile
  (doseq [x [1.5 (float 2.5) 3/4 \a (java.util.Date.) :kw {1 2} #{1.5}]]
    (is (thrown? clojure.lang.ExceptionInfo (msg/encode-value x)) (pr-str x))))
