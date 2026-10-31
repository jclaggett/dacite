(ns gen-peer-fixtures
  "Generate peer-v1 fixture cases under fixtures/peer-v1/cases with the
   shipped encoders (dacite.peer.msg, dacite.peer.frame, dacite.wire.binary).

   Run from impl/clojure:
     clojure -M:dev -m gen-peer-fixtures

   Each case directory holds:
     message.edn   what the frames mean (:expect says how a port checks them)
     frames.hex    one frame per line, lowercase hex

   Do not hand-edit the hex. Change an example or this file and regenerate."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [dacite.peer.examples :as ex]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(defn- root []
  (io/file (.getParentFile (io/file (bin/fixture-root))) "peer-v1"))

(defn- edn-str [x]
  (binding [*print-namespace-maps* false]
    (with-out-str (pp/pprint x))))

(def written (atom []))

(defn write-case!
  [id meta frames]
  (let [dir (io/file (root) "cases" id)]
    (.mkdirs dir)
    (spit (io/file dir "message.edn") (edn-str meta))
    (spit (io/file dir "frames.hex")
          (str (str/join "\n" (map bin/bytes->hex frames)) "\n"))
    (swap! written conj id)
    (println "ok" id "frames" (count frames)
             "bytes" (reduce + (map bin/byte-len frames)))))

;; --- Control messages -------------------------------------------------------

(defn control-cases! []
  (doseq [{:keys [id title type stream value]} ex/examples]
    (write-case! id
                 {:title title :expect :message :type type :stream stream
                  :value (msg/->edn value)}
                 [(msg/encode-message {:type type :stream stream :value value})])))

;; --- Messages a sender would not write but a receiver must read -------------

(defn- lenient-frame
  "A control frame without the sender-side checks of `msg/encode-message`."
  [type stream value]
  (frame/encode-frame {:type type :stream stream :flags frame/flag-fin
                       :payload (msg/encode-value value)}))

(defn lenient-cases! []
  (let [h (msg/hash-ref ex/h1)
        hello (assoc (:value (first ex/examples)) "kind" "relay")
        get-v {"hash" h "policy" "fancy"}]
    (write-case! "error-unknown-code"
                 {:title "A code v1 does not define: decodes, and reads as internal"
                  :expect :message :type :error :stream 5
                  :value {"code" "teapot" "fatal" false}
                  :reads {:error-code "internal"}}
                 [(lenient-frame :error 5 {"code" "teapot" "fatal" false})])
    (write-case! "hello-unknown-kind"
                 {:title "A kind v1 does not define: decodes, and reads as browser"
                  :expect :message :type :hello :stream 0
                  :value (msg/->edn hello)
                  :reads {:peer-kind "browser"}}
                 [(lenient-frame :hello 0 hello)])
    (write-case! "get-unknown-policy"
                 {:title "An unknown policy decodes; the responder answers unsupported-policy"
                  :expect :message :type :get :stream 5
                  :value (msg/->edn get-v)
                  :answer {:type :error :stream 5
                           :value {"code" "unsupported-policy"
                                   "message" "unknown policy"
                                   "fatal" false}}}
                 [(lenient-frame :get 5 get-v)])))

;; --- Item streams -----------------------------------------------------------

(defn- reveal-pages
  "Wire-v1 node items of a default reveal of a vector of `n` short strings."
  [n]
  (let [st (store/mem-store)
        val (apply v/vector st
                   (map #(v/string st (str "line " % ": the quick brown fox"))
                        (range n)))
        order (reveal/page-order st (v/hash val))]
    (mapv (fn [h] {:hash h :bytes (sr/encode-page st {:hash h})}) order)))

(defn- item-meta [title stream partial? pages frames]
  {:title title
   :expect :item-stream
   :stream stream
   :partial? partial?
   :frames (count frames)
   :items (mapv (fn [{:keys [hash bytes]}]
                  {:hash (store/hash->hex hash) :bytes (bin/byte-len bytes)})
                pages)})

(defn stream-cases! []
  (let [none (frame/chunk-items 5 [])
        small-pages (reveal-pages 20)
        small (frame/chunk-items 5 (map :bytes small-pages))
        big-pages (reveal-pages 1200)
        big (frame/chunk-items 7 (map :bytes big-pages))
        part-pages (vec (take 3 (reveal-pages 600)))
        part (frame/chunk-items 9 (map :bytes part-pages) {:partial? true})]
    (write-case! "item-stream-empty"
                 (item-meta "A reveal with nothing to send: one empty FIN frame" 5 false [] none)
                 none)
    (write-case! "item-stream-one-frame"
                 (item-meta "Five pages of a small vector fit in one frame" 5 false small-pages small)
                 small)
    (write-case! "item-stream-three-frames"
                 (item-meta "A larger vector: three frames, an item straddles a boundary"
                            7 false big-pages big)
                 big)
    (write-case! "item-stream-partial"
                 (item-meta "The reveal paused after three pages: FIN with PARTIAL, ask again to resume"
                            9 true part-pages part)
                 part)))

