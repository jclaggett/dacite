(ns dacite.store.browser
  "Browser remote IStore. HTTP is fetch.

   Methods return settled promises (`completed` / `rejected`). A fetch
   cache miss still throws `:dacite/suspend` from inside the method,
   before it returns, and `drive` replays the operation.

   Same protocol as docs/design/service.md and dacite.store.remote:
   GET/PUT/HEAD/DELETE /node/{hex}, GET /root, POST /root/cas, POST /nodes.

   GET /node returns a pack chunk (realized literals under the hash, same
   as the JVM remote). s-get applies it into a local pack cache then
   returns the node.

   Wire:
     :binary (default true) — pack GET and POST /nodes use wire-v1 binary
       (application/vnd.dacite.chunk.v1). Novelty / root CAS stay EDN.
     :binary false — EDN packs (legacy demos).

   Value ops call s-get and expect a node. fetch cannot block the tab, so
   a miss throws a suspension. `drive` reads `response.body` as bytes
   arrive, caches that exchange, and runs the op again. The server still
   sends one Content-Length pack; the reader is where a later streamed
   pack applies bytes before the response ends.

   Call store ops from `drive`. A request outside `drive` throws.

   Bandwidth: every store-protocol exchange records request/response body
   sizes via dacite.store.stats. Static /app assets are not counted.

   Prefer (client-cache/wrap (remote-store base) :write-back) for the demo."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.store.pack :as pack]
            [dacite.store.client-cache :as client-cache]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]))

;; Re-export stats API for todo-web and demos
(def empty-stats stats/empty-stats)
(def get-stats stats/get-stats)
(def reset-stats! stats/reset-stats!)
(def stats-diff stats/stats-diff)
(def measure stats/measure)
(def format-bytes stats/format-bytes)
(def format-stats stats/format-stats)
(def format-delta stats/format-delta)

