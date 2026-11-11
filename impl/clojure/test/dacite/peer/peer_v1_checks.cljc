(ns dacite.peer.peer-v1-checks
  "Peer protocol v1 on the JVM and on nbb: golden frames, framing rules,
   message shapes and bounds (docs/spec/peer-v1.md). The JVM runs them from
   `dacite.peer.peer-v1-test`, one deftest each under its old name; nbb runs
   them from `dacite.peer.peer-cljs-test` (`npm run peer:test`).

   The two properties (defspecs before) use test.check on the JVM and run
   fewer cases from a fixed seed on nbb (`dacite.value.seeded-gen`, no
   shrinking, smaller strings and depth; see `nbb-cases`).

   `is`, `testing` and `throws-info?` are `dacite.store.check`. `env` has
   `:case-dirs` (the fixture case directory names), `:read-spec` (the text
   of docs/spec/peer-v1.md) and `:read-text` (a file's text by path)."
  (:require #?(:clj [clojure.test.check :as tc])
            #?(:clj [clojure.test.check.generators :as gen]
               :cljs [dacite.value.seeded-gen :as gen])
            #?(:clj [clojure.test.check.properties :as prop])
            [clojure.string :as str]
            [dacite.host :as host]
            [dacite.peer.conformance :as conf]
            [dacite.peer.examples :as ex]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.store.check :refer [is testing throws-info?]]
            [dacite.wire.binary :as bin]))

(defn- zero-bytes
  "`n` zero bytes on this host."
  [n]
  #?(:clj (byte-array n) :cljs (js/Uint8Array. n)))

(defn- host-bytes
  "Host bytes for the ints `xs` (0..255)."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js xs))))

(defn- set-byte!
  "Set byte `i` of `b` to the low 8 bits of `v`."
  [b i v]
  #?(:clj (aset-byte b i (unchecked-byte v))
     :cljs (aset b i (bit-and 0xff v))))

