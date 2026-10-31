(ns dacite.peer.msg
  "Peer protocol v1 control messages: small Dacite values in wire-v1.

   Spec: docs/spec/peer-v1.md. Fixtures: fixtures/peer-v1/.

   A control message is a map with string keys. It travels as one frame
   whose payload is the wire-v1 `Lit` of that map: no item header, no
   hash, no store entry. The message type and stream id are in the frame
   envelope (`dacite.peer.frame`), not in the value.

   Native model of a message value (the same on the JVM and nbb):

     nil, boolean, string
     integer          an i64. Floats are not part of the v1 profile.
     vector, set      of native values
     map              string keys, native values
     bytes            a blob (`byte[]` / `Uint8Array`)
     (hash-ref hex)   a u256, written as 64 hex chars

   `encode-message` builds the Dacite value in a scratch store and asks
   the real pack encoder for its canonical literal, so map and set order
   is the one wire-v1 defines."
  (:require [clojure.string :as str]
            [dacite.host :as host]
            [dacite.peer.frame :as frame]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

;; =============================================================================
;; Native model
;; =============================================================================

(defn hash-ref
  "A hash (u256) as it appears in a message: 64 lowercase hex chars."
  [hex]
  {::u256 (str/lower-case hex)})

(defn hash-ref?
  [x]
  (and (map? x) (= [::u256] (keys x))))

(defn hash-ref-hex
  [x]
  (::u256 x))

(defn bytes-value?
  [x]
  #?(:clj (bytes? x)
     :cljs (instance? js/Uint8Array x)))

(defn- int-value?
  [x]
  #?(:clj (or (instance? Long x) (instance? Integer x)
              (instance? Short x) (instance? Byte x))
     :cljs (and (number? x) (js/Number.isInteger x))))

(defn- profile-error!
  [msg data]
  (throw (ex-info msg (assoc data :reason :profile))))

