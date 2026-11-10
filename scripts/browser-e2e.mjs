#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium launch plus a WebRTC handshake
 * with the nbb peer. Browser startup and ICE are outside the 3 second
 * bound. The two values are small. Later fetches reuse the open channel.
 *
 * M6.4: paste dacite://<host>#urn:dacite:<hex>, fetch that hash from the
 * named peer, then paste a second hash for the same name. A hash the
 * peer does not hold fails as unknown-hash. The fragment is not requested
 * as an HTTP path. Counts are the nbb process's printed counts for this
 * fixed fixture (two distinct values, three gets, one channel).
 *
 * Requires `npm run browser:build` first (the page bundle).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { startServer } from './webrtc-signal-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = path.join(root, 'examples', 'web');
const bundle = path.join(webRoot, 'browser', 'js', 'main.js');
const hostName = 'shelf';

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
    ['-m', 'dacite.examples.browser-nbb', '--ws', wsUrl, '--room', room, '--page', page],
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

function askedCount(log, hex) {
  const re = new RegExp('^asked ' + hex + '$', 'gm');
  return (log.out.match(re) || []).length;
}

async function waitAsked(log, hex, n, ms) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (askedCount(log, hex) === n) return;
    if (log.child && log.child.exitCode != null) break;
    await sleep(50);
  }
  throw new Error(
    'timed out waiting for asked ' + hex + ' x' + n + '\n' + log.out,
  );
}

async function fetchHash(page, hex) {
  const uri = 'dacite://' + hostName + '#urn:dacite:' + hex;
  await page.fill('#uri', uri);
  await page.fill('#urn', '');
  await page.click('#fetch');
  await page.waitForFunction(
    (expected) => {
      const hash = document.getElementById('hash')?.dataset.hash || '';
      const code = document.getElementById('status')?.dataset.code || '';
      return hash === expected && code !== '';
    },
    hex,
    { timeout: 45000 },
  );
}