(defn- trim-base [base-url]
  (str/replace base-url #"/$" ""))

(defn- node-url
  [base-url h]
  (str (trim-base base-url) "/node/" (store/hash->hex h)))

(defn- body-byte-len
  "Byte length of a fetch request or response body."
  [body]
  (cond
    (nil? body) 0
    (string? body) (count body)
    (instance? js/Uint8Array body) (.-length body)
    (instance? js/ArrayBuffer body) (.-byteLength body)
    :else (count (str body))))

(def ^:dynamic *http-cache*
  "Atom of completed exchanges for the current `drive`, or nil outside it.
   Retries reuse a response instead of sending it again."
  nil)

(def ^:private max-suspensions
  "Safety cap so a cache-key miss cannot refetch forever."
  10000)

(defn- concat-u8
  "Copy `chunks` (a JS array of Uint8Array) into one Uint8Array."
  [chunks]
  (let [n (loop [i 0 s 0]
            (if (< i (.-length chunks))
              (recur (inc i) (+ s (.-length (aget chunks i))))
              s))
        out (js/Uint8Array. n)]
    (loop [i 0 off 0]
      (when (< i (.-length chunks))
        (let [c (aget chunks i)]
          (.set out c off)
          (recur (inc i) (+ off (.-length c))))))
    out))

(defn- read-response-body
  "Read `response.body` while later bytes are still arriving.

   Each `reader.read()` chunk is appended as it shows up. The promise
   resolves to the full body once the stream ends: a Uint8Array when
   `binary?`, a string otherwise, nil when there is no body. Decoding
   the pack still waits for the last byte; the current server sends one
   Content-Length body."
  [response binary?]
  (let [stream (.-body response)]
    (if (nil? stream)
      (js/Promise.resolve nil)
      (let [reader (.getReader stream)
            chunks #js []]
        (js/Promise.
         (fn [ok err]
           (letfn [(finish []
                     (let [bytes (if (zero? (.-length chunks))
                                   nil
                                   (concat-u8 chunks))]
                       (cond
                         (nil? bytes) (ok nil)
                         binary? (ok bytes)
                         :else (ok (.decode (js/TextDecoder. "utf-8") bytes)))))
                   (pump []
                     (-> (.read reader)
                         (.then (fn [r]
                                  (if (.-done r)
                                    (finish)
                                    (do (.push chunks (.-value r))
                                        (pump)))))
                         (.catch err)))]
             (pump))))))))

(defn- js-headers
  [headers]
  (let [o #js {}]
    (doseq [[k v] headers]
      (aset o (name k) (str v)))
    o))

(defn- fetch-exchange
  "One fetch. Records bandwidth when the body stream ends.
   Resolves to {:status n :body string|Uint8Array|nil}.

   `cache` is `no-store`: GET /root is a mutable pointer, and the demos
   count bytes for this action rather than a previous response."
  [method url body headers binary?]
  (let [init #js {:method method
                  :headers (js-headers headers)
                  :cache "no-store"}]
    (when (some? body)
      (aset init "body" body))
    (-> (js/fetch url init)
        (.then (fn [resp]
                 (-> (read-response-body resp binary?)
                     (.then (fn [resp-body]
                              (stats/record! (stats/classify-url method url)
                                             (body-byte-len body)
                                             (body-byte-len resp-body))
                              {:status (.-status resp)
                               :body resp-body}))))))))

(defn- body-eq
  "True when two request bodies are the same bytes or the same string."
  [a b]
  (cond
    (and (nil? a) (nil? b)) true
    (and (string? a) (string? b)) (= a b)
    (and (instance? js/Uint8Array a) (instance? js/Uint8Array b))
    (and (= (.-length a) (.-length b))
         (loop [i 0]
           (cond
             (= i (.-length a)) true
             (not= (aget a i) (aget b i)) false
             :else (recur (inc i)))))
    :else false))

(defn- headers-key
  [headers]
  (str/join "\n"
            (sort (map (fn [[k v]] (str (name k) ": " v)) headers))))

(defn- cache-get
  [cache method url body headers binary?]
  (let [hk (headers-key headers)]
    (some (fn [entry]
            (when (and (= method (:method entry))
                       (= url (:url entry))
                       (= hk (:headers entry))
                       (= (boolean binary?) (:binary? entry))
                       (body-eq body (:body entry)))
              (:resp entry)))
          @cache)))

(defn- root-url-for-cas
  "GET /root URL for a POST /root/cas URL, or nil."
  [url]
  (when (str/ends-with? url "/root/cas")
    (subs url 0 (- (count url) 4))))

(defn- cache-put!
  "Remember one finished exchange.

   A root CAS drops any cached GET /root. The drive retries the whole
   op, and that GET must not still answer \"no root\" after the CAS."
  [cache method url body headers binary? resp]
  (swap! cache
         (fn [entries]
           (let [drop-root (when (= method "POST") (root-url-for-cas url))
                 entries (if drop-root
                           (filterv (fn [entry]
                                      (not (and (= "GET" (:method entry))
                                                (= drop-root (:url entry)))))
                                    entries)
                           entries)]
             (conj entries {:method method
                            :url url
                            :body body
                            :headers (headers-key headers)
                            :binary? (boolean binary?)
                            :resp resp})))))

(defn- suspend-promise
  [e]
  (when-let [data (ex-data e)]
    (:dacite/suspend data)))

(defn suspended?
  "True if `e` is a fetch suspension thrown inside `drive`."
  [e]
  (boolean (suspend-promise e)))

(defn- http
  "Store-protocol request. Returns {:status :body} from this drive's
   cache, or throws a suspension whose promise resolves after fetch
   reads the body stream. Requires `*http-cache*` (`drive` binds it)."
  ([method url body headers]
   (http method url body headers nil))
  ([method url body headers {:keys [binary-response]}]
   (let [cache *http-cache*]
     (when-not cache
       (throw (ex-info "browser store HTTP requires dacite.store.browser/drive"
                       {:method method :url url})))
     (if-let [hit (cache-get cache method url body headers binary-response)]
       hit
       (let [p (-> (fetch-exchange method url body headers binary-response)
                   (.then (fn [resp]
                            (cache-put! cache method url body headers
                                        binary-response resp)
                            resp)))]
         (throw (ex-info "browser fetch suspended"
                         {:dacite/suspend p})))))))

(defn drive
  "Run `f`, fetching over HTTP without blocking the tab.

   `f` must be synchronous and free of its own try/catch around store
   calls: a cache miss throws, `drive` waits for that response body, then
   calls `f` again. Completed exchanges are reused, so a POST is not sent
   twice. Returns a promise of `f`'s value."
  [f]
  (let [cache (atom [])
        steps (atom 0)]
    (js/Promise.
     (fn [ok err]
       (letfn [(step []
                 (if (>= @steps max-suspensions)
                   (err (ex-info "browser drive exceeded fetch suspensions"
                                 {:suspensions max-suspensions}))
                   (do
                     (swap! steps inc)
                     (try
                       (ok (binding [*http-cache* cache] (f)))
                       (catch :default e
                         (if-let [p (suspend-promise e)]
                           (-> p (.then step) (.catch err))
                           (err e)))))))]
         (step))))))

(defn- edn-body [body]
  (when (and body (pos? (body-byte-len body)))
    (if (string? body)
      (wire/read-edn body)
      ;; ArrayBuffer / Uint8Array EDN (unusual; UTF-8 decode)
      (let [u8 (if (instance? js/Uint8Array body)
                 body
                 (js/Uint8Array. body))
            s (.decode (js/TextDecoder.) u8)]
        (wire/read-edn s)))))

(defn- apply-get-body!
  "Install GET /node body into pack-local. Return node at h."
  [pack-local h body binary?]
  (when (and body (pos? (body-byte-len body)))
    (cond
      (or binary?
          (and (not (string? body)) (bin/dac1-magic? body)))
      (let [chunk (bin/decode-pack-edn body)]
        (pack/apply-chunk! pack-local chunk)
        (store/await (store/s-get pack-local h)))

      :else
      (let [data (edn-body body)]
        (cond
          (pack/chunk? data)
          (do (pack/apply-chunk! pack-local data)
              (store/await (store/s-get pack-local h)))

          (some? data)
          (do (store/await (store/s-put pack-local h data))
              data)

          :else nil)))))

(defrecord BrowserRemoteStore [base-url headers pack-local binary?]
  store/IStore
  (s-get [_ h]
    (if-some [hit (store/await (store/s-get pack-local h))]
      (store/completed hit)
      (let [use-bin? binary?
            hdrs (if use-bin?
                   (assoc headers "Accept" bin/content-type-chunk-v1)
                   (assoc headers "Accept" "application/edn"))
            opts (when use-bin? {:binary-response true})
            {:keys [status body]} (http "GET"
                                        (node-url base-url h)
                                        nil hdrs opts)]
        (cond
          (= 200 status) (store/completed (apply-get-body! pack-local h body use-bin?))
          (= 404 status) (store/completed nil)
          :else (store/unavailable h {:status status})))))

  (s-put [this h value]
    (let [{:keys [status body]} (http "PUT" (node-url base-url h)
                                      (wire/write-edn value)
                                      (assoc headers "Content-Type" "application/edn"))]
      ;; 200 + novelty body (preferred); 204 legacy
      (if (or (= 200 status) (= 204 status))
        (do (store/await (store/s-put pack-local h value))
            (store/completed this))
        (store/unavailable h {:status status :body body}))))

  (s-has? [_ h]
    (if (store/await (store/s-has? pack-local h))
      (store/completed true)
      (let [status (:status (http "HEAD" (node-url base-url h) nil headers))]
        (cond
          (= 200 status) (store/completed true)
          (= 404 status) (store/completed false)
          :else (store/unavailable h {:status status})))))

  (s-delete [this h]
    (let [{:keys [status]} (http "DELETE" (node-url base-url h) nil headers)]
      (if (or (= 204 status) (= 404 status))
        (do (store/await (store/s-delete pack-local h))
            (store/completed this))
        (store/unavailable h {:status status}))))

  (s-snapshot [_] (store/s-snapshot pack-local))
  (s-merge [this m]
    (doseq [[h v] m]
      (store/await (store/s-put this h v)))
    (store/completed this))
  (s-reset [this]
    (store/await (store/s-reset pack-local))
    (store/completed this))

  pack/IChunkTransport
  (send-chunk! [this chunk]
    (let [url (str (trim-base base-url) "/nodes")
          data
          (if binary?
            (let [bs (bin/encode-pack-edn chunk)
                  {:keys [status body]}
                  (http "POST" url bs
                        (assoc headers
                               "Content-Type" bin/content-type-chunk-v1
                               "Accept" "application/edn"))]
              (when-not (= 200 status)
                (throw (ex-info "Browser send-chunk! failed"
                                {:status status :body body})))
              (when (and body (pos? (body-byte-len body)))
                (edn-body body)))
            (let [{:keys [status body]}
                  (http "POST" url (wire/write-edn chunk)
                        (assoc headers "Content-Type" "application/edn"))]
              (when-not (= 200 status)
                (throw (ex-info "Browser send-chunk! failed"
                                {:status status :body body})))
              (when (and body (pos? (body-byte-len body)))
                (edn-body body))))]
      (pack/apply-chunk! pack-local chunk)
      data)))

(defn remote-store
  "HTTP-backed store for the browser. base-url e.g. \"\" (same origin) or full URL.

   Options:
     :headers map
     :binary  true|false (default true — wire-v1 for pack GET/POST)"
  [base-url & [{:keys [headers binary]
                :or {headers {}
                     binary true}}]]
  (->BrowserRemoteStore (or base-url "") headers (store/mem-store)
                        (boolean binary)))

(defn cached-remote-store
  "Remote store with client cache (default :write-back)."
  [base-url & [{:keys [headers policy binary]
                :or {headers {}
                     policy :write-back
                     binary true}}]]
  (client-cache/wrap
   (remote-store base-url {:headers headers :binary binary})
   policy))

(defn- unwrap-remote
  "Peel wrappers for base-url / headers only — not for send-chunk! path."
  [remote]
  (loop [r remote]
    (cond
      (instance? BrowserRemoteStore r) r
      (and (record? r) (contains? r :remote)) (recur (:remote r))
      (and (record? r) (contains? r :inner)) (recur (:inner r))
      (and (record? r) (contains? r :layers)) (recur (last (:layers r)))
      :else r)))

(defn- local-dest [remote]
  (cond
    (client-cache/write-back-store? remote) (:local remote)
    (and (record? remote) (contains? remote :local) (contains? remote :remote)
         (not (instance? BrowserRemoteStore remote)))
    (:local remote)
    :else nil))

(defn fetch-reachable!
  "Bulk pack-fetch (demoted). Prefer s-get pack-fill for interactive use."
  ([remote roots] (fetch-reachable! remote roots nil))
  ([remote roots {:keys [budget have dest]}]
   (let [rs (unwrap-remote remote)
         dest (or dest (local-dest remote) (:pack-local rs) (store/mem-store))
         root-list (cond
                     (nil? roots) []
                     (and (sequential? roots) (string? (first roots)))
                     (mapv store/hex->hash roots)
                     (and (sequential? roots) (vector? (first roots)))
                     (vec (remove nil? roots))
                     :else [roots])
         root-hexes (mapv store/hash->hex root-list)
         have-hexes (mapv store/hash->hex
                          (or have
                              (map (fn [k]
                                     (if (string? k) (store/hex->hash k) k))
                                   (keys (or (store/await (store/s-snapshot dest)) {})))))
         url (str (trim-base (:base-url rs)) "/nodes/get")
         payload {:roots root-hexes
                  :have have-hexes
                  :budget (or budget pack/default-budget)}
         {:keys [status body]} (http "POST" url (wire/write-edn payload)
                                     (assoc (:headers rs)
                                            "Content-Type" "application/edn"))]
     (when-not (= 200 status)
       (throw (ex-info "Browser pack-get failed" {:status status :body body})))
     (let [data (edn-body body)
           chunks (or (:chunks data) [])]
       (doseq [ch chunks]
         (pack/apply-chunk! dest ch))
       (when (client-cache/write-back-store? remote)
         ;; flushed set uses hex keys (CLJS-safe)
         (swap! (:flushed remote)
                into
                (map :hash (mapcat :items chunks))))
       {:dest dest
        :items (:items data 0)
        :chunks (count chunks)
        :covered (:covered data 0)
        :budget (:budget data)}))))

(defn remote-get-root
  "Server root hash vector or nil."
  [remote]
  (let [rs (unwrap-remote remote)
        url (str (trim-base (:base-url rs)) "/root")
        {:keys [status body]} (http "GET" url nil (:headers rs))]
    (when (= 200 status)
      (when-let [hex (:root (edn-body body))]
        (store/hex->hash hex)))))

(defn remote-cas-root!
  "CAS root on server. Returns true on success, false on 409.
   Write-back caches flush reachable nodes before CAS."
  [remote expected new-root]
  (when (client-cache/write-back-store? remote)
    (client-cache/flush-reachable! remote new-root))
  (let [rs (unwrap-remote remote)
        url (str (trim-base (:base-url rs)) "/root/cas")
        payload {:expected (when expected (store/hash->hex expected))
                 :new (store/hash->hex new-root)}
        {:keys [status body]} (http "POST" url (wire/write-edn payload)
                                    (assoc (:headers rs)
                                           "Content-Type" "application/edn"))]
    (cond
      (= 200 status) (true? (:ok (edn-body body)))
      (= 409 status) false
      :else (throw (ex-info "Browser remote CAS failed"
                            {:status status :body body})))))
