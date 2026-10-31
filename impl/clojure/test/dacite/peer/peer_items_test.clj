(ns dacite.peer.peer-items-test
  "Peer protocol v1, items: the literal rule, packing, and verification.
   Spec: docs/spec/peer-v1.md §3.2 and §3.3."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.peer.conformance :as conf]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.wire.binary :as bin]))

(defn- thrown-reason
  [f]
  (try (f) :accepted
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- fixture-items
  "Decoded items of an item-stream fixture, read through the frame reader."
  [id]
  (let [text (slurp (str (conf/fixtures-root) "/cases/" id "/frames.hex"))
        frames (mapv bin/hex->bytes (remove str/blank? (str/split-lines text)))
        meta (read-string (slurp (str (conf/fixtures-root) "/cases/" id "/message.edn")))
        {:keys [items]} (reduce (fn [{:keys [state items]} bs]
                                  (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                    {:state st :items (into items more)}))
                                {:state (frame/reader (:stream meta)) :items []}
                                frames)]
    {:frames frames :bytes items :decoded (bin/decode-item-stream (frame/concat-bytes items))}))

(defn- lines
  [st n]
  (apply v/vector st (map #(v/string st (str "line " % ": the quick brown fox")) (range n))))

(defn- node-total
  [st h]
  (reduce + (map #(bin/byte-len (sr/encode-page st {:hash %})) (reveal/page-order st h))))

(defn- decode-all [bss] (bin/decode-item-stream (frame/concat-bytes bss)))

;; ---------------------------------------------------------------------------
;; The fixtures verify
;; ---------------------------------------------------------------------------

(deftest every-item-stream-fixture-verifies
  (doseq [id ["item-stream-one-frame" "item-stream-mixed" "item-stream-partial"
              "item-stream-literal-at-cap" "item-stream-literal-over-cap"]
          :let [{:keys [decoded]} (fixture-items id)
                st (:store (items/finish! (items/accept-items! nil decoded)))]]
    (is (pos? (count decoded)) id)
    (is (every? #(some? (store/await (store/s-get st (:hash %)))) decoded) id)))

;; ---------------------------------------------------------------------------
;; Serialization: the literal rule
;; ---------------------------------------------------------------------------

(deftest small-values-are-one-literal
  (let [st (store/mem-store)
        h (v/hash (v/map st "title" "hello" "tags" (v/vector st "a" "b")))
        {:keys [items encs]} (items/encode-value st h)]
    (is (= [:literal] encs))
    (is (< (bin/byte-len (first items)) (node-total st h)))
    (testing "the receiver gets the same value hash from the literal alone"
      (let [rst (:store (items/accept-items! nil (decode-all items)))]
        (is (some? (store/await (store/s-get rst h))))))))

(deftest the-shorter-form-wins-and-is-never-worse
  (let [st (store/mem-store)
        shared (v/string st (apply str (repeat 300 "xyz-")))
        values [(v/string st "hello")
                (lines st 20)
                ;; one long value under 40 keys: node entries store it once
                (apply v/map st (mapcat (fn [i] [(str "key" i) shared]) (range 40)))
                (v/string st (apply str (repeat 3000 "a")))]]
    (doseq [val values
            :let [h (v/hash val)
                  {:keys [items encs]} (items/encode-value st h)
                  total (reduce + (map bin/byte-len items))
                  rst (:store (items/finish! (items/accept-items! nil (decode-all items))))]]
      (is (<= total (node-total st h)) (str (pr-str (frequencies encs)) " " total))
      (is (some? (store/await (store/s-get rst h)))))))

(deftest a-literal-never-exceeds-one-frame-payload
  ;; Read from the fixtures; nothing is rebuilt.
  (let [cap (frame/max-literal-item)
        {:keys [decoded]} (fixture-items "item-stream-literal-at-cap")
        over (fixture-items "item-stream-literal-over-cap")]
    (is (= 16378 cap))
    (testing "just under: one literal item, exactly one frame payload"
      (is (= [:literal] (mapv :enc decoded)))
      (is (= cap (bin/byte-len (bin/encode-item (first decoded)))))
      (is (= 1 (count (:frames (fixture-items "item-stream-literal-at-cap"))))))
    (testing "one letter more: the whole value is not a literal; its header is a node"
      (is (= :node (:enc (first (:decoded over)))))
      (is (> (count (:decoded over)) 1))
      (is (every? #(<= (bin/byte-len (bin/encode-item %)) cap) (:decoded over))
          "no item needs more than one frame"))))

(deftest the-cap-applies-at-its-boundary
  ;; The same rule as the fixtures, at a smaller frame so it is quick to build:
  ;; the longest string whose literal item fits 1024 - 6 bytes, and one more.
  (let [st (store/mem-store)
        mf 1024
        cap (frame/max-literal-item mf)
        text (fn [n] (let [r (java.util.Random. 7)]
                       (apply str (repeatedly n #(char (+ 97 (.nextInt r 26)))))))
        hash-of (fn [n] (v/hash (v/string st (text n))))
        fits (fn [n] (items/literal-bytes st (hash-of n) cap))
        n (loop [lo 100 hi 1500]
            (if (<= (- hi lo) 1) lo
                (let [mid (quot (+ lo hi) 2)]
                  (if (fits mid) (recur mid hi) (recur lo mid)))))
        under (items/encode-value st (hash-of n) {:max-frame-bytes mf})
        over (items/encode-value st (hash-of (inc n)) {:max-frame-bytes mf})]
    (is (= cap (bin/byte-len (fits n))) "the longest that fits is exactly the cap")
    (is (nil? (fits (inc n))))
    (is (= [:literal] (:encs under)))
    (is (= :node (first (:encs over))) "over the cap the header is a node item, and it is first")
    (is (every? #(<= (bin/byte-len %) cap)
                (map second (filter #(= :literal (first %)) (map vector (:encs over) (:items over))))))
    (testing "and both verify"
      (is (some? (items/finish! (items/accept-items! nil (decode-all (:items under))))))
      (is (some? (items/finish! (items/accept-items! nil (decode-all (:items over)))))))))

(deftest the-cap-follows-the-negotiated-frame-size
  (let [st (store/mem-store)
        h (v/hash (lines st 60)) ; a literal of about 2 KB
        big (items/encode-value st h)
        small (items/encode-value st h {:max-frame-bytes 1024})]
    (is (= [:literal] (:encs big)))
    (is (= :node (first (:encs small))) "the whole value does not fit 1024 - 6")
    (is (every? #(or (not= :literal (:enc %))
                     (<= (bin/byte-len (bin/encode-item %)) (- 1024 6)))
                (decode-all (:items small))))))

;; ---------------------------------------------------------------------------
;; Frames carry whole items
;; ---------------------------------------------------------------------------

(defn- item-of-size
  [n]
  (let [b (byte-array n)]
    (aset-byte b 0 (byte 0))
    (let [plen (- n 37)]
      (aset-byte b 36 (unchecked-byte (bit-and 0xff plen)))
      (aset-byte b 35 (unchecked-byte (bit-and 0xff (bit-shift-right plen 8))))
      (aset-byte b 34 (unchecked-byte (bit-and 0xff (bit-shift-right plen 16)))))
    b))

(defn- payload-sizes
  [frames]
  (mapv #(bin/byte-len (:payload (frame/decode-frame %))) frames))

(deftest frames-keep-items-whole-when-they-can
  (testing "three 10 000-byte items: one per frame, none split"
    (is (= [10000 10000 10000]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [10000 10000 10000]))))))
  (testing "small items share a frame"
    (is (= [15000]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [5000 5000 5000]))))))
  (testing "an item that is bigger than a payload starts a frame and is cut; the next item follows its tail"
    (is (= [1000 16378 16378 (+ 7244 1000)]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [1000 40000 1000]))))))
  (testing "whatever the cut, the receiver gets the same items back"
    (let [in (mapv item-of-size [1000 40000 1000 16378 20000])
          frames (frame/chunk-items 5 in)
          out (:items (reduce (fn [{:keys [state items]} bs]
                                (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                  {:state st :items (into items more)}))
                              {:state (frame/reader 5) :items []}
                              frames))]
      (is (= (mapv bin/bytes->hex in) (mapv bin/bytes->hex out))))))

;; ---------------------------------------------------------------------------
;; Verification (S3.3): each item from its own bytes; nothing waits
;; ---------------------------------------------------------------------------

(defn- node-items
  "Decoded node items for `hashes`, in the order given."
  [st hashes]
  (mapv (fn [h] (first (bin/decode-item-stream (sr/encode-page st {:hash h}))))
        hashes))

(defn- reveal-nodes
  [st h]
  (let [order (reveal/page-order st h)
        structural? #(str/starts-with? (first (store/require-node st %)) "ft/")]
    {:order order
     :pages (vec (filter structural? order))
     :others (vec (remove structural? order))}))

(deftest items-verify-in-the-order-the-sender-writes-them
  ;; The mixed fixture: node items and literals, header first.
  (let [{:keys [decoded]} (fixture-items "item-stream-mixed")
        rx (items/receiver)
        checks (mapv #(items/accept-item! rx %) decoded)]
    (is (= :node (:enc (first decoded))) "the header comes first")
    (is (contains? (first checks) :pending) "and waits for its root")
    (is (some #(contains? % :body-measure) checks) "a page checked against its contents")
    (is (some #(contains? % :literal) checks))
    (is (empty? (items/unchecked rx)) "the header was checked when its root arrived")))

(deftest a-header-is-accepted-first-and-checked-when-its-root-arrives
  ;; Q2: header-first. The header cannot be checked alone (its hash, count and
  ;; size follow from its root), so it is stored at once, not held back, and
  ;; checked the moment the root is stored.
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        all (node-items st (reveal/page-order st h))
        [header root & more] all
        rx (items/receiver)]
    (is (= #{:pending} (items/accept-item! rx header)))
    (is (some? (store/await (store/s-get (:store rx) h))) "stored on arrival")
    (is (= [h] (items/unchecked rx)))
    (is (= #{:hash} (items/accept-item! rx root)) "the root arrives: the header is checked")
    (is (empty? (items/unchecked rx)))
    (doseq [it more] (items/accept-item! rx it))
    (is (= rx (items/finish! rx)))))

(deftest a-forged-header-count-or-size-is-refused-when-the-root-arrives
  ;; Repro of the review (bare-leaf-header-forged-count) and the same for a
  ;; page root. count and size-bytes are in no hash: recomputation is the only check.
  (doseq [[label make] [["a bare-leaf root" #(v/vector % (v/string % "only"))]
                        ["a page root" #(lines % 300)]]
          [k n] [[:count 1000000] [:size-bytes 77]]]
    (let [st (store/mem-store)
          h (v/hash (make st))
          [header root & more] (node-items st (reveal/page-order st h))
          forged (assoc-in header [:entry 1 k] n)
          rx (items/receiver)]
      (is (= #{:pending} (items/accept-item! rx forged)) (str label " " k ": accepted on arrival"))
      (is (= :bad-entry (thrown-reason #(items/accept-item! rx root))) (str label " " k))
      (is (nil? (store/await (store/s-get (:store rx) h))) "the forged header is removed")
      (is (not-any? #(= % h) (items/unchecked rx))
          "(a bare leaf's own header may still wait for its root)"))))

(deftest a-header-whose-hash-does-not-fit-its-root-is-refused
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        [header root] (node-items st (reveal/page-order st h))
        other (v/hash (lines st 301))
        [oheader] (node-items st (reveal/page-order st other))
        ;; the header of `other`, pointing at this root, under the hash of `other`
        forged (assoc-in oheader [:entry 1 :root] (get-in header [:entry 1 :root]))
        rx (items/receiver)]
    (is (= #{:pending} (items/accept-item! rx forged)))
    (is (= :bad-hash (thrown-reason #(items/accept-item! rx root))))
    (is (nil? (store/await (store/s-get (:store rx) other))))))

(deftest every-header-waiting-on-a-root-is-removed-when-a-check-fails
  ;; Two headers wait on one root. When the root arrives and a check fails,
  ;; neither may stay stored unchecked (review M4, two-headers-one-root).
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        [header root & more] (node-items st (reveal/page-order st h))
        fake1 [1 2 3 4]
        fake2 [9 8 7 6]
        rx (items/receiver)]
    (is (= #{:pending} (items/accept-item! rx (assoc header :hash fake1))))
    (is (= #{:pending} (items/accept-item! rx (assoc header :hash fake2))))
    (is (= 2 (count (items/unchecked rx))))
    (is (= :bad-hash (thrown-reason #(items/accept-item! rx root))))
    (is (empty? (items/unchecked rx)) "nothing is tracked")
    (is (every? #(nil? (store/await (store/s-get (:store rx) %))) [fake1 fake2])
        "and nothing is left stored")
    (testing "one honest and one forged header on the same root: both go"
      (let [rx (items/receiver)]
        (items/accept-item! rx header)
        (items/accept-item! rx (assoc header :hash fake1))
        (is (= :bad-hash (thrown-reason #(items/accept-item! rx root))))
        (is (empty? (items/unchecked rx)))
        (is (nil? (store/await (store/s-get (:store rx) fake1))))))))

(deftest a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives
  ;; A vector of one string: the vector's root is the string's own hash, and
  ;; that string has a header of its own: header first all the way down.
  (let [st (store/mem-store)
        h (v/hash (v/vector st (v/string st "x")))
        all (node-items st (reveal/page-order st h))
        rx (items/receiver)]
    (is (= #{:pending} (items/accept-item! rx (first all))) "the vector header waits")
    (doseq [it (rest all)] (items/accept-item! rx it))
    (is (empty? (items/unchecked rx)))
    (is (some? (store/await (store/s-get (:store rx) h))))))

(deftest a-stream-that-ends-early-leaves-no-unchecked-header
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        [header] (node-items st (reveal/page-order st h))
        rx (items/receiver)]
    (items/accept-item! rx header)
    (testing "PARTIAL / cancel: the receiver drops what it could not check"
      (items/discard-unchecked! rx)
      (is (nil? (store/await (store/s-get (:store rx) h))))
      (is (empty? (items/unchecked rx))))
    (testing "FIN without PARTIAL: a header whose root never came is refused"
      (items/accept-item! rx header)
      (is (= :bad-entry (thrown-reason #(items/finish! rx))))
      (is (nil? (store/await (store/s-get (:store rx) h)))))))

(deftest pages-verify-in-any-order
  ;; A parent never waits for a child, and a child needs no parent.
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        {:keys [pages]} (reveal-nodes st h)
        rx (items/receiver)]
    (doseq [it (reverse (node-items st pages))]
      (items/accept-item! rx it))
    (is (every? #(some? (store/await (store/s-get (:store rx) %))) pages))))

(deftest a-run-of-refs-is-a-ref
  ;; Repro of the review (run-of-ref-has-ref): refs written as a run or repeat.
  (do
    (is (not (lit/names-child-hash? [{:type "char" :body \a}
                                      {:type "run" :body {:of "char" :values [\a \b]}}])))
    (is (lit/names-child-hash? [{:type "ref" :body [1 2 3 4]}]))
    (is (lit/names-child-hash? [{:type "char" :body \a}
                                {:type "run" :body {:of "ref" :values [[1 2 3 4] [5 6 7 8]]}}]))
    (is (lit/names-child-hash? [{:type "repeat" :body {:of "ref" :n 3 :value [1 2 3 4]}}]))
    (testing "a literal that holds a run of refs is bad-item, not bad-entry"
      (is (= :bad-item
             (thrown-reason
              #(items/verify-item! (store/mem-store)
                                   {:enc :literal :hash [1 2 3 4]
                                    :literal {:type "vector"
                                              :body [{:type "run"
                                                      :body {:of "ref" :values [[1 2 3 4] [5 6 7 8]]}}]}})))))))

(deftest a-page-with-a-run-of-refs-is-a-spine-page
  ;; The over-cap fixture holds an ft/digit with a char run and a run of refs.
  ;; It must verify as a spine page (hash only), not leak a raw :missing from
  ;; recomputing a leaf measure.
  (let [{:keys [decoded]} (fixture-items "item-stream-literal-over-cap")
        page (first (filter #(and (= :node (:enc %))
                                  (lit/names-child-hash? (:body (second (:entry %)))))
                            decoded))]
    (is (some? page) "the fixture has a page whose refs are a run")
    (is (= #{:hash} (items/verify-item! (store/mem-store) page)))))

(deftest a-missing-entry-is-a-protocol-reason
  ;; Never a raw {:reason :missing}.
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        [header] (node-items st (reveal/page-order st h))]
    (is (= #{:pending} (items/verify-item! (store/mem-store) header))
        "header first: a missing root is pending, not an error")))

(deftest tampering-is-refused
  (let [st (store/mem-store)
        h (v/hash (lines st 300))
        {:keys [pages]} (reveal-nodes st h)
        digit (first (filter #(= "ft/digit" (first (:entry %))) (node-items st pages)))
        rst (store/mem-store)
        refuse (fn [item] (thrown-reason #(items/verify-item! rst item)))]
    (is (= :accepted (refuse digit)) "the untouched page")
    (testing "a hash that is not the entry's"
      (is (= :bad-hash (refuse (update-in digit [:hash 0] inc)))))
    (testing "a claimed elements-fuse that does not hash to the item"
      (is (= :bad-hash (refuse (update-in digit [:entry 1 :measure :elements-fuse 0] inc)))))
    (testing "a count the page's own contents do not give (the hash does not cover it)"
      (is (= :bad-entry (refuse (update-in digit [:entry 1 :measure :count] inc)))))
    (testing "a literal with one byte changed"
      (let [lit (first (decode-all (:items (items/encode-value st (v/hash (v/string st "hello"))))))
            bad (update-in lit [:literal :body] #(str/replace % "h" "j"))]
        (is (= :accepted (refuse lit)))
        (is (= :bad-hash (refuse bad)))))
    (testing "a literal that names a child by hash"
      (is (= :bad-item
             (refuse {:enc :literal :hash (:hash digit)
                      :literal {:type "vector"
                                :body [{:type "ref" :body (:hash digit)}]}}))))))

(deftest the-reveal-service-and-the-peer-layer-accept-the-same-bytes
  ;; Both take the header first; the reveal service holds a header until its
  ;; root arrives, the peer layer stores it and checks it then.
  (let [st (store/mem-store)
        h (v/hash (lines st 100))
        pages (node-items st (reveal/page-order st h))
        via-service (sr/accept-items! nil pages)
        rx (items/finish! (items/accept-items! nil pages))]
    (is (some? (store/await (store/s-get via-service h))))
    (is (some? (store/await (store/s-get (:store rx) h))))))