async function main() {
  if (!fs.existsSync(bundle)) {
    throw new Error('missing ' + bundle + ' — run npm run browser:build');
  }
  const { chromium } = await import('playwright');
  const signal = await startServer({ port: 0, host: '127.0.0.1' });
  const site = await startStatic();
  const room = 'm64-' + Math.random().toString(16).slice(2);
  const pageBase = `http://127.0.0.1:${site.port}/app/browser/`;
  const nbb = startNbb(signal.url, room, pageBase);
  const urls = [];
  let browser;
  try {
    const a = await waitMatch(nbb.log, /^value-a ([0-9a-f]{64})$/m, 20000, 'value-a');
    const b = await waitMatch(nbb.log, /^value-b ([0-9a-f]{64})$/m, 5000, 'value-b');
    const ea = await waitMatch(nbb.log, /^entries-a (\d+)$/m, 5000, 'entries-a');
    const eb = await waitMatch(nbb.log, /^entries-b (\d+)$/m, 5000, 'entries-b');
    const absentM = await waitMatch(nbb.log, /^absent ([0-9a-f]{64})$/m, 5000, 'absent');
    await waitMatch(
      nbb.log,
      /^waiting the page joins this room when Fetch is clicked$/m,
      5000,
      'waiting',
    );
    const hexA = a[1];
    const hexB = b[1];
    const absent = absentM[1];
    const entriesA = ea[1];
    const entriesB = eb[1];
    if (hexA === hexB) throw new Error('the two values must differ');
    if (entriesA === entriesB) throw new Error('the two entry counts must differ');
    if (!(Number(entriesA) > 1) || !(Number(entriesB) > 1)) {
      throw new Error('each value needs more than one entry');
    }

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
    page.on('request', (req) => urls.push(req.url()));
    page.on('console', (msg) => console.log('page', msg.type(), msg.text()));
    page.on('pageerror', (err) => console.log('pageerror', err));

    await page.goto(pageBase, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(
      () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
      null,
      { timeout: 20000 },
    );
    if (page.url().includes(hexA) || page.url().includes('urn:dacite')) {
      throw new Error('the page URL already carries a value: ' + page.url());
    }

    await page.fill('#signal', signal.url);
    await page.fill('#room', room);

    const problems = [];
    const eq = (name, actual, expected) => {
      if (actual !== expected) problems.push(name + ': ' + actual + ' !== ' + expected);
    };
    const shown = async () => ({
      hash: (await page.locator('#hash').innerText()).trim(),
      from: await page.locator('#hash').getAttribute('data-from'),
      fragment: (await page.locator('#fragment').innerText()).trim(),
      entries: (await page.locator('#entries').innerText()).trim(),
      entriesCount: await page.locator('#entries').getAttribute('data-count'),
      verified: (await page.locator('#verified').innerText()).trim(),
      verifiedCount: await page.locator('#verified').getAttribute('data-count'),
      code: await page.locator('#status').getAttribute('data-code'),
      connects: await page.locator('#status').getAttribute('data-connects'),
      gets: await page.locator('#status').getAttribute('data-gets'),
      peer: (await page.locator('#peer').innerText()).trim(),
    });
    const expectValue = (label, snap, hex, entries, gets) => {
      eq(label + '-hash', snap.hash, hex);
      eq(label + '-from', snap.from, 'fragment');
      eq(label + '-fragment', snap.fragment, 'urn:dacite:' + hex);
      eq(label + '-code', snap.code, 'verified');
      eq(label + '-entries', snap.entries, entries);
      eq(label + '-entries-count', snap.entriesCount, entries);
      eq(label + '-verified', snap.verified, entries);
      eq(label + '-verified-count', snap.verifiedCount, entries);
      eq(label + '-connects', snap.connects, '1');
      eq(label + '-gets', snap.gets, gets);
      eq(label + '-peer', snap.peer, hostName);
    };

    await fetchHash(page, hexA);
    await waitAsked(nbb.log, hexA, 1, 5000);
    expectValue('first', await shown(), hexA, entriesA, '1');

    await fetchHash(page, hexB);
    await waitAsked(nbb.log, hexB, 1, 5000);
    expectValue('second', await shown(), hexB, entriesB, '2');

    await fetchHash(page, absent);
    await waitAsked(nbb.log, absent, 1, 5000);
    const miss = await shown();
    eq('miss-hash', miss.hash, absent);
    eq('miss-from', miss.from, 'fragment');
    eq('miss-fragment', miss.fragment, 'urn:dacite:' + absent);
    eq('miss-code', miss.code, 'unknown-hash');
    eq('miss-entries', miss.entries, '—');
    eq('miss-entries-count', miss.entriesCount, '');
    eq('miss-verified', miss.verified, '—');
    eq('miss-verified-count', miss.verifiedCount, '');
    eq('connects', miss.connects, '1');
    eq('gets', miss.gets, '3');
    eq('peer', miss.peer, hostName);
    eq('asked-a', String(askedCount(nbb.log, hexA)), '1');
    eq('asked-b', String(askedCount(nbb.log, hexB)), '1');
    eq('asked-absent', String(askedCount(nbb.log, absent)), '1');

    const leaked = urls.filter((u) =>
      u.includes(hexA) || u.includes(hexB) || u.includes(absent) ||
      u.includes('urn:dacite') || u.includes('/node/'));
    if (leaked.length) problems.push('fragment requested over HTTP: ' + leaked.join(' | '));
    if (page.url().includes(hexA) || page.url().includes(hexB) || page.url().includes(absent)) {
      problems.push('page URL carries a hash: ' + page.url());
    }
    if (problems.length) throw new Error(problems.join('\n'));

    console.log('ok', JSON.stringify({
      entriesA: Number(entriesA),
      entriesB: Number(entriesB),
      connects: 1,
      gets: 3,
      'unknown-hash': true,
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
