(ns dacite.peer.items-checks
  "Peer protocol v1, items: the literal rule, packing, and verification.
   Spec: docs/spec/peer-v1.md §3.2 and §3.3.

   The bodies that were `dacite.peer.peer-items-test`, plus
   `node-items-over-a-payload-are-reported-by-default-and-throw-on-request`
   from `literal_limits_test` (it needs only `peer.items`). The JVM runs
   them from `dacite.peer.peer-items-test`. nbb runs them from
   `dacite.peer.items-cljs-test` (`npm run items:test`).

   Each check returns a vector of problem strings, empty when it holds.
   Nothing here waits on a clock. A check takes milliseconds."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dacite.peer.conformance :as conf]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.wire.binary :as bin]
            #?(:clj [dacite.service.reveal :as sr])
            #?(:cljs ["fs" :as fs])))

(defn- read-text
  [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync fs path "utf8")))

(defn- thrown-reason
  [f]
  (try (f) :accepted
       (catch #?(:clj Throwable :cljs :default) e
         (:reason (ex-data e)))))

(defn- fixture-items
  "Decoded items of an item-stream fixture, read through the frame reader."
  [id]
  (let [root (conf/fixtures-root)
        text (read-text (str root "/cases/" id "/frames.hex"))
        frames (mapv bin/hex->bytes (remove str/blank? (str/split-lines text)))
        meta (edn/read-string (read-text (str root "/cases/" id "/message.edn")))
        {:keys [items]} (reduce (fn [{:keys [state items]} bs]
                                  (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                    {:state st :items (into items more)}))
                                {:state (frame/reader (:stream meta)) :items []}
                                frames)]
    {:frames frames :bytes items :decoded (bin/decode-item-stream (frame/concat-bytes items))}))

