/**
 * Client for the slice-3 signaling relay (scripts/webrtc-signal-server.mjs).
 * The same JSON shapes are what dacite.peer.transport.webrtc/signal-over-ws!
 * sends. This module is the Node side used by scripts/webrtc-transfer.mjs.
 */
import { WebSocket } from 'ws';

/** ws://host:port plus /room/<token> and an optional ?role=. */
export function roomUrl(url, room, role) {
  const base = new URL(url);
  base.pathname = `/room/${encodeURIComponent(room)}`;
  base.search = '';
  base.hash = '';
  if (role) base.searchParams.set('role', role);
  return base.toString();
}

/**
 * Messages to send so `next` catches up with `prev`. Both are
 * `{ description, candidates, complete }` snapshots from one peer.
 * Candidates already present in `prev` are skipped, so this is safe to
 * call on every local change.
 */
export function diffSignal(prev, next) {
  const out = [];
  const d0 = prev && prev.description;
  const d1 = next && next.description;
  if (d1 && (!d0 || d0.sdp !== d1.sdp || d0.type !== d1.type)) {
    out.push({ type: 'signal', description: { type: d1.type, sdp: d1.sdp } });
  }
  const seen = (prev && prev.candidates ? prev.candidates : []).length;
  const cs = next && next.candidates ? next.candidates : [];
  for (const c of cs.slice(seen)) {
    if (!c || !c.candidate) continue;
    out.push({
      type: 'signal',
      candidate: { candidate: c.candidate, mid: c.mid ?? '' },
    });
  }
  if (next && next.complete && !(prev && prev.complete)) {
    out.push({ type: 'signal', complete: true });
  }
  return out;
}

/**
 * Apply one relayed signal to a node-datachannel PeerConnection.
 * `state` is `{ desc: boolean, queue: candidate[] }` and is updated.
 * A candidate that arrives before the remote description is queued.
 */
export function applyRemote(pc, state, msg) {
  const add = (c) => {
    if (!c || !c.candidate) return;
    if (state.desc) pc.addRemoteCandidate(c.candidate, c.mid ?? '');
    else state.queue.push(c);
  };
  if (msg.description && msg.description.sdp && msg.description.type && !state.desc) {
    pc.setRemoteDescription(msg.description.sdp, msg.description.type);
    state.desc = true;
    const queued = state.queue;
    state.queue = [];
    for (const c of queued) add(c);
  }
  if (msg.candidate) add(msg.candidate);
  if (Array.isArray(msg.candidates)) {
    for (const c of msg.candidates) add(c);
  }
}

/**
 * Join a room. Resolves once the server has assigned a role (`joined`).
 * Signals that arrive in the same burst as `joined` are kept until
 * `onSignal` is set.
 *
 * The returned handle:
 *   role            "offerer" | "answerer"
 *   send(obj)       queue until the socket is open, then send JSON
 *   onSignal(fn)    one relayed message; backlog is delivered on attach
 *   onPeerLeft(fn)  the other peer's socket closed
 *   onError(fn)     a server error after join (the socket then closes)
 *   whenPeerLeft(ms)
 *   close()         promise that resolves when the socket has closed
 */
export function connectSignal({ url, room, role = null, timeoutMs = 20000 }) {
  const ws = new WebSocket(roomUrl(url, room, role), { perMessageDeflate: false });
  let settled = false;
  let open = false;
  let assigned = null;
  let peerLeft = false;
  let closed = false;
  const queue = [];
  const signalBacklog = [];
  const signalHandlers = new Set();
  const leftHandlers = [];
  const closeWaiters = [];
  let errorHandler = null;

  const api = {
    get role() { return assigned; },
    send(obj) {
      const s = JSON.stringify(obj);
      if (open) ws.send(s);
      else queue.push(s);
    },
    onSignal(fn) {
      signalHandlers.add(fn);
      if (signalBacklog.length) {
        const pending = signalBacklog.splice(0);
        for (const m of pending) fn(m);
      }
    },
    onPeerLeft(fn) {
      if (peerLeft) fn();
      else leftHandlers.push(fn);
    },
    onError(fn) { errorHandler = fn; },
    whenPeerLeft(ms = 15000) {
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('timed out waiting for the other peer to leave')), ms);
        api.onPeerLeft(() => { clearTimeout(t); resolve(); });
      });
    },
    close() {
      return new Promise((resolve) => {
        if (closed || ws.readyState === WebSocket.CLOSED) {
          resolve();
          return;
        }
        const t = setTimeout(resolve, 2000);
        closeWaiters.push(() => { clearTimeout(t); resolve(); });
        try { ws.close(); } catch { clearTimeout(t); resolve(); }
      });
    },
  };

  function emitSignal(msg) {
    if (signalHandlers.size === 0) signalBacklog.push(msg);
    else for (const fn of signalHandlers) fn(msg);
  }

  function emitLeft() {
    if (peerLeft) return;
    peerLeft = true;
    for (const fn of leftHandlers.splice(0)) fn();
  }

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        try { ws.close(); } catch { /* ignore */ }
        reject(new Error('signaling join timed out'));
      }
    }, timeoutMs);

    const fail = (err) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(err);
      } else if (errorHandler) {
        errorHandler(err);
      } else {
        console.error(err);
      }
    };

    ws.on('error', (err) => fail(err));
    ws.on('open', () => {
      open = true;
      for (const s of queue.splice(0)) ws.send(s);
    });
    ws.on('close', () => {
      open = false;
      closed = true;
      for (const w of closeWaiters.splice(0)) w();
      // An error frame sent just before close is a separate 'message' event.
      // Give it this turn so the rejection names bad-room / room-full / role-taken.
      if (!settled) {
        setImmediate(() => {
          if (!settled) fail(new Error('signaling socket closed before join'));
        });
      }
    });
    ws.on('message', (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      if (!msg || typeof msg !== 'object') return;
      if (msg.type === 'joined') {
        assigned = msg.role;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(api);
        }
      } else if (msg.type === 'signal') {
        emitSignal(msg);
      } else if (msg.type === 'peer-left') {
        emitLeft();
      } else if (msg.type === 'error') {
        const err = new Error(`${msg.reason}: ${msg.message}`);
        err.reason = msg.reason;
        fail(err);
        try { ws.close(); } catch { /* ignore */ }
      }
    });
  });
}
