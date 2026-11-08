#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium launch plus a WebRTC handshake
 * with the nbb peer. Browser startup and ICE are outside the 3 second
 * bound. The value itself is a small vector, map, and set.
 *
 * M5.2: the browser reads a value held only by the nbb peer.
 * The origin answers GET /node/{root} with 404. Every reachable entry
 * is verified in the page. Chromium is launched with mDNS candidate
 * hiding disabled so both sides see a literal host address.
 *
 * Safari and Firefox are not executed here. The one-time hand check is
 * in docs/plan/peering.md under M5.2.
 *
 * Requires `npm run peer:build` first (the page bundle).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { startServer } from './webrtc-signal-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = path.join(root, 'examples', 'web');
const bundle = path.join(webRoot, 'peer', 'js', 'main.js');
const PROBE_FRAMES = 16;
const FRAME = 4096;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function contentType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  return 'application/octet-stream';
}

function startStatic() {
  const server = http.createServer((req, res) => {
    const u = new URL(req.url || '/', 'http://127.0.0.1');
    if (u.pathname === '/root' || u.pathname.startsWith('/node/')) {
      res.writeHead(404, { 'content-type': 'text/plain', 'cache-control': 'no-store' });
      res.end('not held');
      return;
    }
    if (!u.pathname.startsWith('/app/')) {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not found');
      return;
    }
    let rel = decodeURIComponent(u.pathname.slice('/app/'.length));
    if (rel.endsWith('/')) rel += 'index.html';
    const file = path.normalize(path.join(webRoot, rel));
    if (file !== webRoot && !file.startsWith(webRoot + path.sep)) {
      res.writeHead(403, { 'content-type': 'text/plain' });
      res.end('forbidden');
      return;
    }
    fs.readFile(file, (err, buf) => {
      if (err) {
        res.writeHead(404, { 'content-type': 'text/plain' });
        res.end('not found');
        return;
      }
      res.writeHead(200, {
        'content-type': contentType(file),
        'cache-control': 'no-store',
      });
      res.end(buf);
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      resolve({
        port: addr.port,
        close: () => new Promise((res, rej) => server.close((e) => (e ? rej(e) : res()))),
      });
    });
  });
}

function startNbb(wsUrl, room, page) {
  const child = spawn(
    path.join(root, 'node_modules', '.bin', 'nbb'),
    ['-m', 'dacite.examples.peer-nbb', '--ws', wsUrl, '--room', room, '--page', page],
    { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = { out: '', err: '' };
  child.stdout.on('data', (d) => {
    log.out += d.toString();
    process.stdout.write(d);
  });
  child.stderr.on('data', (d) => {
    log.err += d.toString();
    process.stderr.write(d);
  });
  return { child, log };
}

async function waitMatch(log, re, ms, label) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const m = log.out.match(re);
    if (m) return m;
    if (log.child && log.child.exitCode != null) break;
    await sleep(50);
  }
  throw new Error(
    'timed out waiting for ' + label + '\n--- stdout ---\n' + log.out + '\n--- stderr ---\n' + log.err,
  );
}

function line(log, re) {
  const m = log.out.match(re);
  if (!m) throw new Error('missing ' + re + '\n' + log.out);
  return m;
}