;; --- Rejected input ---------------------------------------------------------

(defn- hello []
  (:value (first ex/examples)))

(defn- ctl-frame
  [type stream flags payload]
  (frame/encode-frame {:type type :stream stream :flags flags :payload payload}))

(defn- swap-map-order
  "Same literal, map pairs reversed: valid shape, not canonical."
  [bs]
  (let [form (bin/decode-lit-bytes bs)]
    (bin/encode-lit-bytes (update form :body (comp vec reverse)))))

(defn- item-header
  [enc plen]
  (let [h (byte-array 37)]
    (aset-byte h 0 (unchecked-byte enc))
    (aset-byte h 33 (unchecked-byte (bit-and 0xff (bit-shift-right plen 24))))
    (aset-byte h 34 (unchecked-byte (bit-and 0xff (bit-shift-right plen 16))))
    (aset-byte h 35 (unchecked-byte (bit-and 0xff (bit-shift-right plen 8))))
    (aset-byte h 36 (unchecked-byte (bit-and 0xff plen)))
    h))

(defn- on-stream
  "`bs` with its stream id field set to `n`, bypassing the stream rule."
  [bs n]
  (doseq [[i sh] [[2 24] [3 16] [4 8] [5 0]]]
    (aset-byte bs i (unchecked-byte (bit-and 0xff (bit-shift-right n sh)))))
  bs)

(defn- ctl-on
  "A control frame for `type` on stream `n`, even when the rule forbids it."
  [type n value]
  (on-stream (lenient-frame type (if (= :session (frame/stream-rule type)) 0 1) value) n))

(defn reject-case!
  [id title reason frames & {:as extra}]
  (write-case! id (merge {:title title :expect :reject :reason reason} extra) frames))

