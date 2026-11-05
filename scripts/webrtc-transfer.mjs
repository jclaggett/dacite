#!/usr/bin/env node
/**
 * M3.2 slice 2: ~5 MB over WebRTC (node-datachannel), named channel dacite-peer.
 *
 * In-process (default):
 *   node scripts/webrtc-transfer.mjs
 *   node scripts/webrtc-transfer.mjs --bytes 5000000 --stun
 *
 * Two terminals, manual signaling via a directory:
 *   mkdir -p /tmp/dacite-sig && rm -f /tmp/dacite-sig/*
 *   node scripts/webrtc-transfer.mjs --role offerer  --dir /tmp/dacite-sig
 *   node scripts/webrtc-transfer.mjs --role answerer --dir /tmp/dacite-sig
 *
 * ICE defaults to host-only. Pass --stun for stun:stun.l.google.com:19302.
 * Requires node-datachannel (prebuilds). If those fail on a platform, werift is
 * the planned fallback (not wired in this script yet).
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const ndc = require('node-datachannel');

const args = process.argv.slice(2);
const opt = (n) => { const i = args.indexOf(n); return i < 0 ? null : (args[i+1] && !args[i+1].startsWith('-') ? args[i+1] : true); };
const role = opt('--role');
const dir = opt('--dir');
const bytes = parseInt(opt('--bytes') || '5000000', 10);
const iceServers = args.includes('--stun') ? ['stun:stun.l.google.com:19302'] : [];
const CHANNEL = 'dacite-peer';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
function payload(n) {
  const b = Buffer.alloc(n);
  for (let i = 0; i < n; i++) b[i] = (i * 7919) % 251;
  return b;
}
function writeJson(f, o) {
  fs.writeFileSync(f + '.tmp', JSON.stringify(o));
  fs.renameSync(f + '.tmp', f);
}
async function waitFile(f, ms = 90000) {
  const t0 = Date.now();
  while (!fs.existsSync(f)) {
    if (Date.now() - t0 > ms) throw new Error('timeout ' + f);
    await sleep(100);
  }
  return JSON.parse(fs.readFileSync(f, 'utf8'));
}

function pairLocal() {
  const p1 = new ndc.PeerConnection('offerer', { iceServers });
  const p2 = new ndc.PeerConnection('answerer', { iceServers });
  p1.onLocalDescription((s, t) => p2.setRemoteDescription(s, t));
  p1.onLocalCandidate((c, m) => p2.addRemoteCandidate(c, m));
  p2.onLocalDescription((s, t) => p1.setRemoteDescription(s, t));
  p2.onLocalCandidate((c, m) => p1.addRemoteCandidate(c, m));
  return [p1, p2];
}

function openDc(pc, offerer) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('dc open timeout')), 20000);
    if (offerer) {
      const dc = pc.createDataChannel(CHANNEL, { ordered: true });
      dc.onOpen(() => { clearTimeout(t); resolve(dc); });
      dc.onError((e) => { clearTimeout(t); reject(new Error(String(e))); });
    } else {
      pc.onDataChannel((dc) => {
        if (dc.getLabel() !== CHANNEL) return;
        const done = () => { clearTimeout(t); resolve(dc); };
        dc.isOpen() ? done() : dc.onOpen(done);
      });
    }
  });
}

function waitForLow(dc) {
  return new Promise((r) => {
    let fired = false;
    const once = () => { if (!fired) { fired = true; r(); } };
    dc.onBufferedAmountLow(once);
    setTimeout(once, 2);
  });
}

async function sendWithBackpressure(dc, data) {
  // 4 KiB frames, 4/1 marks. node-datachannel 0.33.6 corrupted 16 KiB frames
  // when the SCTP buffer was full (hash mismatch). The WebRTC peer hello is
  // clamped to the same 4096; the in-memory transport keeps 16384.
  // sendMessageBinary returning false still queued the frame on 0.33.6:
  // sending it again duplicated bytes and the hash failed. Wait, don't retry.
  const frame = 4096;
  const high = 4 * frame;
  const low = 1 * frame;
  dc.setBufferedAmountLowThreshold(low);
  let paused = 0;
  const maxMsg = dc.maxMessageSize() || 65536;
  for (let off = 0; off < data.length; off += frame) {
    const copy = Buffer.from(data.subarray(off, Math.min(off + frame, data.length)));
    while (dc.bufferedAmount() >= high) {
      paused++;
      await waitForLow(dc);
    }
    if (!dc.sendMessageBinary(copy)) {
      paused++;
      await waitForLow(dc);
    }
  }
  return { paused, frame, maxMsg };
}

function recvAll(dc, expected) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let n = 0;
    const t = setTimeout(() => reject(new Error('recv timeout have=' + n)), 120000);
    dc.onMessage((msg) => {
      const buf = Buffer.from(msg);
      parts.push(buf);
      n += buf.length;
      if (n >= expected) {
        clearTimeout(t);
        resolve(Buffer.concat(parts).subarray(0, expected));
      }
    });
  });
}

async function inProcess() {
  try { ndc.cleanup(); } catch (_) {}
  const [p1, p2] = pairLocal();
  const [dc1, dc2] = await Promise.all([openDc(p1, true), openDc(p2, false)]);
  console.log('open maxMessageSize=%s', dc1.maxMessageSize());
  const data = payload(bytes);
  const want = sha(data);
  console.log('sending %d bytes sha256=%s', bytes, want);
  const recvP = recvAll(dc2, bytes);
  const t0 = Date.now();
  const { paused, frame, maxMsg } = await sendWithBackpressure(dc1, data);
  const got = await recvP;
  console.log('received %d bytes in %dms sha256=%s', got.length, Date.now() - t0, sha(got));
  console.log('flow-control: paused %d times; frame=%d; maxMessageSize=%d; marks 4/1 frames', paused, frame, maxMsg);
  p1.close(); p2.close();
  if (sha(got) !== want) { console.error('HASH MISMATCH'); process.exit(1); }
  console.log('ok');
  process.exit(0);
}

function signalOf(pc) {
  const s = { description: null, candidates: [], complete: false };
  const wait = [];
  const kick = () => { while (wait.length) wait.shift()(); };
  pc.onLocalDescription((sdp, type) => { s.description = { type, sdp }; kick(); });
  pc.onLocalCandidate((candidate, mid) => { s.candidates.push({ candidate, mid }); kick(); });
  pc.onGatheringStateChange((st) => { if (st === 'complete') { s.complete = true; kick(); } });
  return {
    s,
    waitDesc: async () => { while (!s.description) await new Promise((r) => wait.push(r)); },
    waitDone: async () => { while (!s.complete) await new Promise((r) => wait.push(r)); },
    apply: (o) => {
      if (o.description) pc.setRemoteDescription(o.description.sdp, o.description.type);
      for (const c of o.candidates || []) pc.addRemoteCandidate(c.candidate, c.mid);
    },
  };
}

async function manual(roleName) {
  if (!dir) throw new Error('--dir required');
  fs.mkdirSync(dir, { recursive: true });
  const offerF = path.join(dir, 'offer.json');
  const answerF = path.join(dir, 'answer.json');
  const doneF = path.join(dir, 'done.json');
  const pc = new ndc.PeerConnection(roleName, { iceServers });
  const sig = signalOf(pc);

  if (roleName === 'offerer') {
    const dcP = openDc(pc, true);
    await sig.waitDesc();
    const tick = setInterval(() => writeJson(offerF, sig.s), 200);
    await sig.waitDone();
    clearInterval(tick);
    writeJson(offerF, sig.s);
    console.log('offer written; waiting for answer');
    let answer = await waitFile(answerF);
    sig.apply(answer);
    let applied = answer.candidates.length;
    for (let i = 0; i < 50; i++) {
      await sleep(100);
      answer = JSON.parse(fs.readFileSync(answerF, 'utf8'));
      const fresh = (answer.candidates || []).slice(applied);
      for (const c of fresh) pc.addRemoteCandidate(c.candidate, c.mid);
      applied += fresh.length;
      if (answer.complete) break;
    }
    const dc = await dcP;
    console.log('offerer open maxMessageSize=%s', dc.maxMessageSize());
    await waitFile(path.join(dir, 'recv-ready.json'));
    const data = payload(bytes);
    const want = sha(data);
    console.log('sending %d bytes sha256=%s', bytes, want);
    const { paused } = await sendWithBackpressure(dc, data);
    console.log('sent; paused %d; waiting for done', paused);
    const done = await waitFile(doneF, 180000);
    pc.close();
    console.log('peer', done);
    if (done.sha256 !== want) process.exit(1);
    console.log('ok');
    process.exit(0);
  } else {
    console.log('waiting for offer');
    let offer = await waitFile(offerF);
    sig.apply(offer);
    const dcP = openDc(pc, false);
    let offerApplied = (offer.candidates || []).length;
    const poll = setInterval(() => {
      if (!fs.existsSync(offerF)) return;
      const o = JSON.parse(fs.readFileSync(offerF, 'utf8'));
      const fresh = (o.candidates || []).slice(offerApplied);
      for (const c of fresh) pc.addRemoteCandidate(c.candidate, c.mid);
      offerApplied += fresh.length;
    }, 200);
    await sig.waitDesc();
    const tick = setInterval(() => writeJson(answerF, sig.s), 200);
    await sig.waitDone();
    clearInterval(tick);
    clearInterval(poll);
    writeJson(answerF, sig.s);
    writeJson(path.join(dir, 'recv-ready.json'), { ok: true });
    const dc = await dcP;
    console.log('answerer open maxMessageSize=%s', dc.maxMessageSize());
    const got = await recvAll(dc, bytes);
    const gotSha = sha(got);
    console.log('received %d sha256=%s', got.length, gotSha);
    writeJson(doneF, { sha256: gotSha, bytes: got.length });
    pc.close();
    console.log('ok');
    process.exit(0);
  }
}

(role ? manual(role) : inProcess()).catch((e) => { console.error(e); process.exit(1); });
