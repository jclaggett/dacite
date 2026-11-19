(ns dacite.examples.browser
  "Parse the Value Browser URI (M6.4).

   The operator names a peer and a value. The peer is `dacite://<host>`.
   The value is `urn:dacite:<hex>`, a Dacite digest. The URN has no
   algorithm token. The pasteable form puts that URN in the fragment:

       dacite://<host>#urn:dacite:<hex>

   The same field also accepts `dacite://<host>` together with the URN,
   separated by whitespace, and `parse-value-uri` accepts the URN as its
   own argument when the field has no fragment.

   The host is a name. Nothing here resolves it, looks it up, or builds
   an HTTP path from the fragment. `:http-path` on a success is nil."
  (:require [clojure.string :as str]))

(def urn-prefix
  "The content name of a Dacite digest. No algorithm token."
  "urn:dacite:")

(def ^:private host-re
  #"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62})")

(def ^:private urn-re
  #"urn:dacite:([0-9a-f]{64})")

(defn- blank-text
  "Trim `s`. Nil is empty on every host."
  [s]
  (str/trim (or s #?(:clj "" :cljs ""))))

(defn- fail [reason]
  {:error reason})

(defn- pieces
  [text]
  (->> (str/split text #"\s+")
       (remove str/blank?)
       vec))

(defn- algorithm-token?
  "True when `s` puts an algorithm name in a URN (`urn:dacite:sha256:…`
   or `urn:sha256:…`). `urn:dacite:<hex>` names the digest and nothing else."
  [s]
  (boolean (or (re-find #"(?i)urn:[a-z0-9][a-z0-9.+-]*:[a-z][a-z0-9.+-]*:"
                        (or s ""))
               (re-find #"(?i)urn:(?:sha-?256|sha-?512|blake[0-9]*):"
                        (or s "")))))

(defn- parse-urn
  "The 64 hex chars of `urn:dacite:<hex>`, or nil."
  [s]
  (let [m (re-matches urn-re (blank-text s))]
    (when m (second m))))

(defn- read-urn
  "A URN beside the peer, or nil when `s` is blank. An error map when
   it is not `urn:dacite:<hex>`."
  [s]
  (let [s (blank-text s)]
    (cond
      (str/blank? s) nil
      (algorithm-token? s) (fail :algorithm)
      :else (if-let [hex (parse-urn s)]
              {:hash hex :fragment (str urn-prefix hex) :from :urn}
              (fail :bad-urn)))))

(defn- parse-authority
  "The host name. A path, a port, a user, or a query is not a name."
  [authority]
  (cond
    (str/blank? authority) (fail :bad-host)
    (str/includes? authority "/") (fail :has-path)
    (str/includes? authority "@") (fail :has-user)
    (str/includes? authority ":") (fail :has-port)
    (str/includes? authority "?") (fail :has-query)
    (re-matches host-re authority) {:host authority}
    :else (fail :bad-host)))

(defn- parse-dacite
  "One `dacite://` token, with or without a fragment."
  [token]
  (cond
    (str/blank? token) (fail :empty)
    (not (str/starts-with? token "dacite://")) (fail :not-dacite)
    :else
    (let [body (subs token (count "dacite://"))
          cut (or (str/index-of body "#") (count body))
          before (subs body 0 cut)
          frag (when (str/index-of body "#") (subs body (inc cut)))
          host (parse-authority before)]
      (cond
        (str/includes? before "?") (fail :has-query)
        (:error host) host
        (nil? frag) {:host (:host host)}
        (str/blank? frag) {:host (:host host)}
        (algorithm-token? frag) (fail :algorithm)
        :else
        (if-let [hex (parse-urn frag)]
          {:host (:host host)
           :hash hex
           :fragment frag
           :from :fragment}
          (fail :bad-urn))))))

(defn- prefer-urn
  "The inline URN and the separate field. Both blank is nil. They must
   name the same digest."
  [inline field]
  (let [a (read-urn inline)
        b (read-urn field)]
    (cond
      (:error a) a
      (:error b) b
      (and (:hash a) (:hash b) (not= (:hash a) (:hash b))) (fail :mismatch)
      :else (or a b))))

(defn- finish
  [parsed extra]
  (cond
    (:error parsed) parsed
    (:error extra) extra
    (str/blank? (:host parsed)) (fail :bad-host)
    (and (:hash parsed) (:hash extra) (not= (:hash parsed) (:hash extra)))
    (fail :mismatch)
    :else
    (let [from-fragment? (some? (:hash parsed))
          hex (or (:hash parsed) (:hash extra))
          fragment (if from-fragment? (:fragment parsed) (:fragment extra))]
      {:host (:host parsed)
       :hash hex
       :fragment fragment
       :from (cond from-fragment? :fragment hex :urn :else nil)
       :http-path nil})))

(defn parse-value-uri
  "Parse the peer field `text` and an optional separate value URN.

   Returns `{:host :hash :fragment :from :http-path}` or `{:error reason}`.

   `:host` is the name from `dacite://<host>`. `:hash` is the 64 hex
   chars of the digest, or nil when the operator named only a peer.
   `:fragment` is `urn:dacite:<hex>` as pasted. `:from` is `:fragment`
   when that URN was the URI fragment, or `:urn` when it was written
   beside the peer. `:http-path` is nil: the fragment is not a path.

   `text` may be the pasteable form, or `dacite://<host>` with the URN
   after whitespace. `urn` is used when `text` has no fragment."
  [text urn]
  (let [text (blank-text text)
        urn (blank-text urn)
        parts (pieces text)]
    (cond
      (and (str/blank? text) (str/blank? urn)) (fail :empty)
      (> (count parts) 2) (fail :bad-urn)
      (and (str/blank? text) (seq urn)) (fail :not-dacite)
      :else
      (finish (parse-dacite (first parts))
              (prefer-urn (second parts) urn)))))

;; =============================================================================
;; Own host (the `/signal` token goes only to the relay that served the page)
;; =============================================================================

(def ^:private url-authority-re
  #"^([A-Za-z][A-Za-z0-9+.-]*)://(?:[^@/?#]*@)?(\[[^\]/?#]*\]|[^:/?#]*)(?::([0-9]*))?(?:[/?#].*)?$")

(def ^:private default-ports
  {"http" 80 "ws" 80 "https" 443 "wss" 443})

(def ^:private loopback-names
  "Names of this computer's loopback interface, one host for `same-host?`."
  #{"localhost" "127.0.0.1" "[::1]"})

(defn url-host
  "{:host :port} of an absolute URL: the host lower-cased (an IPv6 literal
   keeps its brackets; a loopback name is `:loopback`) and the port as an
   integer (the scheme's default when absent). Nil when `url` is not an
   absolute URL with a host, or the scheme has no default port and none
   is given."
  [url]
  (when-let [[_ scheme host port] (and (string? url) (re-matches url-authority-re (str/trim url)))]
    (let [h (str/lower-case host)
          p (if (seq port)
              #?(:clj (Long/parseLong port) :cljs (js/parseInt port 10))
              (get default-ports (str/lower-case scheme)))]
      (when (and (seq h) p)
        {:host (if (loopback-names h) :loopback h) :port p}))))

(defn same-host?
  "Whether URLs `a` and `b` name the same host and port. `localhost`,
   `127.0.0.1`, and `[::1]` are one host (this computer's loopback), so a
   page opened as `http://localhost:8790/` and a relay at
   `ws://127.0.0.1:8790/signal` match. The scheme only picks the default
   port (http and ws 80, https and wss 443). False when either URL does
   not parse."
  [a b]
  (let [x (url-host a)
        y (url-host b)]
    (boolean (and x y (= x y)))))