(defn reject-cases! []
  (let [fin frame/flag-fin
        hello-lit (msg/encode-value (hello))
        err-lit (msg/encode-value {"code" "unknown-hash" "fatal" false})]
    (reject-case! "reject-frame-short" "Five bytes: shorter than the envelope"
                  :frame-too-short [(byte-array [1 1 0 0 0])])
    (reject-case! "reject-frame-too-large" "One byte over 16 KiB"
                  :frame-too-large
                  [(let [b (byte-array (inc frame/max-frame))]
                     (aset-byte b 0 (byte 5))
                     (aset-byte b 1 (byte 1))
                     b)]
                  :type :item-stream)
    (reject-case! "reject-type-zero" "Type id 0 is reserved"
                  :unknown-type [(let [f (ctl-frame :hello 0 fin hello-lit)]
                                   (aset-byte f 0 (byte 0))
                                   f)])
    (reject-case! "reject-reserved-flags" "A reserved flag bit is set"
                  :bad-flags [(ctl-frame :hello 0 (bit-or fin 0x80) hello-lit)])
    (reject-case! "reject-partial-without-fin" "PARTIAL on a frame with no FIN"
                  :partial-without-fin [(ctl-frame :item-stream 5 frame/flag-partial (byte-array 0))])
    (reject-case! "reject-control-without-fin" "A control message must carry FIN"
                  :not-fin [(ctl-frame :hello 0 0 hello-lit)])
    (reject-case! "reject-partial-on-control" "PARTIAL is only for item streams"
                  :bad-flags [(ctl-frame :error 5 (bit-or fin frame/flag-partial) err-lit)])
    (reject-case! "reject-item-stream-as-control" "An item-stream frame read as a control message"
                  :wrong-type [(ctl-frame :item-stream 5 fin (byte-array 0))]
                  :as-control true)
    (reject-case! "reject-literal-trailing-byte" "A byte after the literal"
                  :bad-literal
                  [(ctl-frame :error 5 fin (frame/concat-bytes [err-lit (byte-array [0])]))]
                  :type :error)
    (reject-case! "reject-not-canonical" "Map pairs in the wrong order"
                  :not-canonical
                  [(ctl-frame :error 5 fin (swap-map-order err-lit))]
                  :type :error)
    (reject-case! "reject-float-field" "An f64 is outside the v1 profile"
                  :profile
                  [(ctl-frame :hello 0 fin
                              (bin/encode-lit-bytes
                               {:type "map"
                                :body [[{:type "string" :body "versions"}
                                        {:type "f64" :body 1.5}]]}))]
                  :type :hello)
    (reject-case! "reject-hello-missing-field" "hello without versions"
                  :invalid-message
                  [(ctl-frame :hello 0 fin (msg/encode-value (dissoc (hello) "versions")))]
                  :type :hello)
    (reject-case! "reject-have-short-bits" "count 9 needs two bytes of bits"
                  :invalid-message
                  [(ctl-frame :have 1 fin (msg/encode-value
                                           {"count" 9 "bits" (byte-array [0xff])}))]
                  :type :have)
    (reject-case! "reject-have-spare-bits" "Spare bits of the last byte must be zero"
                  :invalid-message
                  [(ctl-frame :have 1 fin (msg/encode-value
                                           {"count" 3 "bits" (byte-array [(unchecked-byte 0xa1)])}))]
                  :type :have)
    (reject-case! "reject-request-on-stream-0" "get on the session stream"
                  :wrong-stream
                  [(ctl-on :get 0 {"hash" (msg/hash-ref ex/h1) "policy" "default"})]
                  :type :get)
    (reject-case! "reject-have-query-on-stream-0" "have? on the session stream"
                  :wrong-stream
                  [(ctl-on :have? 0 {"hashes" [(msg/hash-ref ex/h1)]})]
                  :type :have?)
    (reject-case! "reject-ack-on-stream-n" "ack on a request's stream"
                  :wrong-stream
                  [(ctl-on :ack 7 {"hashes" [(msg/hash-ref ex/h1)]})]
                  :type :ack)
    (reject-case! "reject-hello-on-stream-n" "hello on a request's stream"
                  :wrong-stream
                  [(ctl-on :hello 5 (hello))]
                  :type :hello)
    (reject-case! "reject-cancel-on-stream-0" "cancel needs the request's stream"
                  :wrong-stream
                  [(ctl-on :cancel 0 {})]
                  :type :cancel)
    (reject-case! "reject-item-stream-on-stream-0" "an item-stream frame on stream 0"
                  :wrong-stream
                  [(on-stream (ctl-frame :item-stream 5 fin (byte-array 0)) 0)]
                  :type :item-stream :stream 5)
    (reject-case! "reject-string-too-long" "A 257-byte reason"
                  :invalid-message
                  [(lenient-frame :cancel 5 {"reason" (apply str (repeat 257 "x"))})]
                  :type :cancel)
    (reject-case! "reject-interest-zero-tau" "tau-ms 0 never decays"
                  :invalid-message
                  [(lenient-frame :get 5 {"hash" (msg/hash-ref ex/h1) "policy" "default"
                                          "interest" {"peak" 1000 "tau-ms" 0 "floor" 0}})]
                  :type :get)
    (reject-case! "reject-interest-zero-peak" "peak 0 is no interest"
                  :invalid-message
                  [(lenient-frame :get 5 {"hash" (msg/hash-ref ex/h1) "policy" "default"
                                          "interest" {"peak" 0 "tau-ms" 1000 "floor" 0}})]
                  :type :get)
    (reject-case! "reject-hello-zero-streams" "max-streams 0 accepts no request"
                  :invalid-message
                  [(lenient-frame :hello 0 (assoc-in (hello) ["limits" "max-streams"] 0))]
                  :type :hello)
    (reject-case! "reject-item-literal" "An item with enc 1: v1 streams carry nodes only"
                  :bad-item
                  [(ctl-frame :item-stream 5 0 (item-header 1 0))]
                  :type :item-stream :stream 5)
    (reject-case! "reject-item-unknown-enc" "An item whose enc is 7"
                  :bad-item
                  [(ctl-frame :item-stream 5 0 (item-header 7 0))]
                  :type :item-stream :stream 5)
    (reject-case! "reject-item-too-large" "An item header that claims 2 MiB"
                  :item-too-large
                  [(ctl-frame :item-stream 5 0 (item-header 0 (* 2 1024 1024)))]
                  :type :item-stream :stream 5)
    (reject-case! "reject-item-truncated" "FIN in the middle of an item"
                  :truncated-item
                  [(ctl-frame :item-stream 5 fin (item-header 0 100))]
                  :type :item-stream :stream 5)
    (reject-case! "reject-frame-after-fin" "A frame after FIN on the same stream"
                  :after-fin
                  [(ctl-frame :item-stream 5 fin (byte-array 0))
                   (ctl-frame :item-stream 5 fin (byte-array 0))]
                  :type :item-stream :stream 5)
    (reject-case! "reject-wrong-stream" "A frame for stream 6 on the reader of stream 5"
                  :wrong-stream
                  [(ctl-frame :item-stream 6 fin (byte-array 0))]
                  :type :item-stream :stream 5))
  ;; Not an error: answer `unsupported`, keep the session.
  (write-case! "unknown-type"
               {:title "Type 0x42 is not defined: decode, answer unsupported, go on"
                :expect :unknown :type-id 0x42 :stream 3}
               [(ctl-frame 0x42 3 frame/flag-fin (byte-array [1 2 3]))]))

(defn -main [& _]
  (let [dir (io/file (root) "cases")]
    (when (.isDirectory dir)
      (doseq [d (.listFiles dir)] (when (.isDirectory d)
                                    (doseq [f (.listFiles d)] (.delete f))
                                    (.delete d)))))
  (control-cases!)
  (lenient-cases!)
  (stream-cases!)
  (reject-cases!)
  (spit (io/file (root) "manifest.json")
        (str (json/generate-string {:format "dacite-peer-v1"
                                    :max_frame frame/max-frame
                                    :cases (vec (sort @written))}
                                   {:pretty true})
             "\n"))
  (println "manifest cases" (count @written)))
