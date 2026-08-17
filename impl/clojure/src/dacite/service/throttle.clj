(ns dacite.service.throttle
  "Inbound HTTP admission for dacite.service.

   Per-client token bucket + inflight caps so one caller cannot starve
   others. Empty bucket is 429 (does not sleep — that would hold a
   handler thread). Orthogonal to dacite.store.rate-limit, which paces
   outbound send-chunk! by blocking.

   Client key: Authorization Bearer token if present, else remote IP.
   A token is only a bucket name, not auth.

   See docs/design/service.md."
  (:require [clojure.string :as str]
            [dacite.store.pack :as pack]
            [dacite.store.rate-limit :as rl]))

(def defaults
  {:max-body-bytes 1048576
   :pack-get-max-budget 65536
   :pack-get-max-starts 32
   :max-threads 32
   :max-sse 16
   :client-inflight 8
   :client-rate 50.0
   :client-burst 100
   :sse-per-client 4})

(defn- default-now-ms []
  (System/currentTimeMillis))

(defn normalize-opts
  "nil/true → defaults; false → nil (disabled); map → merge defaults."
  [opts]
  (cond
    (false? opts) nil
    (or (nil? opts) (true? opts)) defaults
    (map? opts) (merge defaults opts)
    :else (throw (ex-info "throttle opts must be a map, true, false, or nil"
                          {:opts opts}))))

(defn create
  "Build throttle state, or nil when disabled.

   opts — true/nil (defaults), false (off), or a map merged over defaults.
   :now-fn may be injected for tests."
  [opts]
  (when-let [o (normalize-opts opts)]
    {:opts (assoc o :now-fn (or (when (map? opts) (:now-fn opts))
                                default-now-ms))
     :buckets (atom {})
     :inflight (atom {})
     :sse (atom {})
     :global-api (atom 0)
     :global-sse (atom 0)}))

(defn bearer-token
  "Extract the Bearer credential from an Authorization header, or nil."
  [authorization]
  (when (string? authorization)
    (when-let [m (re-matches #"(?i)Bearer\s+(\S+)" (str/trim authorization))]
      (second m))))

(defn client-key
  "Bucket name: bearer:<token> when Authorization is Bearer, else ip:<host>."
  [authorization remote-host]
  (if-let [tok (bearer-token authorization)]
    (str "bearer:" tok)
    (str "ip:" (or remote-host "unknown"))))

(defn- deny
  ([status error]
   {:ok false :status status :error error})
  ([status error retry-after-s]
   {:ok false :status status :error error :retry-after-s retry-after-s}))

(defn- try-inc!
  "Atom of key→count. Increment if current < cap. Returns true when acquired."
  [store key cap]
  (loop []
    (let [m @store
          n (long (get m key 0))]
      (if (>= n (long cap))
        false
        (if (compare-and-set! store m (assoc m key (inc n)))
          true
          (recur))))))

(defn- dec-key!
  [store key]
  (swap! store
         (fn [m]
           (let [n (dec (long (get m key 0)))]
             (if (pos? n)
               (assoc m key n)
               (dissoc m key))))))

(defn- try-inc-atom!
  "Increment a bare counter atom if < cap."
  [a cap]
  (loop []
    (let [n (long @a)]
      (if (>= n (long cap))
        false
        (if (compare-and-set! a n (inc n))
          true
          (recur))))))

(defn- bucket-for
  [th client-key]
  (let [{:keys [client-burst now-fn]} (:opts th)
        buckets (:buckets th)]
    (if-let [existing (get @buckets client-key)]
      existing
      (let [fresh (atom {:tokens (double client-burst)
                         :last-ms (long (now-fn))})]
        (-> (swap! buckets
                   (fn [m]
                     (if (contains? m client-key)
                       m
                       (assoc m client-key fresh))))
            (get client-key))))))

(defn take-request-token!
  "Try one request token for client-key.
   Returns {:ok true} or a 429 deny map."
  [th client-key]
  (let [{:keys [client-burst client-rate now-fn]} (:opts th)
        bucket (bucket-for th client-key)
        result (rl/try-take-tokens! bucket {:capacity client-burst
                                            :rate client-rate
                                            :cost 1
                                            :now-fn now-fn})]
    (if (:ok result)
      {:ok true}
      (let [ms (long (:retry-after-ms result 1000))
            sec (max 1 (long (Math/ceil (/ (double ms) 1000.0))))]
        (deny 429 "rate limited" sec)))))

(defn acquire-api!
  "Admit one API request (not SSE). Caller must release-api! when :ok.

   Order: global thread cap → per-client inflight → token."
  [th client-key]
  (let [{:keys [max-threads client-inflight]} (:opts th)]
    (if-not (try-inc-atom! (:global-api th) max-threads)
      (deny 503 "server busy" 1)
      (if-not (try-inc! (:inflight th) client-key client-inflight)
        (do (swap! (:global-api th) dec)
            (deny 429 "rate limited" 1))
        (let [tok (take-request-token! th client-key)]
          (if (:ok tok)
            {:ok true}
            (do (dec-key! (:inflight th) client-key)
                (swap! (:global-api th) dec)
                tok)))))))

(defn release-api!
  [th client-key]
  (dec-key! (:inflight th) client-key)
  (swap! (:global-api th) #(max 0 (dec (long %))))
  nil)

(defn acquire-sse!
  "Admit one SSE subscriber. No request tokens. Caller must release-sse!."
  [th client-key]
  (let [{:keys [max-sse sse-per-client]} (:opts th)]
    (if-not (try-inc-atom! (:global-sse th) max-sse)
      (deny 503 "server busy" 1)
      (if-not (try-inc! (:sse th) client-key sse-per-client)
        (do (swap! (:global-sse th) dec)
            (deny 429 "rate limited" 1))
        {:ok true}))))

(defn release-sse!
  [th client-key]
  (dec-key! (:sse th) client-key)
  (swap! (:global-sse th) #(max 0 (dec (long %))))
  nil)

(defn content-length-too-large?
  "True when Content-Length header exceeds max-body-bytes."
  [content-length-header max-body-bytes]
  (when (and content-length-header max-body-bytes)
    (try
      (> (Long/parseLong (str/trim (str content-length-header)))
         (long max-body-bytes))
      (catch Exception _ false))))

(defn read-body-limited
  "Read at most max-body-bytes from in.

   Returns byte[] or :too-large. Empty body is a zero-length array."
  ^bytes [^java.io.InputStream in max-body-bytes]
  (let [limit (long max-body-bytes)
        bs (.readNBytes in (int (min Integer/MAX_VALUE (inc limit))))]
    (if (> (alength bs) limit)
      :too-large
      bs)))

(defn body-too-large
  []
  (deny 413 "body too large"))

(defn clamp-pack-get-req
  "Clamp :budget and concatenate :roots/:hashes to the server maxima.

   Always sets :budget so the response reports what was used."
  [req max-budget max-starts]
  (let [max-budget (long max-budget)
        max-starts (long max-starts)
        budget (min (long (or (:budget req) pack/default-budget)) max-budget)
        starts (vec (concat (or (:roots req) []) (or (:hashes req) [])))
        starts' (vec (take max-starts starts))]
    (assoc req
           :budget budget
           :roots starts'
           :hashes [])))