async function main() {
  if (!fs.existsSync(bundle)) {
    throw new Error('missing ' + bundle + ' — run npm run peer:build');
  }
  const { chromium } = await import('playwright');
  const signal = await startServer({ port: 0, host: '127.0.0.1' });
  const site = await startStatic();
  const room = 'm52-' + Math.random().toString(16).slice(2);
  const pageBase = `http://127.0.0.1:${site.port}/app/peer/`;
  const nbb = startNbb(signal.url, room, pageBase);
  let browser;
  try {
    const rootM = await waitMatch(nbb.log, /^root ([0-9a-f]{64})$/m, 20000, 'root');
    const entriesM = await waitMatch(nbb.log, /^entries (\d+)$/m, 5000, 'entries');
    const rootHex = rootM[1];
    const entries = Number(entriesM[1]);
    if (!(entries > 1)) throw new Error('nbb value should be more than one entry, got ' + entries);

    const q = new URLSearchParams({ root: rootHex, signal: signal.url, room });
    const url = pageBase + '?' + q.toString();
    console.log('open', url);

    browser = await chromium.launch({
      headless: true,
      args: [
        '--disable-features=WebRtcHideLocalIpsWithMdns',
        '--no-sandbox',
        '--disable-dev-shm-usage',
      ],
    });
    const context = await browser.newContext();
    const page = await context.newPage();
    const consoleLog = [];
    page.on('console', (msg) => {
      const line = msg.type() + ' ' + msg.text();
      consoleLog.push(line);
      console.log('page', line);
    });
    page.on('pageerror', (err) => {
      consoleLog.push('pageerror ' + err);
      console.log('pageerror', err);
    });
    await page.goto(url, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(
      () => {
        const s = document.getElementById('status')?.textContent || '';
        return s === 'verified from peer' || s.startsWith('failed');
      },
      null,
      { timeout: 45000 },
    );
    const status = await page.locator('#status').innerText();
    if (status !== 'verified from peer') {
      throw new Error('page status: ' + status + '\n' + consoleLog.join('\n') + '\n' + nbb.log.err);
    }
    const shownRoot = (await page.locator('#root').innerText()).trim();
    const shownEntries = (await page.locator('#entries').innerText()).trim();
    const shownVerified = (await page.locator('#verified').innerText()).trim();
    const origin = (await page.locator('#origin').innerText()).trim();
    const originStatus = await page.locator('#origin').getAttribute('data-status');
    const flowGot = await page.locator('#flow').getAttribute('data-got');
    const flowMax = await page.locator('#flow').getAttribute('data-max');
    const frame = await page.locator('#status').getAttribute('data-frame');

    await waitMatch(nbb.log, /^served$/m, 5000, 'served');
    await waitMatch(nbb.log, new RegExp('^flow-sent ' + PROBE_FRAMES + '$', 'm'), 5000, 'flow-sent');
    await waitMatch(nbb.log, /^flow-drained 0$/m, 5000, 'flow-drained');
    await waitMatch(nbb.log, /^peer-buffered-reads (\d+)$/m, 5000, 'peer-buffered-reads');
    const flowMaxNbb = Number(line(nbb.log, /^flow-max (\d+)$/m)[1]);
    const reads = Number(line(nbb.log, /^peer-buffered-reads (\d+)$/m)[1]);
    const browserMax = Number(flowMax);

    const problems = [];
    const eq = (name, actual, expected) => {
      if (actual !== expected) problems.push(name + ': ' + actual + ' !== ' + expected);
    };
    eq('root', shownRoot, rootHex);
    eq('entries', shownEntries, String(entries));
    eq('verified', shownVerified, String(entries));
    eq('origin', origin, '404');
    eq('origin-status', originStatus, '404');
    eq('flow-got', flowGot, String(PROBE_FRAMES));
    eq('negotiated-max-frame', frame, String(FRAME));
    // The burst is synchronous. Chromium's bufferedAmount stays at
    // 16 * 4096 until the next turn, which is above the 4-frame high mark.
    eq('browser-buffered-max', browserMax, PROBE_FRAMES * 4096);
    if (!(reads >= 1)) problems.push('peer-buffered-reads: ' + reads);
    if (!Number.isInteger(flowMaxNbb) || flowMaxNbb < 0) problems.push('flow-max: ' + flowMaxNbb);
    if (problems.length) {
      throw new Error(problems.join('\n'));
    }
    console.log('ok', JSON.stringify({
      entries,
      'flow-max': flowMaxNbb,
      'browser-buffered-max': browserMax,
      'peer-buffered-reads': reads,
      frame: Number(frame),
    }));
  } finally {
    if (browser) await browser.close().catch(() => {});
    if (nbb.child.exitCode == null) nbb.child.kill('SIGTERM');
    await signal.close().catch(() => {});
    await site.close().catch(() => {});
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
