(ns dacite.examples.page-token
  "The `/signal` token on a page (docs/design/packaging.md §P1), shared by
   the Value Browser, library, and peer pages.

   `npx dacite` puts `signal`, `room`, and `token` in the URL fragment,
   which is never sent in a request. `take-token!` keeps the token in
   `sessionStorage` (a reload still has it) and strips it from the address
   bar with `history.replaceState`. `token-for` hands it out only for a
   relay on this page's own host (`dacite.examples.browser/same-host?`:
   `localhost`, `127.0.0.1`, and `[::1]` are one host), so it is never sent
   to another relay. The pages send it as the WebSocket subprotocol
   `dacite.token.<token>` (`dacite.peer.transport/signal-protocols`). The
   dev relay (`npm run webrtc:signal`) takes no token, and `signal` and
   `room` in the query still work for it."
  (:require [clojure.string :as str]
            [dacite.examples.browser :as browser]))

(defn non-blank
  "`v` trimmed when it is a non-blank string, else nil. `URLSearchParams.get`
   returns JavaScript null for an absent key."
  [v]
  (when (and (string? v) (pos? (count (str/trim v))))
    (str/trim v)))

(defn fragment-params
  "The URL fragment as `URLSearchParams`."
  []
  (js/URLSearchParams. (subs (or (.-hash js/location) "") 1)))

(defn query-param
  "A non-blank query value, or nil."
  [k]
  (non-blank (.get (js/URLSearchParams. (.-search js/location)) k)))

(defn page-param
  "A non-blank value from the URL fragment, else from the query, or nil."
  [k]
  (or (non-blank (.get (fragment-params) k))
      (query-param k)))

(def token-key
  "The `sessionStorage` key of the `/signal` token."
  "dacite.signal-token")

(defn- session-get [k]
  (try (.getItem js/sessionStorage k) (catch :default _ nil)))

(defn- session-set! [k v]
  (try (.setItem js/sessionStorage k v) (catch :default _ nil)))

(defn take-token!
  "The `/signal` token: from the fragment (kept in `sessionStorage`, then
   removed from the address bar), else the one this tab kept, or nil."
  []
  (let [ps (fragment-params)]
    (when-let [t (non-blank (.get ps "token"))]
      (session-set! token-key t)
      (.delete ps "token")
      (let [rest (.toString ps)]
        (.replaceState js/history nil ""
                       (str (.-pathname js/location) (.-search js/location)
                            (if (pos? (count rest)) (str "#" rest) "")))))
    (non-blank (session-get token-key))))

(defn own-host?
  "Whether the relay at `signal` is on this page's own host and port (the
   `npx dacite` that served it)."
  [signal]
  (browser/same-host? signal (.-href js/location)))

(defn token-for
  "`token` when the relay at `signal` is on this page's own host, else nil."
  [token signal]
  (when (and token (own-host? signal)) token))
