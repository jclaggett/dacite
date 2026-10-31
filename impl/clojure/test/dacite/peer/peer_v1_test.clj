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
    (is (>= cases 53) "the manifest lists the cases")
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

;; ---------------------------------------------------------------------------
;; Review of PR #7: F1-F4 and the cheap should-fix items
;; ---------------------------------------------------------------------------

(defn- thrown-reason
  [f]
  (try (f) :accepted
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest f1-unknown-error-code-is-read-as-internal
  (let [wire (msg/encode-value {"code" "teapot" "fatal" false})
        fr (frame/encode-frame {:type :error :stream 5 :flags 1 :payload wire})
        got (msg/decode-message fr)]
    (is (= "teapot" (get-in got [:value "code"])))
    (is (= "internal" (msg/error-code (:value got))))
    (is (= "unknown-hash" (msg/error-code {"code" "unknown-hash"}))))
  (testing "a sender still writes only codes v1 defines"
    (is (= :invalid-message
           (thrown-reason #(msg/encode-message {:type :error :stream 5
                                                :value {"code" "teapot" "fatal" false}}))))))

(deftest f2-literal-items-are-refused-in-item-streams
  (let [hdr (fn [enc] (let [b (byte-array 37)] (aset-byte b 0 (byte enc)) b))
        feed (fn [enc]
               (frame/feed (frame/reader 5)
                           (frame/decode-frame
                            (frame/encode-frame {:type :item-stream :stream 5 :flags 0
                                                 :payload (hdr enc)}))))]
    (is (= :bad-item (thrown-reason #(feed 1))))
    (is (= :bad-item (thrown-reason #(feed 7))))
    (is (= 1 (count (second (feed 0)))))))

(deftest f3-message-types-and-streams
  (testing "session messages are on stream 0, requests on a request id"
    (doseq [[t ok bad v] [[:hello 0 5 (:value (first ex/examples))]
                          [:ack 0 7 {"hashes" [(msg/hash-ref ex/h1)]}]
                          [:release 0 7 {"hashes" [(msg/hash-ref ex/h1)]}]
                          [:have? 1 0 {"hashes" [(msg/hash-ref ex/h1)]}]
                          [:get 1 0 {"hash" (msg/hash-ref ex/h1) "policy" "default"}]
                          [:cancel 5 0 {}]
                          [:have 1 0 {"count" 1 "bits" (byte-array [0x80])}]]]
      (is (= :accepted (thrown-reason #(msg/encode-message {:type t :stream ok :value v}))) (name t))
      (is (= :wrong-stream
             (thrown-reason #(msg/encode-message {:type t :stream bad :value v}))) (name t))))
  (testing "error may be on either"
    (doseq [sid [0 3]]
      (is (= :accepted (thrown-reason #(msg/encode-message
                                        {:type :error :stream sid
                                         :value {"code" "internal" "fatal" false}}))))))
  (testing "item-stream is never on stream 0, in either direction"
    (is (= :wrong-stream (thrown-reason #(frame/chunk-items 0 []))))
    (is (= :wrong-stream (thrown-reason
                          #(frame/decode-frame (byte-array [5 1 0 0 0 0])))))))

(deftest f3-stream-table-parity-and-duplicates
  (let [off (frame/stream-table :offerer 2)
        ans (frame/stream-table :answerer 2)]
    (testing "own ids: offerer odd, answerer even, rising by two"
      (let [[off1 a] (frame/open-local off)
            [_ b] (frame/open-local off1)
            [ans1 c] (frame/open-local ans)
            [_ d] (frame/open-local ans1)]
        (is (= [1 3 2 4] [a b c d]))))
    (testing "the answerer accepts odd ids from the offerer, not even"
      (is (frame/live? (frame/open-remote ans 1) 1))
      (is (= :bad-stream-parity (thrown-reason #(frame/open-remote ans 2))))
      (is (= :bad-stream-parity (thrown-reason #(frame/open-remote off 3)))))
    (testing "id 0 and ids over u32 are not requests"
      (is (= :wrong-stream (thrown-reason #(frame/open-remote ans 0))))
      (is (= :wrong-stream (thrown-reason #(frame/open-remote ans 4294967296)))))
    (testing "an id is used once, even after it closes"
      (let [t (-> ans (frame/open-remote 1) (frame/close-stream 1))]
        (is (not (frame/live? t 1)))
        (is (= :duplicate-stream (thrown-reason #(frame/open-remote t 1))))
        (is (frame/live? (frame/open-remote t 3) 3))))
    (testing "open requests are bounded by max-streams; closing frees a slot"
      (let [t (-> ans (frame/open-remote 1) (frame/open-remote 3))]
        (is (= :too-many-streams (thrown-reason #(frame/open-remote t 5))))
        (is (frame/live? (-> t (frame/close-stream 1) (frame/open-remote 5)) 5))))
    (testing "the same hash asked twice is two streams (N1)"
      (let [t (-> ans (frame/open-remote 1) (frame/open-remote 3))]
        (is (= #{1 3} (:open t)))))))

(deftest f4-unknown-policy-is-answered-not-refused
  (let [wire (msg/encode-value {"hash" (msg/hash-ref ex/h1) "policy" "fancy"})
        fr (frame/encode-frame {:type :get :stream 5 :flags 1 :payload wire})
        got (msg/decode-message fr)
        ans (msg/answer-error got)]
    (is (= :get (:type got)))
    (is (= {:type :error :stream 5
            :value {"code" "unsupported-policy" "message" "unknown policy" "fatal" false}}
           ans))
    (testing "the answer is a valid message on the request's stream"
      (is (= "unsupported-policy"
             (get-in (msg/decode-message (msg/encode-message ans)) [:value "code"])))))
  (testing "a known policy and a plain request need no answer"
    (is (nil? (msg/answer-error {:type :get :stream 5
                                 :value {"hash" (msg/hash-ref ex/h1) "policy" "default"}})))
    (is (nil? (msg/answer-error {:type :hello :stream 0 :value {}}))))
  (testing "other things a responder will not serve"
    (is (= "unsupported" (get-in (msg/answer-error {:type 66 :unknown? true :stream 3})
                                 [:value "code"])))
    (is (= "unsupported"
           (get-in (msg/answer-error {:type :have? :stream 1 :value {}} {:caps #{"reveal"}})
                   [:value "code"])))
    (is (= "unsupported"
           (get-in (msg/answer-error {:type :get :stream 1 :value {"policy" "default"}}
                                     {:caps #{"have"}})
                   [:value "code"]))))
  (testing "an unknown hello kind is read as browser"
    (is (= "browser" (msg/peer-kind {"kind" "relay"})))
    (is (= "node" (msg/peer-kind {"kind" "node"})))))

(deftest s1-max-hashes-follows-max-frame
  (is (= 256 (msg/max-hashes-for 16384)))
  (is (= 20 (msg/max-hashes-for 1024)))
  (doseq [mf [1024 2048 4096 16384]
          :let [n (msg/max-hashes-for mf)
                hs (vec (repeat n (msg/hash-ref ex/h1)))
                reason (apply str (repeat msg/max-string-bytes "r"))]]
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :have? :stream 1 :max-frame-bytes mf
                                                          :value {"hashes" hs}}))) mf)
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :ack :stream 0 :max-frame-bytes mf
                                                          :value {"hashes" hs}}))) mf)
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :release :stream 0 :max-frame-bytes mf
                                                          :value {"hashes" hs "reason" reason}}))) mf)))

(deftest s2-strings-and-sets-are-bounded
  (let [long (apply str (repeat 257 "x"))
        ok (apply str (repeat 256 "x"))
        wide (apply str (repeat 86 "é"))] ; 172 bytes
    (doseq [[t v] [[:cancel {"reason" long}]
                   [:error {"code" "internal" "fatal" false "message" long}]
                   [:release {"hashes" [(msg/hash-ref ex/h1)] "reason" long}]]]
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message {:type t :stream (if (= t :release) 0 5) :value v})))))
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :cancel :stream 5 :value {"reason" ok}}))))
    (testing "the limit is in bytes, not characters"
      (is (= :accepted (thrown-reason #(msg/encode-message {:type :cancel :stream 5 :value {"reason" wide}}))))
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message {:type :cancel :stream 5
                                                  :value {"reason" (str wide wide)}})))))
    (let [hello (:value (first ex/examples))]
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message
                              {:type :hello :stream 0
                               :value (assoc hello "caps" (set (map (fn [i] (str "cap-" i)) (range 33))))})))))))

(deftest s3-interest-bounds
  (let [v (fn [i] {"hash" (msg/hash-ref ex/h1) "policy" "default" "interest" i})
        enc (fn [i] (thrown-reason #(msg/encode-message {:type :get :stream 5 :value (v i)})))]
    (is (= :accepted (enc {"peak" 1000 "tau-ms" 1 "floor" 0})))
    (is (= :accepted (enc {"peak" 1 "tau-ms" 2000 "floor" 5000})) "floor above peak pauses at once; allowed")
    (is (= :invalid-message (enc {"peak" 0 "tau-ms" 2000 "floor" 0})))
    (is (= :invalid-message (enc {"peak" 1000 "tau-ms" 0 "floor" 0})))))

(deftest nit-hello-zero-limits
  (let [h (:value (first ex/examples))]
    (doseq [path [["limits" "max-streams"] ["limits" "max-item"] ["rates" "req-rate"]
                  ["rates" "req-burst"] ["rates" "send-max"] ["rates" "recv-max"]]]
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message {:type :hello :stream 0
                                                  :value (assoc-in h path 0)})))
          (str path)))))

;; S4: the spec's own hex, written by hand from the layout rules, is the
;; same bytes as the fixture frames.
(deftest spec-hex-blocks-match-fixtures
  (let [spec (slurp (str (.getParentFile (java.io.File. (conf/fixtures-root))) "/../docs/spec/peer-v1.md"))
        blocks (re-seq #"(?s)```hex ([a-z0-9?-]+)\n(.*?)```" spec)]
    (is (>= (count blocks) 2))
    (doseq [[_ id body] blocks
            :let [hex-of (fn [s] (apply str (re-seq #"[0-9a-f]" (clojure.string/replace s #"(?m);.*$" ""))))
                  fixture (clojure.string/trim (slurp (str (conf/fixtures-root) "/cases/" id "/frames.hex")))]]
      (is (= fixture (hex-of body)) id))))
