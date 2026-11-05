(ns dacite.peer.link
  "Wire a peer client or responder to a `dacite-peer` channel (M3.1). Portable
   (.cljc). The client and the responder are sans I/O: they take `:send!` and
   `:close!` and are handed each frame. This namespace supplies those over an
   `ITransport` (`dacite.peer.transport`), and nothing else: the peer code is the
   same whether the channel is in memory or WebRTC.

   A `links` atom maps peer id -> the session's channel. Make one, pass its
   `client-opts` or `responder-opts` to the client or responder, then attach
   each connection:

     (let [links (link/links)
           c (client/client (merge {:store st} (link/client-opts links)))]
       (link/open-client-channel! links c \"b\" conn))   ; the offerer opens the channel

   For the answering side, `serve!` waits for the channel (`on-channel!`).

   What this does, per channel:

   - **Only `dacite-peer`.** The peer code sees frames of this channel and no
     other. Another channel (`dacite-roots`) on the same connection is not its
     business and never reaches it.
   - **`max-frame` is clamped** to the channel's `max-message-size` in the
     `hello` this side sends (`msg/clamp-hello`), so no frame ever needs
     splitting at the transport. A channel under the smallest legal frame
     throws `:channel-too-small`.
   - **Back-pressure per channel.** The responder's `:buffered` is the channel's
     `buffered-amount`, for every stream on it (C17 marks are per channel).
   - **Close.** A protocol violation or a fatal error makes the peer call
     `:close!`, which closes this channel (the other layers on the connection go
     on). A channel close, or its connection closing, ends the session on both
     sides the way a fatal error does: the peer code's `close-session!`, nothing
     sent.

   One client talks to one responder over a channel. A node that is both (one
   connection carrying a session each way) is later work."
  (:require [dacite.peer.client :as client]
            [dacite.peer.responder :as responder]
            [dacite.peer.msg :as msg]
            [dacite.peer.transport :as t]))

(defn links
  "An empty set of links: peer id -> channel."
  []
  (atom {}))

(defn- channel-of [links peer]
  (or (get @links peer)
      (throw (ex-info "no channel for this peer" {:reason :no-channel :peer peer}))))

(defn client-opts
  "Client options (`:send!`, `:close!`) that write to `links`' channels. Messages
   are encoded with `msg/encode-message`."
  [links]
  {:send! (fn [peer m] (t/send! (channel-of links peer) (msg/encode-message m)))
   :close! (fn [peer] (when-let [ch (get @links peer)] (t/close! ch)))})

(defn responder-opts
  "Responder options (`:send!`, `:close!`, `:buffered`) over `links`' channels.
   `:buffered` is the channel's `buffered-amount`, whichever stream asks."
  [links]
  {:send! (fn [peer bs] (t/send! (channel-of links peer) bs))
   :close! (fn [peer] (when-let [ch (get @links peer)] (t/close! ch)))
   :buffered (fn [peer _stream]
               (if-let [ch (get @links peer)] (t/buffered-amount ch) 0))})

(defn- bind!
  "Register `ch` for `peer`, call `(open!)` (the session opens and sends its
   `hello`), then route the channel's messages and its close. The session must be
   open first: messages that arrived before this side listened are handed over as
   soon as `on-message!` is set, and a frame for a session that is not open is
   ignored."
  [links peer ch open! on-message on-closed]
  (swap! links assoc peer ch)
  (open!)
  (t/on-close! ch (fn [] (on-closed)
                    (swap! links (fn [m] (if (identical? ch (get m peer)) (dissoc m peer) m)))))
  (t/on-message! ch on-message))

(defn attach-client!
  "Run `client`'s session with `peer` over channel `ch`: send this side's `hello`
   (clamped to the channel), route frames in, and let a close end the session.
   `role` is `:offerer` (default) or `:answerer`."
  ([links c peer ch] (attach-client! links c peer ch :offerer))
  ([links c peer ch role]
   (bind! links peer ch
          #(client/open-session! c peer role {:max-message-size (t/max-message-size ch)})
          (fn [bs] (client/on-frame! c peer bs))
          (fn [] (client/close-session! c peer)))
   ch))

(defn attach-responder!
  "Serve `peer` over channel `ch`: send this side's `hello` (clamped to the
   channel), route frames in, and let a close end the session."
  [links r peer ch]
  (bind! links peer ch
         #(responder/open-session! r peer {:max-message-size (t/max-message-size ch)})
         (fn [bs] (responder/on-frame! r peer bs))
         (fn [] (responder/close-session! r peer)))
  ch)

(defn open-client-channel!
  "The client side of `conn`: open the `dacite-peer` channel and attach."
  ([links c peer conn] (open-client-channel! links c peer conn :offerer))
  ([links c peer conn role]
   (attach-client! links c peer (t/open-channel! conn t/peer-channel) role)))

(defn serve!
  "The serving side of `conn`: when the other side opens `dacite-peer`, attach
   `responder` to it as `peer`. (The channel is handed over at once if it was
   already opened.)"
  [links r peer conn]
  (t/on-channel! conn t/peer-channel #(attach-responder! links r peer %)))