(defn- now-ms []
  #?(:clj (/ (System/nanoTime) 1.0e6) :cljs (.now js/performance)))

(defn- property
  "The property `f` holds for `n` draws from `gens`: test.check on the JVM,
   a fixed seed on nbb. One assertion, as a defspec reports one."
  [n gens f]
  (let [r #?(:clj (tc/quick-check n (prop/for-all* gens f))
             :cljs (gen/quick-check n (apply gen/tuple gens) #(apply f %) 21))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(defn- thrown-reason
  [f]
  (try (f) :accepted
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (:reason (ex-data e)))))

(defn every-fixture-decodes-and-re-encodes
  [env]
  (let [{:keys [cases problems]} (conf/run-all)]
    (is (>= cases 50) "the manifest lists the cases")
    (is (empty? problems) (pr-str problems))))

(defn manifest-lists-every-case-directory
  [env]
  (let [dirs (sort ((:case-dirs env)))]
    (is (= (vec dirs) (vec (sort (get (conf/manifest) "cases")))))))

(defn every-example-has-a-fixture
  [env]
  (let [ids (set (get (conf/manifest) "cases"))]
    (doseq [{:keys [id]} ex/examples]
      (is (contains? ids id) id))
    (testing "every message type has an example"
      (is (= (set (keys msg/shapes)) (set (map :type ex/examples)))))))

(defn framing-rules
  [env]
  (is (empty? (conf/run-framing))))

(defn frame-sizes
  [env]
  (is (= 16384 frame/max-frame))
  (is (= 16378 frame/max-payload))
  (testing "a frame of exactly max-frame is accepted, one more is not"
    (let [ok (frame/encode-frame {:type :item-stream :stream 1 :flags 1
                                  :payload (zero-bytes frame/max-payload)})]
      (is (= 16384 (bin/byte-len ok)))
      (is (= 16378 (bin/byte-len (:payload (frame/decode-frame ok)))))
      (is (throws-info? #(frame/encode-frame {:type :item-stream :stream 1
                                        :payload (zero-bytes (inc frame/max-payload))})))))
  (testing "the largest control messages fit one frame"
    (let [hs (vec (repeat msg/max-hashes (msg/hash-ref ex/h1)))
          q (msg/encode-message {:type :ack :stream 0 :value {"hashes" hs}})]
      (is (< (bin/byte-len q) frame/max-frame))
      (is (< (bin/byte-len q) 9000)))))

(defn stream-ids-span-u32
  [env]
  (let [f (msg/encode-message {:type :cancel :stream 4294967295 :value {}})]
    (is (= 4294967295 (:stream (msg/decode-message f)))))
  (is (throws-info? #(msg/encode-message {:type :cancel :stream 4294967296 :value {}}))))

(defn stream-id-is-not-the-hash
  [env]
  ;; N1: the envelope carries a requester-chosen id, so two asks for one
  ;; hash are two streams. Their frames differ only in the envelope.
  (let [v {"hash" (msg/hash-ref ex/h1) "policy" "default" "recv-max" 750000}
        a (msg/encode-message {:type :get :stream 5 :value v})
        b (msg/encode-message {:type :get :stream 9 :value v})]
    (is (not= (bin/bytes->hex a) (bin/bytes->hex b)))
    (is (= (:value (msg/decode-message a)) (:value (msg/decode-message b))))
    (is (not= (:stream (msg/decode-message a)) (:stream (msg/decode-message b))))))

;; ---------------------------------------------------------------------------
;; Property: any profile value round-trips and re-encodes to the same bytes.
;; ---------------------------------------------------------------------------

(def ^:private gen-hex
  (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789abcdef") 64)))

(def ^:private gen-leaf
  (gen/one-of [(gen/return nil)
               gen/boolean
               (gen/fmap long gen/small-integer)
               gen/string-alphanumeric
               (gen/fmap msg/hash-ref gen-hex)]))

;; Blobs appear only as map values (no v1 message holds one now). A vector with a
;; blob and another element does not realize in the core today:
;; (msg/encode-value [(byte-array 1) nil]) throws "cannot intern page-body
;; lit item". Reported in the M1.1 PR; not worked around here.
(def ^:private gen-blob
  (gen/fmap host-bytes (gen/vector gen/byte 0 20)))

(def ^:private gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/fmap set (gen/vector (gen/one-of [gen/string-alphanumeric
                                                         (gen/fmap long gen/small-integer)])
                                            0 4))
                  (gen/map gen/string-alphanumeric inner {:max-elements 4})]))
   gen-leaf))

(def nbb-cases
  "Case counts on nbb, where `msg/encode-value` of these small values takes
   about 10 ms each, so the JVM's 150 and 50 cases would take about 2.5 s
   and 1 s there. The JVM keeps its counts."
  {:any-profile 60 :blob-fields 25})

(defn blob-fields-round-trip
  [_env]
  (property #?(:clj 50 :cljs (:blob-fields nbb-cases))
            [(gen/map gen/string-alphanumeric gen-blob {:max-elements 3})
             gen/small-integer]
            (fn [m n]
              (let [x (assoc m "n" (long n))
                    bs (msg/encode-value x)]
                (and (msg/equal-values? x (msg/decode-value bs))
                     (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value (msg/decode-value bs)))))))))

(defn any-profile-value-round-trips
  [_env]
  (property #?(:clj 150 :cljs (:any-profile nbb-cases)) [gen-value]
            (fn [x]
              (let [bs (msg/encode-value x)
                    back (msg/decode-value bs)]
                (and (msg/equal-values? x back)
                     (= (bin/bytes->hex bs) (bin/bytes->hex (msg/encode-value back))))))))