(defn- ->value
  "Native → Dacite value in `st`."
  [st x]
  (cond
    (nil? x) (v/null st)
    (boolean? x) (v/bool st x)
    (string? x) (v/string st x)
    (int-value? x) (v/i64 st x)
    (hash-ref? x) (let [hex (hash-ref-hex x)]
                    (when-not (re-matches #"[0-9a-f]{64}" hex)
                      (profile-error! "a hash is 64 hex chars" {:got hex}))
                    (v/u256 st (bin/hex->bytes hex)))
    (bytes-value? x) (v/blob st x)
    (vector? x) (apply v/vector st (map #(->value st %) x))
    (set? x) (apply v/set st (map #(->value st %) x))
    (map? x) (apply v/map st
                    (mapcat (fn [[k val]]
                              (when-not (string? k)
                                (profile-error! "map keys are strings" {:key k}))
                              [(v/string st k) (->value st val)])
                            x))
    :else (profile-error! "value is outside the peer-v1 profile" {:value x})))

(defn- form-bytes
  [body]
  (bin/as-wire-bytes body))

(declare form->native)

(defn- elements
  [body]
  (lit/expand-rle-seq body))

(defn- form->native
  [{:keys [type body]}]
  (case (str type)
    "null" nil
    "bool" (boolean body)
    "i64" #?(:clj (long body) :cljs body)
    "string" (str body)
    "blob" (form-bytes body)
    "u256" (hash-ref (bin/bytes->hex (form-bytes body)))
    "vector" (mapv form->native (elements body))
    "set" (into #{} (map form->native) (elements body))
    "map" (into {}
                (map (fn [[k val]]
                       (let [kn (form->native k)]
                         (when-not (string? kn)
                           (profile-error! "map keys are strings" {:key kn}))
                         [kn (form->native val)])))
                body)
    (profile-error! "literal type is outside the peer-v1 profile" {:type type})))

(defn encode-value
  "Canonical wire-v1 `Lit` bytes of a native value."
  [x]
  (let [st (store/mem-store)
        val (->value st x)
        form (pack/literal-of st (v/hash val))]
    (bin/encode-lit-bytes form)))

(defn decode-value
  "Native value of wire-v1 `Lit` bytes. Rejects anything outside the profile."
  [bs]
  (form->native (bin/decode-lit-bytes bs)))

;; =============================================================================
;; Shapes
;; =============================================================================

(def max-hashes
  "Hashes in one `have?`, `ack` or `release`. 256 hashes are 8 KiB, and a
   bitmap for them is 32 bytes."
  256)

(def error-codes
  #{"unsupported" "unsupported-version" "unsupported-policy" "malformed"
    "unknown-hash" "bad-entry" "too-large" "rate-limited" "overloaded"
    "cancelled" "internal"})

(def capabilities
  "Capabilities v1 defines. A peer ignores capabilities it does not know."
  #{"have" "reveal"})

(def kinds
  "Peer kinds v1 defines. An unknown kind is accepted and read as \"browser\"
   (see `peer-kind`)."
  #{"node" "browser"})

(def max-string-bytes
  "Longest string in any message, in UTF-8 bytes. Free text (`reason`,
   `message`, `agent`) is cut to this by a sender, never rejected there."
  256)

(def max-set-size
  "Most elements in a set (`versions`, `caps`)."
  32)

(defn max-hashes-for
  "Hashes a `have?`, `ack` or `release` can hold when frames are at most
   `max-frame-bytes`: 32 bytes each, after 33 bytes of header and map
   framing and 320 bytes kept for a `release` reason. At 16 KiB that is
   more than 256, so 256 rules; at 1024 it is 20."
  [max-frame-bytes]
  (max 1 (min max-hashes (quot (- max-frame-bytes 33 320) 32))))

(def policies
  "Reveal policies v1 defines. `get` names one."
  #{"default"})

;; spec forms: :int :nat (>= 0) :pos (>= 1) :string :bool :hash :blob
;;   [:vec spec] [:set spec] [:hashes] [:map {key spec} #{required keys}]
(def shapes
  {:hello
   [:map {"versions" [:set :pos]
          "agent" :string
          "kind" :string
          "caps" [:set :string]
          "limits" [:map {"max-frame" :nat "max-item" :pos
                          "max-streams" :pos "max-have" :nat}
                    #{"max-frame" "max-item" "max-streams" "max-have"}]
          "rates" [:map {"send-max" :pos "recv-max" :pos
                         "req-rate" :pos "req-burst" :pos}
                   #{"send-max" "recv-max" "req-rate" "req-burst"}]}
    #{"versions" "kind" "caps" "limits" "rates"}]

   :have?
   [:map {"hashes" [:vec :hash]} #{"hashes"}]

   :have
   [:map {"count" :nat "bits" :blob} #{"count" "bits"}]

   :get
   [:map {"hash" :hash
          "policy" :string
          "interest" [:map {"peak" :pos "tau-ms" :pos "floor" :nat}
                      #{"peak" "tau-ms" "floor"}]}
    #{"hash" "policy"}]

   :ack
   [:map {"hashes" [:vec :hash]} #{"hashes"}]

   :release
   [:map {"hashes" [:vec :hash] "reason" :string} #{"hashes"}]

   :cancel
   [:map {"reason" :string} #{}]

   :error
   [:map {"code" :string "message" :string "fatal" :bool "retry-ms" :nat}
    #{"code" "fatal"}]})

(defn- utf8-length
  [s]
  (bin/byte-len (bin/as-wire-bytes (host/utf8-bytes s))))

(defn- check
  "Problems (strings) of `x` against spec `sp`, at path `p`."
  [sp x p]
  (let [kind (if (vector? sp) (first sp) sp)]
    (case kind
      :nat (if (and (int-value? x) (>= x 0)) [] [(str p ": expected a non-negative integer")])
      :pos (if (and (int-value? x) (>= x 1)) [] [(str p ": expected an integer of at least 1")])
      :int (if (int-value? x) [] [(str p ": expected an integer")])
      :string (cond
                (not (string? x)) [(str p ": expected a string")]
                (> (utf8-length x) max-string-bytes)
                [(str p ": longer than " max-string-bytes " UTF-8 bytes")]
                :else [])
      :bool (if (boolean? x) [] [(str p ": expected a boolean")])
      :blob (if (bytes-value? x) [] [(str p ": expected a blob")])
      :hash (if (hash-ref? x) [] [(str p ": expected a hash")])
      :vec (if (vector? x)
             (into [] (mapcat (fn [i el] (check (second sp) el (str p "[" i "]")))
                              (range) x))
             [(str p ": expected a vector")])
      :set (cond
             (not (set? x)) [(str p ": expected a set")]
             (> (count x) max-set-size)
             [(str p ": more than " max-set-size " elements")]
             :else (into [] (mapcat #(check (second sp) % (str p "{}"))) x))
      :map (if-not (map? x)
             [(str p ": expected a map")]
             (let [[_ fields required] sp]
               (into (vec (for [k required :when (not (contains? x k))]
                            (str p "." k ": required")))
                     (mapcat (fn [[k val]]
                               (if-let [fsp (get fields k)]
                                 (check fsp val (str p "." k))
                                 []))
                             x)))))))

(defn bitmap-held?
  "True when bit `i` of `bits` is set."
  [bits i]
  (let [bits (bin/as-wire-bytes bits)
        b #?(:clj (bit-and 0xff (aget ^bytes bits (int (quot i 8))))
             :cljs (aget bits (quot i 8)))]
    (pos? (bit-and b (bit-shift-right 0x80 (rem i 8))))))

(defn- semantic-problems
  "Rules a shape cannot say. `mode` is `:receive` (what a receiver must
   refuse) or `:send` (receive rules, plus the names a sender may use: a
   defined policy, error code and kind). A receiver never refuses a name
   it does not know: it answers or reads it as a default (`error-code`,
   `peer-kind`, `answer-error`)."
  [type x mode]
  (case type
    :hello
    (let [lim (get x "limits")]
      (cond-> []
        (and (set? (get x "versions")) (empty? (get x "versions")))
        (conj "versions: must not be empty")
        (and (= :send mode) (string? (get x "kind"))
             (not (contains? kinds (get x "kind"))))
        (conj "kind: must be node or browser")
        (and (map? lim) (int-value? (get lim "max-frame"))
             (not (<= frame/min-max-frame (get lim "max-frame") frame/max-frame)))
        (conj "limits.max-frame: must be 1024..16384")
        (and (map? lim) (int-value? (get lim "max-have"))
             (not (<= 1 (get lim "max-have") max-hashes)))
        (conj "limits.max-have: must be 1..256")))

    :have?
    (cond-> []
      (and (vector? (get x "hashes")) (not (<= 1 (count (get x "hashes")) max-hashes)))
      (conj "hashes: 1..256 required"))

    (:ack :release)
    (cond-> []
      (and (vector? (get x "hashes")) (not (<= 1 (count (get x "hashes")) max-hashes)))
      (conj "hashes: 1..256 required"))

    :have
    (let [n (get x "count") bits (get x "bits")]
      (if (and (int-value? n) (bytes-value? bits))
        (let [blen (bin/byte-len bits)
              expect (quot (+ n 7) 8)]
          (cond-> []
            (not (<= 1 n max-hashes)) (conj "count: 1..256 required")
            (not= blen expect) (conj (str "bits: expected " expect " bytes"))
            (and (= blen expect)
                 (some #(bitmap-held? bits %) (range n (* 8 blen))))
            (conj "bits: spare bits must be zero")))
        []))

    :get
    (cond-> []
      (and (= :send mode) (string? (get x "policy"))
           (not (contains? policies (get x "policy"))))
      (conj "policy: not defined in v1"))

    :error
    (cond-> []
      (and (= :send mode) (string? (get x "code"))
           (not (contains? error-codes (get x "code"))))
      (conj "code: not defined in v1"))

    []))

(defn problems
  "Problems of native value `x` as a message of `type`. Empty when valid.
   `mode` is `:send` (the default) or `:receive`; see `semantic-problems`."
  ([type x] (problems type x :send))
  ([type x mode]
   (if-let [sp (get shapes type)]
     (let [base (check sp x "$")]
       (if (seq base) base (semantic-problems type x mode)))
     ["no value shape for this type"])))

(defn error-code
  "The code of a decoded `error` value as a receiver reads it: an unknown
   code is `\"internal\"`."
  [value]
  (let [c (get value "code")]
    (if (contains? error-codes c) c "internal")))

(defn peer-kind
  "The kind of a decoded `hello` value as a receiver reads it: an unknown
   kind is `\"browser\"`, the less preferred."
  [value]
  (let [k (get value "kind")]
    (if (contains? kinds k) k "browser")))

;; =============================================================================
;; The `have` bitmap
;; =============================================================================

(defn bitmap
  "Bytes of a `have` bitmap for a seq of booleans. Hash i is bit
   `0x80 >> (i mod 8)` of byte `i div 8`. Spare bits are zero."
  [held?s]
  (let [held? (vec held?s)
        n (count held?)
        out (vec (repeat (quot (+ n 7) 8) 0))]
    (bin/as-wire-bytes
     (reduce (fn [acc i]
               (if (nth held? i)
                 (update acc (quot i 8) #(bit-or % (bit-shift-right 0x80 (rem i 8))))
                 acc))
             out
             (range n)))))

;; =============================================================================
;; Messages on the wire
;; =============================================================================

(defn- invalid!
  [type ps]
  (throw (ex-info (str "invalid " (name type) " message: " (str/join "; " ps))
                  {:reason :invalid-message :type type :problems ps})))

(defn encode-message
  "Frame bytes for {:type :hello|:have?|... :stream n :value native}.

   Validates the value first. A control message is one frame with FIN."
  [{:keys [type stream value max-frame-bytes]
    :or {stream 0}}]
  (when-not (contains? shapes type)
    (throw (ex-info "not a control message type" {:reason :unknown-type :type type})))
  (let [ps (problems type value :send)]
    (when (seq ps) (invalid! type ps)))
  (frame/encode-frame {:type type
                       :stream stream
                       :flags frame/flag-fin
                       :payload (encode-value value)
                       :max-frame-bytes (or max-frame-bytes frame/max-frame)}))

(defn decode-message
  "Parse and validate one control frame.

   Returns {:type :stream :value}. An unknown type id comes back as
   {:type n :unknown? true :stream s} without a value, so the caller can
   answer `unsupported`. An item-stream frame is not a control message:
   use `dacite.peer.frame/decode-frame` for it."
  ([bs] (decode-message bs frame/max-frame))
  ([bs max-frame-bytes]
   (let [fr (frame/decode-frame bs max-frame-bytes)
         type (:type fr)]
     (cond
       (number? type)
       {:type type :unknown? true :stream (:stream fr)}

       (= :item-stream type)
       (throw (ex-info "item-stream is not a control message" {:reason :wrong-type}))

       :else
       (do
         (when-not (:fin? fr)
           (throw (ex-info "a control message is one frame with FIN"
                           {:reason :not-fin :type type})))
         (when (:partial? fr)
           (throw (ex-info "PARTIAL is for item streams" {:reason :bad-flags})))
         (let [value (try (decode-value (:payload fr))
                          (catch #?(:clj Throwable :cljs :default) e
                            (if (:reason (ex-data e))
                              (throw e)
                              (throw (ex-info (str "bad literal: " (ex-message e))
                                              {:reason :bad-literal :type type})))))
               ps (problems type value :receive)]
           (when (seq ps) (invalid! type ps))
           ;; Canonical form: the bytes must be what the encoder writes.
           (when-not (= (bin/bytes->hex (encode-value value))
                        (bin/bytes->hex (:payload fr)))
             (throw (ex-info "literal is not canonical" {:reason :not-canonical :type type})))
           {:type type :stream (:stream fr) :value value}))))))

(defn answer-error
  "The `error` message a responder sends for a decoded request it will not
   serve, as {:type :error :stream n :value {...}}. Nil when it can serve.

   `msg` is a result of `decode-message`. `ctx` is {:caps #{..} :policies
   #{..}}, the responder's own capabilities and policies (default: all
   of v1). Every answer is non-fatal and on the request's stream.

   - an unknown message type: `unsupported`
   - `have?` without the `have` capability, `get` without `reveal`: `unsupported`
   - `get` with a policy the responder does not know: `unsupported-policy`"
  ([msg] (answer-error msg {}))
  ([{:keys [type stream value unknown?]} {:keys [caps policies]
                                          :or {caps capabilities
                                               policies #{"default"}}}]
   (let [err (fn [code text]
               {:type :error
                :stream stream
                :value {"code" code "message" text "fatal" false}})]
     (cond
       unknown? (err "unsupported" "unknown message type")
       (and (= :have? type) (not (contains? caps "have")))
       (err "unsupported" "have? is not served")
       (and (= :get type) (not (contains? caps "reveal")))
       (err "unsupported" "get is not served")
       (and (= :get type) (not (contains? policies (get value "policy"))))
       (err "unsupported-policy" (str "unknown policy"))))))

;; =============================================================================
;; EDN for fixtures
;; =============================================================================

(defn ->edn
  "Native value → plain EDN (blobs become {::blob hex})."
  [x]
  (cond
    (bytes-value? x) {::blob (bin/bytes->hex x)}
    (hash-ref? x) x
    (map? x) (into {} (map (fn [[k val]] [k (->edn val)])) x)
    (vector? x) (mapv ->edn x)
    (set? x) (into #{} (map ->edn) x)
    :else x))

(defn <-edn
  "Inverse of `->edn`."
  [x]
  (cond
    (and (map? x) (= [::blob] (keys x))) (bin/hex->bytes (::blob x))
    (hash-ref? x) x
    (map? x) (into {} (map (fn [[k val]] [k (<-edn val)])) x)
    (vector? x) (mapv <-edn x)
    (set? x) (into #{} (map <-edn) x)
    :else x))

(defn equal-values?
  "Structural equality of native values (bytes compare by content)."
  [a b]
  (= (->edn a) (->edn b)))
