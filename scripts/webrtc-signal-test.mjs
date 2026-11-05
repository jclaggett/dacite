#!/usr/bin/env node
/**
 * M3.2 slice 3 checks.
 *
 *   npm run webrtc:signal-test
 *
 * 1. Room policy on a loopback relay: queued signals flush to the second
 *    peer, a duplicate role is role-taken, a third peer is room-full, a bad
 *    token is bad-room.
 * 2. diffSignal emits description, candidate, and complete once each.
 * 3. The transfer script still runs in-process and with --dir file signaling
 *    (small payload).
 * 4. Two processes transfer 5_000_000 bytes over WebRTC using only
 *    --ws and --room (no signal directory). SHA-256 must match.
 */
import assert from 'assert';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';
import { startServer } from './webrtc-signal-server.mjs';
import { connectSignal, diffSignal } from './webrtc-signal-client.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const transfer = path.join(root, 'scripts', 'webrtc-transfer.mjs');

function runNode(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, {
      cwd: root,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve(out + err);
      else reject(new Error(`exit ${code}\n${out}${err}`));
    });
  });
}

function hashesOf(text) {
  return [...text.matchAll(/sha256=([0-9a-f]{64})/g)].map((m) => m[1]);
}

async function expectReject(p, reason) {
  try {
    await p;
  } catch (err) {
    assert.ok(
      String(err.message || err).includes(reason),
      `expected ${reason} in ${err.message}`,
    );
    return;
  }
  assert.fail(`expected rejection ${reason}`);
}

async function waitFor(pred, label, ms = 2000) {
  const t0 = Date.now();
  while (!pred()) {
    if (Date.now() - t0 > ms) throw new Error('timeout: ' + label);
    await new Promise((r) => setTimeout(r, 10));
  }
}

function testDiff() {
  const prev = { description: null, candidates: [], complete: false };
  const next = {
    description: { type: 'offer', sdp: 'v=0' },
    candidates: [{ candidate: 'c1', mid: '0' }, { candidate: '', mid: '0' }],
    complete: true,
  };
  assert.deepStrictEqual(diffSignal(prev, next), [
    { type: 'signal', description: { type: 'offer', sdp: 'v=0' } },
    { type: 'signal', candidate: { candidate: 'c1', mid: '0' } },
    { type: 'signal', complete: true },
  ]);
  assert.deepStrictEqual(diffSignal(next, next), []);
  const more = {
    ...next,
    candidates: next.candidates.concat([{ candidate: 'c2', mid: '1' }]),
    complete: true,
  };
  assert.deepStrictEqual(diffSignal(next, more), [
    { type: 'signal', candidate: { candidate: 'c2', mid: '1' } },
  ]);
}

async function testRooms(url) {
  const room = 'queue.room~1';
  const offerer = await connectSignal({ url, room, role: 'offerer' });
  assert.strictEqual(offerer.role, 'offerer');
  offerer.send({ type: 'signal', description: { type: 'offer', sdp: 'queued-sdp' } });
  offerer.send({ type: 'signal', candidate: { candidate: 'queued-cand', mid: '0' } });

  const answerer = await connectSignal({ url, room, role: 'answerer' });
  assert.strictEqual(answerer.role, 'answerer');
  const got = [];
  answerer.onSignal((m) => got.push(m));
  await waitFor(() => got.length >= 2, 'queued signals');
  assert.deepStrictEqual(got.map((m) => m.description?.sdp || m.candidate?.candidate), [
    'queued-sdp',
    'queued-cand',
  ]);

  const roles = 'one-offerer';
  const seated = await connectSignal({ url, room: roles, role: 'offerer' });
  await expectReject(connectSignal({ url, room: roles, role: 'offerer' }), 'role-taken');
  await seated.close();

  await expectReject(connectSignal({ url, room, role: 'offerer' }), 'room-full');
  await expectReject(connectSignal({ url, room, role: 'answerer' }), 'room-full');

  const late = [];
  offerer.onSignal((m) => late.push(m));
  answerer.send({ type: 'signal', description: { type: 'answer', sdp: 'back' } });
  await waitFor(() => late.some((m) => m.description?.sdp === 'back'), 'relay after a rejected joiner');

  await expectReject(connectSignal({ url, room: 'bad token', role: 'offerer' }), 'bad-room');
  await expectReject(connectSignal({ url, room: 'x', role: 'spectator' }), 'bad-role');

  const left = offerer.whenPeerLeft(2000);
  await answerer.close();
  await left;

  const again = await connectSignal({ url, room, role: 'answerer' });
  assert.strictEqual(again.role, 'answerer');
  await again.close();
  await offerer.close();
}

async function testInProcess() {
  const out = await runNode([transfer, '--bytes', '65536']);
  assert.ok(out.includes('ok'), out);
  const hashes = hashesOf(out);
  assert.ok(hashes.length >= 2 && hashes[0] === hashes[1], out);
}

async function testFileSignaling() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-sig-'));
  try {
    const [a, b] = await Promise.all([
      runNode([transfer, '--role', 'offerer', '--dir', dir, '--bytes', '65536']),
      runNode([transfer, '--role', 'answerer', '--dir', dir, '--bytes', '65536']),
    ]);
    assert.ok(a.includes('ok') && b.includes('ok'), a + b);
    const ha = hashesOf(a);
    const hb = hashesOf(b);
    assert.strictEqual(ha[0], hb[0], a + b);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

async function testWsTransfer(url) {
  const room = 'transfer-5mb';
  const bytes = '5000000';
  const [a, b] = await Promise.all([
    runNode([transfer, '--ws', url, '--room', room, '--role', 'offerer', '--bytes', bytes]),
    runNode([transfer, '--ws', url, '--room', room, '--role', 'answerer', '--bytes', bytes]),
  ]);
  assert.ok(a.includes('ok'), a);
  assert.ok(b.includes('ok'), b);
  assert.ok(!a.includes('HASH MISMATCH') && !b.includes('HASH MISMATCH'));
  const ha = hashesOf(a);
  const hb = hashesOf(b);
  assert.ok(ha.length >= 1 && hb.length >= 1, a + b);
  assert.strictEqual(ha[0], hb[0], `sha mismatch\n${a}\n${b}`);
  assert.ok(a.includes(bytes) && b.includes(bytes), a + b);
  console.log('ws 5MB sha256=%s', ha[0]);
}

const server = await startServer({ port: 0, host: '127.0.0.1' });
try {
  testDiff();
  console.log('diffSignal ok');
  await testRooms(server.url);
  console.log('room policy ok', server.url);
  await testInProcess();
  console.log('in-process transfer ok');
  await testFileSignaling();
  console.log('file signaling ok');
  await testWsTransfer(server.url);
  console.log('signal-test ok');
} finally {
  await server.close();
}