(defn outside-the-profile
  [env]
  ;; a ratio, a char, a date, and a float are JVM values
  (doseq [x (into [1.5 :kw {1 2} #{1.5}]
                  #?(:clj [(float 2.5) 3/4 \a (java.util.Date.)] :cljs []))]
    (is (throws-info? #(msg/encode-value x)) (pr-str x))))

(defn f1-unknown-error-code-is-read-as-internal
  [env]
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

(defn f2-item-encodings-in-item-streams
  [env]
  ;; Decision Q5: an item is a node (enc 0) or a literal (enc 1). A literal
  ;; is at most one frame payload; any other enc is refused.
  (let [hdr (fn [enc plen]
              (let [b (zero-bytes 37)]
                (set-byte! b 0 enc)
                (set-byte! b 36 (bit-and 0xff plen))
                (set-byte! b 35 (bit-and 0xff (bit-shift-right plen 8)))
                (set-byte! b 34 (bit-and 0xff (bit-shift-right plen 16)))
                b))
        feed (fn [b]
               (frame/feed (frame/reader 5)
                           (frame/decode-frame
                            (frame/encode-frame {:type :item-stream :stream 5 :flags 0
                                                 :payload b}))))]
    (is (= :bad-item (thrown-reason #(feed (hdr 7 0)))))
    (is (= 1 (count (second (feed (hdr 0 0))))) "a node item")
    (is (= 1 (count (second (feed (hdr 1 0))))) "a literal item")
    (testing "a literal item over one frame payload is refused from its header"
      (is (= :literal-too-large
             (thrown-reason #(feed (hdr 1 (- (inc frame/max-payload) 37))))))
      (is (= :accepted (thrown-reason #(feed (hdr 1 (- frame/max-payload 37)))))
          "...and exactly one payload is allowed"))
    (testing "a hashed entry may be larger than a frame; it spans frames"
      (is (= :accepted (thrown-reason #(feed (hdr 0 (- (inc frame/max-payload) 37)))))))))

(defn f3-message-types-and-streams
  [env]
  (testing "session messages are on stream 0, requests on a request id"
    (doseq [[t ok bad v] [[:hello 0 5 (:value (first ex/examples))]
                          [:ack 0 7 {"hashes" [(msg/hash-ref ex/h1)]}]
                          [:get 1 0 {"hash" (msg/hash-ref ex/h1) "policy" "default" "recv-max" 750000}]
                          [:cancel 5 0 {}]]]
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
                          #(frame/decode-frame (host-bytes [3 1 0 0 0 0])))))))

(defn f3-stream-table-parity-and-duplicates
  [env]
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

(defn f4-unknown-policy-is-answered-not-refused
  [env]
  (let [wire (msg/encode-value {"hash" (msg/hash-ref ex/h1) "policy" "fancy" "recv-max" 750000})
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
                                 :value {"hash" (msg/hash-ref ex/h1) "policy" "default" "recv-max" 750000}})))
    (is (nil? (msg/answer-error {:type :hello :stream 0 :value {}}))))
  (testing "other things a responder will not serve"
    (is (= "unsupported" (get-in (msg/answer-error {:type 66 :unknown? true :stream 3})
                                 [:value "code"])))
    (is (= "unsupported"
           (get-in (msg/answer-error {:type :get :stream 1 :value {"policy" "default" "recv-max" 750000}}
                                     {:caps #{}})
                   [:value "code"]))))
  (testing "an unknown hello kind is read as browser"
    (is (= "browser" (msg/peer-kind {"kind" "relay"})))
    (is (= "node" (msg/peer-kind {"kind" "node"})))))

(defn s1-max-hashes-follows-max-frame
  [env]
  (is (= 256 (msg/max-hashes-for 16384)))
  (is (= 30 (msg/max-hashes-for 1024)))
  (doseq [mf [1024 2048 4096 16384]
          :let [n (msg/max-hashes-for mf)
                hs (vec (repeat n (msg/hash-ref ex/h1)))]]
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :ack :stream 0 :max-frame-bytes mf
                                                          :value {"hashes" hs}}))) mf)))

(defn s2-strings-and-sets-are-bounded
  [env]
  (let [long (apply str (repeat 257 "x"))
        ok (apply str (repeat 256 "x"))
        wide (apply str (repeat 86 "é"))] ; 172 bytes
    (testing "a receiver rejects an over-long string (fixture reject-string-too-long)"
      (doseq [[t v] [[:cancel {"reason" long}]
                     [:error {"code" "internal" "fatal" false "message" long}]]
              :let [fr (frame/encode-frame {:type t :stream 5 :flags 1
                                            :payload (msg/encode-value v)})]]
        (is (= :invalid-message (thrown-reason #(msg/decode-message fr))) (name t))))
    (testing "a sender cuts free text instead of failing (S1)"
      (doseq [[t v k] [[:cancel {"reason" long} "reason"]
                       [:error {"code" "internal" "fatal" false "message" long} "message"]
                       [:hello (assoc (:value (first ex/examples)) "agent" long) "agent"]]
              :let [bs (msg/encode-message {:type t :stream (case t :hello 0 5) :value v})
                    got (get-in (msg/decode-message bs) [:value k])]]
        (is (= ok got) (name t))))
    (testing "the cut is at a character boundary, in bytes"
      (is (= (apply str (repeat 128 "é")) (msg/cut-text (apply str (repeat 200 "é")))))
      (is (= (str (apply str (repeat 255 "x"))) (msg/cut-text (str (apply str (repeat 255 "x")) "é"))))
      (is (= wide (msg/cut-text wide)))
      (let [smile (apply str (repeat 70 "😀"))] ; 4 bytes each: 64 fit
        (is (= (apply str (repeat 64 "😀")) (msg/cut-text smile)))))
    (testing "names a receiver parses are not cut: a sender bug is refused"
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message
                              {:type :get :stream 5
                               :value {"hash" (msg/hash-ref ex/h1) "policy" long "recv-max" 750000}})))))
    (is (= :accepted (thrown-reason #(msg/encode-message {:type :cancel :stream 5 :value {"reason" ok}}))))
    (testing "the limit is in bytes, not characters"
      (is (= :accepted (thrown-reason #(msg/encode-message {:type :cancel :stream 5 :value {"reason" wide}}))))
      (is (= 256 (count (host/utf8-bytes (get-in (msg/decode-message
                                                       (msg/encode-message {:type :cancel :stream 5
                                                                            :value {"reason" (str wide wide)}}))
                                                      [:value "reason"]))))
          "344 bytes in, 172+... cut to a whole number of characters: 256"))
    (let [hello (:value (first ex/examples))]
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message
                              {:type :hello :stream 0
                               :value (assoc hello "caps" (set (map (fn [i] (str "cap-" i)) (range 33))))})))))))

(defn s5-integers-are-within-2-to-the-53
  [env]
  ;; nbb holds a double: exact only to 2^53 - 1. Both hosts keep the range.
  (is (= 9007199254740991 msg/max-int))
  (doseq [n [msg/max-int (- msg/max-int) 0 1]]
    (is (= n (get (msg/decode-value (msg/encode-value {"a" n})) "a"))))
  (doseq [n (into [(inc msg/max-int) (- (inc msg/max-int))]
                  #?(:clj [Long/MAX_VALUE Long/MIN_VALUE] :cljs []))]
    (is (= :profile (thrown-reason #(msg/encode-value {"a" n}))) (str n)))
  (testing "a receiver rejects one a foreign sender wrote"
    (let [big (bin/encode-lit-bytes {:type "map"
                                     :body [[{:type "string" :body "a"}
                                             {:type "i64" :body (inc msg/max-int)}]]})]
      (is (= :profile (thrown-reason #(msg/decode-value big)))))))

(defn s9-version-negotiation
  [env]
  (let [hello (:value (first ex/examples))
        mine (assoc hello "versions" #{1 2} "caps" #{"reveal" "x"}
                    "limits" {"max-frame" 16384 "max-item" 1048576 "max-streams" 32})
        theirs (assoc hello "versions" #{2 3} "caps" #{"reveal"}
                      "limits" {"max-frame" 8192 "max-item" 262144 "max-streams" 8})
        {:keys [ok error]} (msg/negotiate mine theirs)]
    (is (nil? error))
    (is (= 2 (:version ok)) "the highest in both")
    (is (= #{"reveal"} (:caps ok)) "the intersection")
    (is (= [8192 262144] [(:max-frame ok) (:max-item ok)]) "the smaller of the two")
    (is (= [8 32] [(:may-open ok) (:will-accept ok)]) "max-streams is per direction")
    (is (= (- 8192 6) (:frame-literal-cap ok)))
    (testing "no common version: a fatal unsupported-version on stream 0"
      (let [{:keys [ok error]} (msg/negotiate (assoc mine "versions" #{1}) (assoc theirs "versions" #{2 3}))]
        (is (nil? ok))
        (is (= {:type :error :stream 0
                :value {"code" "unsupported-version" "message" "no version in common" "fatal" true}}
               error))
        (testing "and it is a valid message to send"
          (is (= "unsupported-version"
                 (get-in (msg/decode-message (msg/encode-message error)) [:value "code"]))))))))

(defn s8-reassembly-copies-an-item-once-and-handles-many-small-ones
  [env]
  (let [item (fn [n] (let [b (zero-bytes n)
                           plen (- n 37)]
                       (set-byte! b 36 (bit-and 0xff plen))
                       (set-byte! b 35 (bit-and 0xff (bit-shift-right plen 8)))
                       (set-byte! b 34 (bit-and 0xff (bit-shift-right plen 16)))
                       b))
        read (fn [in]
               (let [frames (frame/chunk-items 5 in)
                     r (reduce (fn [{:keys [state items]} bs]
                                 (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                   {:state st :items (into items more)}))
                               {:state (frame/reader 5 (* 2 1048576)) :items []}
                               frames)]
                 (assoc r :frames (count frames))))
        big (read [(item 1000000)])
        many (read (repeat 20000 (item 40)))]
    (testing "one 1 MB item over 62 frames"
      (is (= 62 (:frames big)))
      (is (= [1000000] (mapv bin/byte-len (:items big))))
      (is (nil? (:need (:state big)))))
    (testing "20 000 small items, a few hundred per frame"
      (is (= 20000 (count (:items many))))
      (is (every? #(= 40 (bin/byte-len %)) (:items many))))))

(defn s3-interest-bounds
  [env]
  (let [v (fn [i] {"hash" (msg/hash-ref ex/h1) "policy" "default" "recv-max" 750000 "interest" i})
        enc (fn [i] (thrown-reason #(msg/encode-message {:type :get :stream 5 :value (v i)})))]
    (is (= :accepted (enc {"peak" 1000 "tau-ms" 1 "floor" 0})))
    (is (= :accepted (enc {"peak" 1 "tau-ms" 2000 "floor" 5000})) "floor above peak pauses at once; allowed")
    (is (= :invalid-message (enc {"peak" 0 "tau-ms" 2000 "floor" 0})))
    (is (= :invalid-message (enc {"peak" 1000 "tau-ms" 0 "floor" 0})))))

(defn nit-hello-zero-limits
  [env]
  (let [h (:value (first ex/examples))]
    (doseq [path [["limits" "max-streams"] ["limits" "max-item"] ["rates" "req-rate"]
                  ["rates" "req-burst"] ["rates" "send-max"] ["rates" "recv-max"]]]
      (is (= :invalid-message
             (thrown-reason #(msg/encode-message {:type :hello :stream 0
                                                  :value (assoc-in h path 0)})))
          (str path)))))

(defn frame-parser-does-not-trust-a-length-past-max-item
  [env]
  ;; N12: the length in the item header is a claim. Past the reader's
  ;; max-item it is refused from the header, before any further payload
  ;; is buffered.
  (let [hdr (fn [plen]
              (let [b (zero-bytes 37)]
                (set-byte! b 0 0)
                (set-byte! b 33 (bit-and 0xff (unsigned-bit-shift-right plen 24)))
                (set-byte! b 34 (bit-and 0xff (unsigned-bit-shift-right plen 16)))
                (set-byte! b 35 (bit-and 0xff (unsigned-bit-shift-right plen 8)))
                (set-byte! b 36 (bit-and 0xff plen))
                b))
        feed (fn [max-item plen]
               (frame/feed (frame/reader 1 max-item)
                           (frame/decode-frame
                            (frame/encode-frame {:type :item-stream :stream 1
                                                 :payload (hdr plen)}))))]
    (is (= :item-too-large (thrown-reason #(feed 1000 5000))))
    (is (= :accepted (thrown-reason #(feed 1000 10)))
        "a length inside the cap is buffered, not refused")))

(defn frame-parser-is-linear
  [env]
  ;; N12: the reader used to concatenate the whole buffer on every frame.
  ;; 400 KB in 64-byte frames is about six thousand recopyings of a growing
  ;; buffer (on the order of a gigabyte). Linear joins the item once, at
  ;; the end, and stays well under a second.
  (let [payload-len 400000
        hdr (zero-bytes 37)
        _ (set-byte! hdr 0 0)
        _ (set-byte! hdr 33 (bit-and 0xff (unsigned-bit-shift-right payload-len 24)))
        _ (set-byte! hdr 34 (bit-and 0xff (unsigned-bit-shift-right payload-len 16)))
        _ (set-byte! hdr 35 (bit-and 0xff (unsigned-bit-shift-right payload-len 8)))
        _ (set-byte! hdr 36 (bit-and 0xff payload-len))
        item (frame/concat-bytes [hdr (zero-bytes payload-len)])
        room 64
        n (bin/byte-len item)
        frames (mapv (fn [from]
                       (frame/decode-frame
                        (frame/encode-frame
                         {:type :item-stream :stream 1
                          :payload (frame/slice item from (min (+ from room) n))})))
                     (range 0 n room))
        t0 (now-ms)
        [_ items] (reduce (fn [[st _] fr] (frame/feed st fr))
                          [(frame/reader 1) []]
                          frames)
        ms (- (now-ms) t0)]
    (is (= 1 (count items)))
    (is (= n (bin/byte-len (first items))))
    (is (< ms 1500) (str "took " ms " ms"))))

(defn spec-hex-blocks-match-fixtures
  [env]
  (let [spec ((:read-spec env))
        blocks (re-seq #"(?s)```hex ([a-z0-9?-]+)\n(.*?)```" spec)]
    (is (>= (count blocks) 2))
    (doseq [[_ id body] blocks
            :let [hex-of (fn [s] (apply str (re-seq #"[0-9a-f]" (str/replace s #"(?m);.*$" ""))))
                  fixture (str/trim ((:read-text env) (str (conf/fixtures-root) "/cases/" id "/frames.hex")))]]
      (is (= fixture (hex-of body)) id))))

(def checks
  {"every-fixture-decodes-and-re-encodes" every-fixture-decodes-and-re-encodes
   "manifest-lists-every-case-directory" manifest-lists-every-case-directory
   "every-example-has-a-fixture" every-example-has-a-fixture
   "framing-rules" framing-rules
   "frame-sizes" frame-sizes
   "stream-ids-span-u32" stream-ids-span-u32
   "stream-id-is-not-the-hash" stream-id-is-not-the-hash
   "blob-fields-round-trip" blob-fields-round-trip
   "any-profile-value-round-trips" any-profile-value-round-trips
   "outside-the-profile" outside-the-profile
   "f1-unknown-error-code-is-read-as-internal" f1-unknown-error-code-is-read-as-internal
   "f2-item-encodings-in-item-streams" f2-item-encodings-in-item-streams
   "f3-message-types-and-streams" f3-message-types-and-streams
   "f3-stream-table-parity-and-duplicates" f3-stream-table-parity-and-duplicates
   "f4-unknown-policy-is-answered-not-refused" f4-unknown-policy-is-answered-not-refused
   "s1-max-hashes-follows-max-frame" s1-max-hashes-follows-max-frame
   "s2-strings-and-sets-are-bounded" s2-strings-and-sets-are-bounded
   "s5-integers-are-within-2-to-the-53" s5-integers-are-within-2-to-the-53
   "s9-version-negotiation" s9-version-negotiation
   "s8-reassembly-copies-an-item-once-and-handles-many-small-ones" s8-reassembly-copies-an-item-once-and-handles-many-small-ones
   "s3-interest-bounds" s3-interest-bounds
   "nit-hello-zero-limits" nit-hello-zero-limits
   "frame-parser-does-not-trust-a-length-past-max-item" frame-parser-does-not-trust-a-length-past-max-item
   "frame-parser-is-linear" frame-parser-is-linear
   "spec-hex-blocks-match-fixtures" spec-hex-blocks-match-fixtures})
