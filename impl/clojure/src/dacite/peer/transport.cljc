(ns dacite.peer.transport
  "ITransport: one connection between two peers, many named channels (M3.1).
   Portable (.cljc), no I/O of its own. Docs: docs/plan/peering.md M3.1 and
   docs/spec/peer-v1.md section 5.

   A **connection** (`ITransport`) is what one WebRTC handshake gives: one
   `RTCPeerConnection`. It opens **named channels**, each a reliable, ordered
   pipe of whole messages (bytes):

   - `\"dacite-peer\"`: the peer protocol (peer-v1). Every frame, every stream
     of the session, goes on this one channel; the stream id in the frame
     envelope tells them apart.
   - `\"dacite-roots\"`: the roots layer (later; see docs/design/roots.md). The
     peering code never sees a roots message, and roots never sees a peer frame.

   A **channel** (`IChannel`) has `send!`, a message callback (`on-message!`),
   `buffered-amount` and `close!`, plus `on-close!`, `max-message-size` and
   `max-frame-limit`.

   Rules every transport keeps (the in-memory one, `dacite.peer.transport.mem`,
   and later WebRTC):

   - **Messages are opaque bytes.** The transport never parses a frame.
   - **No splitting.** A message larger than the channel's `max-message-size`
     is refused by `send!` (it throws `:message-too-large`). The peer keeps
     under it: the `hello` a side sends carries a `max-frame` clamped to the
     channel's size (`msg/clamp-hello`), then to `max-frame-limit` when the
     transport sets one (WebRTC, 4096). The in-memory transport sets none.
   - **Order.** Messages on one channel arrive in the order sent.
   - **Back-pressure is per channel.** `buffered-amount` is the bytes sent and
     not yet delivered. The responder's 4-frame high and 1-frame low marks
     (spec 12.5, C17) read it.
   - **Close.** `close!` on a channel ends that channel on both sides, and only
     that layer's session. `close-connection!` closes every channel and then
     the connection; both end every session on them, like a protocol violation."
  )

(def peer-channel
  "The channel label of the peer protocol."
  "dacite-peer")

(def roots-channel
  "The channel label of the roots layer (reserved; not built yet)."
  "dacite-roots")

(defprotocol IChannel
  (channel-name [ch] "The label this channel was opened with.")
  (send! [ch bs]
    "Send one message (bytes). Throws `:message-too-large` over `max-message-size`
     and `:channel-closed` on a closed channel. Never blocks; what the other side
     has not taken counts in `buffered-amount`.")
  (on-message! [ch f]
    "Call `(f bytes)` for each message that arrives. Messages that arrived before
     `f` was set are delivered when it is.")
  (on-close! [ch f]
    "Call `(f)` once when the channel is closed, by either side or by its
     connection closing.")
  (buffered-amount [ch]
    "Bytes `send!` has taken that the other side has not yet received.")
  (max-message-size [ch]
    "The largest message this channel carries, in bytes.")
  (max-frame-limit [ch]
    "An extra upper bound on the `max-frame` advertised in `hello`, or nil.
     Independent of `max-message-size`: the channel may carry a larger
     message, but the hello asks for frames no bigger than this. The
     node-datachannel adapter returns 4096 (`frame/webrtc-max-frame`, a
     0.33.6 limit). The browser `RTCPeerConnection` adapter returns nil, so
     the only cut is `max-message-size`. The in-memory transport returns
     nil too, and its hello keeps 16384.")
  (channel-open? [ch] "True until the channel is closed.")
  (close! [ch] "Close the channel, for both sides."))

(defprotocol ITransport
  (open-channel! [conn name]
    "Open (negotiate) the channel `name` and return this side's end of it. The
     other side learns of it through `on-channel!`; when it also calls
     `open-channel!` with the same name (a channel negotiated up front, as WebRTC
     `negotiated: true`) both get the same channel.")
  (on-channel! [conn name f]
    "Call `(f channel)` when the other side opens the channel `name`. One handler
     per name; a channel opened before the handler was set is handed over when it is.")
  (on-connection-close! [conn f]
    "Call `(f)` once when the connection is closed, from either side.")
  (connection-open? [conn] "True until the connection is closed.")
  (close-connection! [conn] "Close every channel, then the connection."))
