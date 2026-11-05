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
  (:require [clojure.set]
            [clojure.string :as str]
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

(def max-int
  "Largest integer a v1 message holds: 2^53 - 1. nbb has one number type, a
   double, which is exact only up to there. The JVM sender and receiver keep
   the same range, so a value one host cannot read is one the other will not
   write. Outside it is `profile`."
  9007199254740991)

(defn- int-value?
  [x]
  #?(:clj (or (instance? Long x) (instance? Integer x)
              (instance? Short x) (instance? Byte x))
     :cljs (and (number? x) (js/Number.isInteger x))))

(defn- profile-error!
  [msg data]
  (throw (ex-info msg (assoc data :reason :profile))))

(defn- in-range?
  [x]
  (<= (- max-int) x max-int))

(defn- ->value
  "Native → Dacite value in `st`."
  [st x]
  (cond
    (nil? x) (v/null st)
    (boolean? x) (v/bool st x)
    (string? x) (v/string st x)
    (int-value? x) (do (when-not (in-range? x)
                         (profile-error! "an integer is within +-(2^53 - 1)" {:value x}))
                       (v/i64 st x))
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
    "i64" (let [n #?(:clj (long body) :cljs body)]
            (when-not (in-range? n)
              (profile-error! "an integer is within +-(2^53 - 1)" {:value (str n)}))
            n)
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
  "Canonical wire-v1 `Lit` bytes of a native value.

   Capped at the source: more than `:max-bytes` (default one frame payload,
   `frame/max-payload`) throws {:reason :too-large}. A control message is
   one frame, so this fails here and not later in `encode-frame`."
  ([x] (encode-value x {}))
  ([x {:keys [max-bytes] :or {max-bytes frame/max-payload}}]
   (let [st (store/mem-store)
         val (->value st x)
         form (pack/literal-of st (v/hash val))
         bs (bin/encode-lit-bytes form)]
     (when (> (bin/byte-len bs) (long max-bytes))
       (throw (ex-info "control value is over one frame payload"
                       {:reason :too-large :size (bin/byte-len bs) :max max-bytes})))
     bs)))

(defn decode-value
  "Native value of wire-v1 `Lit` bytes. Rejects anything outside the profile."
  [bs]
  (form->native (bin/decode-lit-bytes bs)))

;; =============================================================================
;; Shapes
;; =============================================================================

(def max-hashes
  "Hashes in one `ack` or `forget`. 256 hashes are 8 KiB."
  256)

(def error-codes
  #{"unsupported" "unsupported-version" "unsupported-policy" "malformed"
    "unknown-hash" "bad-entry" "too-large" "rate-limited" "overloaded"
    "cancelled" "internal"})

(def capabilities
  "Capabilities v1 defines. A peer ignores capabilities it does not know."
  #{"reveal"})

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
  "Hashes an `ack` or `forget` can hold when frames are at most
   `max-frame-bytes`: 32 bytes each, after 33 bytes of header and map
   framing and 320 bytes kept for a `forget` reason. At 16 KiB that is
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
                          "max-streams" :pos}
                    #{"max-frame" "max-item" "max-streams"}]
          "rates" [:map {"send-max" :pos "recv-max" :pos
                         "req-rate" :pos "req-burst" :pos}
                   #{"send-max" "recv-max" "req-rate" "req-burst"}]}
    #{"versions" "kind" "caps" "limits" "rates"}]

   :get
   [:map {"hash" :hash
          "policy" :string
          "recv-max" :pos
          "interest" [:map {"peak" :pos "tau-ms" :pos "floor" :nat}
                      #{"peak" "tau-ms" "floor"}]}
    #{"hash" "policy" "recv-max"}]

   :ack
   [:map {"hashes" [:vec :hash]} #{"hashes"}]

   :forget
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
                (host/lone-surrogate-index x)
                [(str p ": not valid Unicode (a lone surrogate at UTF-16 index "
                      (host/lone-surrogate-index x) ")")]
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
             (not (<= frame/webrtc-max-frame (get lim "max-frame") frame/max-frame)))
        (conj (str "limits.max-frame: must be " frame/webrtc-max-frame
                   ".." frame/max-frame))))

    (:ack :forget)
    (cond-> []
      (and (vector? (get x "hashes")) (not (<= 1 (count (get x "hashes")) max-hashes)))
      (conj "hashes: 1..256 required"))

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
;; Free text and negotiation
;; =============================================================================

(defn- code-points
  "The characters of `s` as strings, a surrogate pair kept whole."
  [s]
  #?(:clj (map #(String. (Character/toChars %)) (.toArray (.codePoints ^String s)))
     :cljs (js->clj (js/Array.from s))))

(defn- repair-text
  "`s` with each lone surrogate replaced by U+FFFD. Free text is for humans, so
   a sender repairs it (a string value of Dacite refuses such text, issue #16)
   rather than throw while it reports another error."
  [s]
  (loop [s s]
    (if-let [i (host/lone-surrogate-index s)]
      (recur (str (subs s 0 i) "\uFFFD" (subs s (inc i))))
      s)))

(defn cut-text
  "`s` cut to at most `max-string-bytes` UTF-8 bytes, at a character
   boundary. Free text (`agent`, `reason`, `message`) is cut by a sender,
   never rejected there: cutting loses detail nobody parses, and a receiver
   would refuse the whole message otherwise. A lone surrogate in it is
   replaced by U+FFFD (it has no UTF-8 form)."
  [s0]
  (let [s (repair-text s0)]
    (if (<= (utf8-length s) max-string-bytes)
      s
      (loop [cs (code-points s) n 0 out []]
        (let [c (first cs)
              m (when c (utf8-length c))]
          (if (and c (<= (+ n m) max-string-bytes))
            (recur (next cs) (+ n m) (conj out c))
            (apply str out)))))))

(def ^:private free-text
  "The free-text keys of each message type."
  {:hello ["agent"] :cancel ["reason"] :forget ["reason"] :error ["message"]})

(defn cut-free-text
  "`value` with its free-text fields cut by `cut-text`."
  [type value]
  (reduce (fn [m k] (if (string? (get m k)) (update m k cut-text) m))
          value
          (get free-text type)))

(defn negotiate
  "Combine two `hello` values (`mine`, `theirs`), as decoded.

   Returns {:ok {...}} or {:error <error message>}.

   - the version is the highest in both `versions` sets; with none in
     common the answer is `error unsupported-version`, `fatal`, on stream 0
   - `max-frame` and `max-item` are the smaller of the two (frames and items
     in both directions must fit the weaker side)
   - `max-streams` is per direction: `:may-open` is what the other side
     accepts from me, `:will-accept` is what I accept from it
   - capabilities are the intersection
   - `:frame-literal-cap` is the largest literal item either side may send:
     `max-frame - 6`"
  [mine theirs]
  (let [common (clojure.set/intersection (set (get mine "versions"))
                                         (set (get theirs "versions")))]
    (if (empty? common)
      {:error {:type :error :stream 0
               :value {"code" "unsupported-version"
                       "message" "no version in common"
                       "fatal" true}}}
      (let [lim-m (get mine "limits") lim-t (get theirs "limits")
            max-frame (min (get lim-m "max-frame") (get lim-t "max-frame"))]
        {:ok {:version (apply max common)
              :caps (clojure.set/intersection (set (get mine "caps"))
                                              (set (get theirs "caps")))
              :max-frame max-frame
              :max-item (min (get lim-m "max-item") (get lim-t "max-item"))
              :may-open (get lim-t "max-streams")
              :will-accept (get lim-m "max-streams")
              :frame-literal-cap (frame/max-literal-item max-frame)}}))))

(defn clamp-hello
  "`hello` with its `max-frame` cut to `max-message-size`, the largest message a
   transport channel carries (peer-v1 section 5): a frame then never needs
   splitting below the peer. A channel that cannot carry the smallest frame
   that holds a node item (`frame/min-max-frame`) cannot carry a session: it
   throws `:channel-too-small`. The WebRTC library clamp below that minimum
   is `hello-for-channel`'s `:max-frame-limit`, not this check."
  [hello max-message-size]
  (when (< max-message-size frame/min-max-frame)
    (throw (ex-info "the channel's largest message is under the smallest max-frame"
                    {:reason :channel-too-small
                     :max-message-size max-message-size
                     :min-max-frame frame/min-max-frame})))
  (update-in hello ["limits" "max-frame"] #(min % max-message-size)))

(defn hello-for-channel
  "The `hello` to send on a channel.

   `:max-message-size` is `clamp-hello`: cut `max-frame` to what the channel
   carries, and throw `:channel-too-small` under `frame/min-max-frame`.
   `:max-frame-limit`, when set, cuts again. The WebRTC transport passes
   `frame/webrtc-max-frame` (4096). That is under the minimum above: the
   channel can carry 8192, but node-datachannel 0.33.6 corrupts a 16 KiB
   frame on a full SCTP buffer, so the hello asks for 4096. A nil key leaves
   the hello alone. The in-memory transport sets neither extra limit and
   keeps 16384."
  [hello {:keys [max-message-size max-frame-limit]}]
  (cond-> hello
    max-message-size (clamp-hello max-message-size)
    max-frame-limit (update-in ["limits" "max-frame"] #(min % max-frame-limit))))

;; =============================================================================
;; Messages on the wire
;; =============================================================================

(defn- invalid!
  [type ps]
  (throw (ex-info (str "invalid " (name type) " message: " (str/join "; " ps))
                  {:reason :invalid-message :type type :problems ps})))

(defn encode-message
  "Frame bytes for {:type :hello|:get|... :stream n :value native}.

   Validates the value first. A control message is one frame with FIN."
  [{:keys [type stream value max-frame-bytes]
    :or {stream 0}}]
  (when-not (contains? shapes type)
    (throw (ex-info "not a control message type" {:reason :unknown-type :type type})))
  (let [value (cut-free-text type value)
        ps (problems type value :send)]
    (when (seq ps) (invalid! type ps))
    (frame/encode-frame {:type type
                         :stream stream
                         :flags frame/flag-fin
                         :payload (encode-value
                                   value
                                   {:max-bytes (- (long (or max-frame-bytes frame/max-frame))
                                                  frame/header-bytes)})
                         :max-frame-bytes (or max-frame-bytes frame/max-frame)})))

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
   - `get` without the `reveal` capability: `unsupported`
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