(defn- lines
  [st n]
  (apply v/vector st (map #(v/string st (str "line " % ": the quick brown fox")) (range n))))

(def ^:private page-lines
  "A vector of this many lines has a finger-tree page as its root, not a
   bare leaf. Two lines already do. Sixteen stays under 3 s on nbb. Three
   hundred is the same shape, and a check that built it twice crossed 3 s."
  16)

(defn- join-chunks
  "Join `n` one-character pieces without one `apply` of `n` arguments."
  [n ch]
  (str/join
   (map (fn [start]
          (apply str (map ch (range start (min n (+ start 512))))))
        (range 0 n 512))))

(defn- cycled
  "n letters, a through z and back, so neighbors differ."
  [n]
  (join-chunks n #(char (+ 97 (mod % 26)))))

(defn- mixed-letters
  [n]
  (join-chunks n #(char (+ 97 (mod (* % 7) 26)))))

(defn- node-total
  [st h]
  (reduce + (map #(bin/byte-len (reveal/encode-page st {:hash %})) (reveal/page-order st h))))

(defn- decode-all [bss] (bin/decode-item-stream (frame/concat-bytes bss)))

(defn- node-items
  "Decoded node items for `hashes`, in the order given."
  [st hashes]
  (mapv (fn [h] (first (bin/decode-item-stream (reveal/encode-page st {:hash h}))))
        hashes))

(defn- reveal-nodes
  [st h]
  (let [order (reveal/page-order st h)
        structural? #(str/starts-with? (first (store/require-node st %)) "ft/")]
    {:order order
     :pages (vec (filter structural? order))
     :others (vec (remove structural? order))}))

(defn- held?
  [st h]
  (some? (store/await (store/s-get st h))))

(defn- bad
  [label expected actual]
  (when-not (= expected actual)
    [(str label ": expected " (pr-str expected) ", got " (pr-str actual))]))

(defn- bump-cell
  "One more than a hash word or a fuse cell. The JVM uses `inc`, as the
   old test did. On nbb those values are BigInts. `instance?` does not see
   them, and `inc` mixes a BigInt with a number, so coerce through the
   decimal text, which is exact."
  [x]
  #?(:clj (inc x)
     :cljs (+ (js/BigInt (str x)) (js/BigInt 1))))

(defn- item-of-size
  "n zero bytes with a node header whose payload length is n minus 37.
   `feed` trusts that length when it cuts items back out of frames."
  [n]
  (let [plen (- n 37)]
    (bin/as-wire-bytes
     (assoc (vec (repeat n 0))
            34 (bit-and 0xff (quot plen 65536))
            35 (bit-and 0xff (quot plen 256))
            36 (bit-and 0xff plen)))))

(defn- payload-sizes
  [frames]
  (mapv #(bin/byte-len (:payload (frame/decode-frame %))) frames))

(defn- search-fit
  "Largest n in [lo, hi) for which `(fits n)` is truthy. `fits` is monotonic."
  [fits lo hi]
  (loop [lo lo hi hi]
    (if (<= (- hi lo) 1)
      lo
      (let [mid (quot (+ lo hi) 2)]
        (if (fits mid) (recur mid hi) (recur lo mid))))))

;; ---------------------------------------------------------------------------
;; The fixtures verify
;; ---------------------------------------------------------------------------

(defn every-item-stream-fixture-verifies
  ;; JUSTIFICATION for the time (rule: a test over about 3 s is broken unless it
  ;; says why). `item-stream-literal-over-cap` is a string one letter past one
  ;; frame payload, so accepting it rebuilds the value from node pages and
  ;; recomputes the fuse. On nbb that pass is about 10 s. The other four fixtures
  ;; here are under a second. The check is those fixtures, so the over-cap one
  ;; stays. The JVM does the same work in well under 3 s (`max-ms`).
  []
  (vec
   (mapcat
    (fn [id]
      (let [{:keys [decoded]} (fixture-items id)
            st (:store (items/finish! (items/accept-items! nil decoded)))]
        (concat
         (when-not (pos? (count decoded)) [(str id ": no items")])
         (when-not (every? #(held? st (:hash %)) decoded)
           [(str id ": an item hash is not stored")]))))
    ["item-stream-one-frame" "item-stream-mixed" "item-stream-partial"
     "item-stream-literal-at-cap" "item-stream-literal-over-cap"])))

;; ---------------------------------------------------------------------------
;; Serialization: the literal rule
;; ---------------------------------------------------------------------------

(defn small-values-are-one-literal
  []
  (let [st (store/mem-store)
        h (v/hash (v/map st "title" "hello" "tags" (v/vector st "a" "b")))
        {:keys [items encs]} (items/encode-value st h)
        rst (:store (items/accept-items! nil (decode-all items)))]
    (vec
     (concat
      (bad "one literal" [:literal] encs)
      (when-not (< (bin/byte-len (first items)) (node-total st h))
        [(str "literal is not shorter than the nodes: " (bin/byte-len (first items)))])
      (when-not (held? rst h) ["the receiver did not store the literal"])))))

(defn the-shorter-form-wins-and-is-never-worse
  []
  (let [st (store/mem-store)
        shared (v/string st (str/join (repeat 300 "xyz-")))
        values [(v/string st "hello")
                (lines st 20)
                (apply v/map st (mapcat (fn [i] [(str "key" i) shared]) (range 40)))
                (v/string st (str/join (repeat 3000 "a")))]]
    (vec
     (mapcat
      (fn [val]
        (let [h (v/hash val)
              {:keys [items encs]} (items/encode-value st h)
              total (reduce + (map bin/byte-len items))
              nodes (node-total st h)
              rst (:store (items/finish! (items/accept-items! nil (decode-all items))))]
          (concat
           (when-not (<= total nodes)
             [(str (pr-str (frequencies encs)) " " total " > node total " nodes)])
           (when-not (held? rst h)
             [(str "not stored " (pr-str (frequencies encs)))]))))
      values))))

(defn a-literal-never-exceeds-one-frame-payload
  []
  (let [cap (frame/max-literal-item)
        {:keys [decoded]} (fixture-items "item-stream-literal-at-cap")
        over (fixture-items "item-stream-literal-over-cap")]
    (vec
     (concat
      (bad "cap" 16378 cap)
      (bad "just under is one literal" [:literal] (mapv :enc decoded))
      (bad "just under is exactly the cap" cap
           (bin/byte-len (bin/encode-item (first decoded))))
      (bad "just under is one frame" 1
           (count (:frames (fixture-items "item-stream-literal-at-cap"))))
      (bad "one letter more: the header is a node" :node
           (:enc (first (:decoded over))))
      (when-not (> (count (:decoded over)) 1)
        ["over the cap the value is a single item"])
      (when-not (every? #(<= (bin/byte-len (bin/encode-item %)) cap) (:decoded over))
        ["an item needs more than one frame"])))))

(defn the-cap-applies-at-its-boundary
  []
  (let [st (store/mem-store)
        mf 1024
        cap (frame/max-literal-item mf)
        hash-of (fn [n] (v/hash (v/string st (cycled n))))
        fits (fn [n] (items/literal-bytes st (hash-of n) cap))
        n (search-fit fits 100 1500)
        under (items/encode-value st (hash-of n) {:max-frame-bytes mf})
        over (items/encode-value st (hash-of (inc n)) {:max-frame-bytes mf})
        lit-sizes (map second (filter #(= :literal (first %))
                                      (map vector (:encs over) (:items over))))]
    (vec
     (concat
      (bad "the longest that fits is exactly the cap" cap (bin/byte-len (fits n)))
      (bad "one more has no literal" nil (fits (inc n)))
      (bad "under the cap" [:literal] (:encs under))
      (bad "over the cap the header is a node, and it is first" :node (first (:encs over)))
      (when-not (every? #(<= (bin/byte-len %) cap) lit-sizes)
        ["a literal in the over-cap value exceeds the cap"])
      (when-not (some? (items/finish! (items/accept-items! nil (decode-all (:items under)))))
        ["the value under the cap did not verify"])
      (when-not (some? (items/finish! (items/accept-items! nil (decode-all (:items over)))))
        ["the value over the cap did not verify"])))))

(defn the-cap-follows-the-negotiated-frame-size
  []
  (let [st (store/mem-store)
        h (v/hash (lines st 60))
        big (items/encode-value st h)
        small (items/encode-value st h {:max-frame-bytes 1024})
        room (- 1024 6)]
    (vec
     (concat
      (bad "the default frame is a literal" [:literal] (:encs big))
      (bad "the whole value does not fit 1024 - 6" :node (first (:encs small)))
      (when-not (every? #(or (not= :literal (:enc %))
                             (<= (bin/byte-len (bin/encode-item %)) room))
                        (decode-all (:items small)))
        ["a literal does not fit 1024 - 6"])))))

;; ---------------------------------------------------------------------------
;; Frames carry whole items
;; ---------------------------------------------------------------------------

(defn frames-keep-items-whole-when-they-can
  []
  (let [in (mapv item-of-size [1000 40000 1000 16378 20000])
        frames (frame/chunk-items 5 in)
        out (:items (reduce (fn [{:keys [state items]} bs]
                              (let [[st more] (frame/feed state (frame/decode-frame bs))]
                                {:state st :items (into items more)}))
                            {:state (frame/reader 5) :items []}
                            frames))]
    (vec
     (concat
      (bad "three 10000-byte items, one per frame"
           [10000 10000 10000]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [10000 10000 10000]))))
      (bad "small items share a frame"
           [15000]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [5000 5000 5000]))))
      (bad "an item bigger than a payload is cut, and the next follows its tail"
           [1000 16378 16378 (+ 7244 1000)]
           (payload-sizes (frame/chunk-items 5 (map item-of-size [1000 40000 1000]))))
      (bad "the receiver gets the same items"
           (mapv bin/bytes->hex in) (mapv bin/bytes->hex out))))))

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(defn items-verify-in-the-order-the-sender-writes-them
  []
  (let [{:keys [decoded]} (fixture-items "item-stream-mixed")
        rx (items/receiver)
        checks (mapv #(items/accept-item! rx %) decoded)
        spanned (:decoded (fixture-items "item-stream-node-spans-frames"))
        rx2 (items/receiver)
        checks2 (mapv #(items/accept-item! rx2 %) spanned)]
    (vec
     (concat
      (bad "the header comes first" :node (:enc (first decoded)))
      (when-not (contains? (first checks) :pending)
        ["the header does not wait for its root"])
      (when-not (some #(contains? % :literal) checks)
        ["no literal was accepted"])
      (when-not (empty? (items/unchecked rx))
        ["the header was not checked when its root arrived"])
      (when-not (some #(contains? % :body-measure) checks2)
        ["a page was not checked against its contents"])
      (when-not (empty? (items/unchecked rx2))
        ["the spanning fixture left an unchecked header"])))))

(defn a-header-is-accepted-first-and-checked-when-its-root-arrives
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        [header root & more] (node-items st (reveal/page-order st h))
        rx (items/receiver)
        arrived (items/accept-item! rx header)
        stored (held? (:store rx) h)
        waiting (items/unchecked rx)
        checked (items/accept-item! rx root)]
    (doseq [it more] (items/accept-item! rx it))
    (vec
     (concat
      (bad "accepted on arrival" #{:pending} arrived)
      (when-not stored ["not stored on arrival"])
      (bad "waits for its root" [h] waiting)
      (bad "the root arrives: the header is checked" #{:hash} checked)
      (when-not (empty? (items/unchecked rx)) ["still unchecked after the root"])
      (bad "finish" rx (items/finish! rx))))))

(defn a-forged-header-count-or-size-is-refused-when-the-root-arrives
  []
  (vec
   (mapcat
    (fn [[label make]]
      (mapcat
       (fn [[k n]]
         (let [st (store/mem-store)
               h (v/hash (make st))
               [header root] (node-items st (reveal/page-order st h))
               forged (assoc-in header [:entry 1 k] n)
               rx (items/receiver)]
           (concat
            (bad (str label " " k ": accepted on arrival")
                 #{:pending} (items/accept-item! rx forged))
            (bad (str label " " k) :bad-entry (thrown-reason #(items/accept-item! rx root)))
            (when (held? (:store rx) h)
              [(str label " " k ": the forged header stayed")])
            (when (some #(= % h) (items/unchecked rx))
              [(str label " " k ": still unchecked")]))))
       [[:count 1000000] [:size-bytes 77]]))
    [["a bare-leaf root" #(v/vector % (v/string % "only"))]
     ["a page root" #(lines % page-lines)]])))

(defn a-header-whose-hash-does-not-fit-its-root-is-refused
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        [header root] (node-items st (reveal/page-order st h))
        other (v/hash (lines st (inc page-lines)))
        [oheader] (node-items st (reveal/page-order st other))
        forged (assoc-in oheader [:entry 1 :root] (get-in header [:entry 1 :root]))
        rx (items/receiver)]
    (vec
     (concat
      (bad "accepted" #{:pending} (items/accept-item! rx forged))
      (bad "refused" :bad-hash (thrown-reason #(items/accept-item! rx root)))
      (when (held? (:store rx) other) ["the forged header stayed"])))))

(defn only-the-failing-headers-waiting-on-a-root-are-removed
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        [header root] (node-items st (reveal/page-order st h))
        fake1 [1 2 3 4]
        fake2 [9 8 7 6]
        both (let [rx (items/receiver)]
               (concat
                (bad "forged 1 accepted" #{:pending}
                     (items/accept-item! rx (assoc header :hash fake1)))
                (bad "forged 2 accepted" #{:pending}
                     (items/accept-item! rx (assoc header :hash fake2)))
                (bad "two waiting" 2 (count (items/unchecked rx)))
                (bad "both fail" :bad-hash (thrown-reason #(items/accept-item! rx root)))
                (when-not (empty? (items/unchecked rx)) ["forged headers still tracked"])
                (when (held? (:store rx) fake1) ["fake1 stayed"])
                (when (held? (:store rx) fake2) ["fake2 stayed"])))
        mixed (let [rx (items/receiver)]
                (items/accept-item! rx header)
                (items/accept-item! rx (assoc header :hash fake1))
                (concat
                 (bad "mixed fail" :bad-hash (thrown-reason #(items/accept-item! rx root)))
                 (when-not (empty? (items/unchecked rx)) ["mixed: still tracked"])
                 (when (held? (:store rx) fake1) ["the forged header stayed"])
                 (when-not (held? (:store rx) (:hash header))
                   ["the honest header was removed"])))]
    (vec (concat both mixed))))

(defn a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives
  []
  (let [st (store/mem-store)
        h (v/hash (v/vector st (v/string st "x")))
        all (node-items st (reveal/page-order st h))
        rx (items/receiver)
        waited (items/accept-item! rx (first all))]
    (doseq [it (rest all)] (items/accept-item! rx it))
    (vec
     (concat
      (bad "the vector header waits" #{:pending} waited)
      (when-not (empty? (items/unchecked rx)) ["a header is still unchecked"])
      (when-not (held? (:store rx) h) ["the vector is not stored"])))))

(defn a-stream-that-ends-early-leaves-no-unchecked-header
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        [header] (node-items st (reveal/page-order st h))
        rx (items/receiver)]
    (items/accept-item! rx header)
    (items/discard-unchecked! rx)
    (let [partial-problems (concat
                            (when (held? (:store rx) h) ["PARTIAL left the header"])
                            (when-not (empty? (items/unchecked rx))
                              ["PARTIAL left it tracked"]))]
      (items/accept-item! rx header)
      (vec
       (concat
        partial-problems
        (bad "FIN without the root" :bad-entry (thrown-reason #(items/finish! rx)))
        (when (held? (:store rx) h) ["FIN left the header"]))))))

(defn pages-verify-in-any-order
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        {:keys [pages]} (reveal-nodes st h)
        rx (items/receiver)]
    (doseq [it (reverse (node-items st pages))]
      (items/accept-item! rx it))
    (when-not (every? #(held? (:store rx) %) pages)
      ["a page sent in reverse was not stored"])))

(defn a-run-of-refs-is-a-ref
  []
  (vec
   (concat
    (bad "chars are not refs" false
         (lit/names-child-hash? [{:type "char" :body \a}
                                 {:type "run" :body {:of "char" :values [\a \b]}}]))
    (bad "a ref is a ref" true
         (lit/names-child-hash? [{:type "ref" :body [1 2 3 4]}]))
    (bad "a run of refs" true
         (lit/names-child-hash?
          [{:type "char" :body \a}
           {:type "run" :body {:of "ref" :values [[1 2 3 4] [5 6 7 8]]}}]))
    (bad "a repeat of refs" true
         (lit/names-child-hash?
          [{:type "repeat" :body {:of "ref" :n 3 :value [1 2 3 4]}}]))
    (bad "a literal that holds a run of refs is bad-item" :bad-item
         (thrown-reason
          #(items/verify-item! (store/mem-store)
                               {:enc :literal :hash [1 2 3 4]
                                :literal {:type "vector"
                                          :body [{:type "run"
                                                  :body {:of "ref"
                                                         :values [[1 2 3 4] [5 6 7 8]]}}]}}))))))

(defn a-page-with-a-run-of-refs-is-a-spine-page
  []
  (let [st (store/mem-store)
        h (v/hash (v/string st (mixed-letters 60000)))
        page (first (filter #(and (= :node (:enc %))
                                  (lit/names-child-hash? (:body (second (:entry %)))))
                            (node-items st (reveal/page-order st h))))]
    (vec
     (concat
      (when-not (some? page) ["a long string has no page whose refs are a run"])
      (when page
        (bad "a spine page checks by hash" #{:hash}
             (items/verify-item! (store/mem-store) page)))))))

(defn a-missing-entry-is-a-protocol-reason
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        [header] (node-items st (reveal/page-order st h))]
    (bad "header first: a missing root is pending" #{:pending}
         (items/verify-item! (store/mem-store) header))))

(defn tampering-is-refused
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        {:keys [pages]} (reveal-nodes st h)
        digit (first (filter #(= "ft/digit" (first (:entry %))) (node-items st pages)))
        rst (store/mem-store)
        refuse (fn [item] (thrown-reason #(items/verify-item! rst item)))
        lit (first (decode-all (:items (items/encode-value st (v/hash (v/string st "hello"))))))]
    (vec
     (concat
      (bad "the untouched page" :accepted (refuse digit))
      (bad "a hash that is not the entry's" :bad-hash
           (refuse (update-in digit [:hash 0] bump-cell)))
      (bad "a claimed elements-fuse that does not hash to the item" :bad-hash
           (refuse (update-in digit [:entry 1 :measure :elements-fuse 0] bump-cell)))
      (bad "a count the page's own contents do not give" :bad-entry
           (refuse (update-in digit [:entry 1 :measure :count] inc)))
      (bad "the literal" :accepted (refuse lit))
      (bad "a literal with one byte changed" :bad-hash
           (refuse (update-in lit [:literal :body] #(str/replace % "h" "j"))))
      (bad "a literal that names a child by hash" :bad-item
           (refuse {:enc :literal :hash (:hash digit)
                    :literal {:type "vector"
                              :body [{:type "ref" :body (:hash digit)}]}}))))))

(defn the-reveal-service-and-the-peer-layer-accept-the-same-bytes
  []
  (let [st (store/mem-store)
        h (v/hash (lines st page-lines))
        pages (node-items st (reveal/page-order st h))
        rx (items/finish! (items/accept-items! nil pages))]
    (vec
     (concat
      (when-not (held? (:store rx) h) ["the peer layer did not store the root"])
      #?(:clj (let [via (sr/accept-items! nil pages)]
                (when-not (held? via h) ["the reveal service did not store the root"]))
         :cljs [])))))

(defn node-items-over-a-payload-are-reported-by-default-and-throw-on-request
  []
  (let [st (store/mem-store)
        h (v/hash (v/string st (str/join (repeat 3000 "x"))))
        normal (items/encode-value st h)
        tiny (items/encode-value st h {:max-frame-bytes 120})
        cap (frame/max-literal-item 120)]
    (vec
     (concat
      (when-not (empty? (:oversized normal))
        [(str "a normal frame reported oversized " (pr-str (:oversized normal)))])
      (when-not (seq (:oversized tiny)) ["a tiny frame reported nothing oversized"])
      (bad "oversized items are still sent" (count (:hashes tiny)) (count (:items tiny)))
      (when-not (every? #(> (:len %) cap) (:oversized tiny))
        ["an oversized item is within the tiny cap"])
      (bad "strict throws" :node-too-large
           (thrown-reason #(items/encode-value st h {:max-frame-bytes 120
                                                     :strict-node-cap? true})))))))

(def checks
  (array-map
   "every-item-stream-fixture-verifies" every-item-stream-fixture-verifies
   "small-values-are-one-literal" small-values-are-one-literal
   "the-shorter-form-wins-and-is-never-worse" the-shorter-form-wins-and-is-never-worse
   "a-literal-never-exceeds-one-frame-payload" a-literal-never-exceeds-one-frame-payload
   "the-cap-applies-at-its-boundary" the-cap-applies-at-its-boundary
   "the-cap-follows-the-negotiated-frame-size" the-cap-follows-the-negotiated-frame-size
   "frames-keep-items-whole-when-they-can" frames-keep-items-whole-when-they-can
   "items-verify-in-the-order-the-sender-writes-them" items-verify-in-the-order-the-sender-writes-them
   "a-header-is-accepted-first-and-checked-when-its-root-arrives" a-header-is-accepted-first-and-checked-when-its-root-arrives
   "a-forged-header-count-or-size-is-refused-when-the-root-arrives" a-forged-header-count-or-size-is-refused-when-the-root-arrives
   "a-header-whose-hash-does-not-fit-its-root-is-refused" a-header-whose-hash-does-not-fit-its-root-is-refused
   "only-the-failing-headers-waiting-on-a-root-are-removed" only-the-failing-headers-waiting-on-a-root-are-removed
   "a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives" a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives
   "a-stream-that-ends-early-leaves-no-unchecked-header" a-stream-that-ends-early-leaves-no-unchecked-header
   "pages-verify-in-any-order" pages-verify-in-any-order
   "a-run-of-refs-is-a-ref" a-run-of-refs-is-a-ref
   "a-page-with-a-run-of-refs-is-a-spine-page" a-page-with-a-run-of-refs-is-a-spine-page
   "a-missing-entry-is-a-protocol-reason" a-missing-entry-is-a-protocol-reason
   "tampering-is-refused" tampering-is-refused
   "the-reveal-service-and-the-peer-layer-accept-the-same-bytes" the-reveal-service-and-the-peer-layer-accept-the-same-bytes
   "node-items-over-a-payload-are-reported-by-default-and-throw-on-request" node-items-over-a-payload-are-reported-by-default-and-throw-on-request))

(def max-ms
  "No check may take longer than this (milliseconds), except `slow-ms`."
  3000)

(def slow-ms
  "nbb ceilings above `max-ms`. See the JUSTIFICATION on the check."
  {"every-item-stream-fixture-verifies" 30000})

(defn limit-ms
  [name]
  (get slow-ms name max-ms))

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-all
  "Run every check. Returns [{:name :ms :problems}]. A check that throws
   is a problem, and one over `max-ms` is a problem."
  []
  (mapv (fn [[name f]]
          (let [t0 (now-ms)
                problems (try (vec (f))
                               (catch #?(:clj Throwable :cljs :default) e
                                 [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
                ms (- (now-ms) t0)
                limit (limit-ms name)]
            {:name name :ms ms
             :problems (cond-> problems
                         (> ms limit) (conj (str "took " ms " ms, over " limit)))}))
        checks))
