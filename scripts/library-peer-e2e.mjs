#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium navigation of the library reader
 * and a WebRTC handshake with the nbb peer. Browser startup and ICE are
 * outside the 3 second bound. The value is the seed catalog and its plate.
 *
 * M5.3: the library page reads chapter 0 and the plate from the nbb
 * peer, by the root hash in the query. The static server below serves
 * only the page. Wall-clock times are recorded.
 *
 * Requires `npm run library:build` first (examples/web/library/js/main.js).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { startServer } from './webrtc-signal-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = path.join(root, 'examples', 'web');
const bundle = path.join(webRoot, 'library', 'js', 'main.js');
const PLATE_BYTES = 100;
const PLATE_PX = 16;

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
    ['-m', 'dacite.examples.library-nbb', '--ws', wsUrl, '--room', room, '--page', page],
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

function num(s, name) {
  const n = Number(s);
  if (!Number.isFinite(n)) throw new Error(name + ' is not a number: ' + s);
  return n;
}

async function openRead(browser, url) {
  const page = await browser.newPage();
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
      const s = document.getElementById('readout')?.getAttribute('data-status');
      if (s === 'failed') return true;
      const img = document.getElementById('plate');
      return s === 'rendered' && !!img && img.naturalWidth > 0;
    },
    null,
    { timeout: 60000 },
  );
  const status = await page.locator('#readout').getAttribute('data-status');
  if (status !== 'rendered') {
    const text = await page.locator('#readout').innerText();
    throw new Error('page did not render: ' + text + '\n' + consoleLog.join('\n'));
  }
  const attr = async (k) => page.locator('#readout').getAttribute('data-' + k);
  const chapter = await page.locator('article.page-body').innerText();
  const naturalWidth = await page.locator('#plate').evaluate((el) => el.naturalWidth);
  const naturalHeight = await page.locator('#plate').evaluate((el) => el.naturalHeight);
  const out = {
    mode: await attr('mode'),
    'round-trips': num(await attr('round-trips'), 'round-trips'),
    'origin-requests': num(await attr('origin-requests'), 'origin-requests'),
    gets: num(await attr('gets'), 'gets'),
    frames: num(await attr('frames'), 'frames'),
    'bytes-peer': num(await attr('bytes-peer'), 'bytes-peer'),
    'bytes-origin': num(await attr('bytes-origin'), 'bytes-origin'),
    'ttf-ms': num(await attr('ttf-ms'), 'ttf-ms'),
    'read-ms': num(await attr('read-ms'), 'read-ms'),
    'image-bytes': num(await attr('image-bytes'), 'image-bytes'),
    root: await attr('root'),
    chapter,
    naturalWidth,
    naturalHeight,
  };
  await page.close();
  return out;
}

async function main() {
  if (!fs.existsSync(bundle)) {
    throw new Error('missing ' + bundle + ' — run npm run library:build');
  }
  const { chromium } = await import('playwright');
  const signal = await startServer({ port: 0, host: '127.0.0.1' });
  const site = await startStatic();
  const room = 'm53-' + Math.random().toString(16).slice(2);
  const nbb = startNbb(signal.url, room, `http://127.0.0.1:${site.port}/app/library/`);
  let browser;
  try {
    const nbbRoot = await waitMatch(nbb.log, /^root ([0-9a-f]{64})$/m, 60000, 'nbb root');
    const nbbEntries = await waitMatch(nbb.log, /^entries (\d+)$/m, 5000, 'nbb entries');
    const buildUs = await waitMatch(nbb.log, /^build-us (\d+)$/m, 5000, 'build-us');
    const tau = await waitMatch(nbb.log, /^tau-ms (\d+)$/m, 5000, 'tau-ms');
    const pageUrl = await waitMatch(nbb.log, /^page (\S+)$/m, 5000, 'page url');

    const problems = [];
    const eq = (name, actual, expected) => {
      if (actual !== expected) problems.push(name + ': ' + actual + ' !== ' + expected);
    };
    eq('tau-ms', tau[1], '10000');
    if (!(Number(nbbEntries[1]) > 1)) problems.push('entries: ' + nbbEntries[1]);
    if (problems.length) throw new Error(problems.join('\n'));

    browser = await chromium.launch({
      headless: true,
      args: [
        '--disable-features=WebRtcHideLocalIpsWithMdns',
        '--no-sandbox',
        '--disable-dev-shm-usage',
      ],
    });

    const peer = await openRead(browser, pageUrl[1]);
    await waitMatch(nbb.log, /^served$/m, 5000, 'served');
    await waitMatch(nbb.log, /^recv-max (\d+)$/m, 5000, 'recv-max');
    await waitMatch(nbb.log, /^serve-us (\d+)$/m, 5000, 'serve-us');
    const recvMax = Number(nbb.log.out.match(/^recv-max (\d+)$/m)[1]);
    const serveUs = Number(nbb.log.out.match(/^serve-us (\d+)$/m)[1]);

    eq('mode', peer.mode, 'peer');
    eq('image-bytes', peer['image-bytes'], PLATE_BYTES);
    eq('width', peer.naturalWidth, PLATE_PX);
    eq('height', peer.naturalHeight, PLATE_PX);
    eq('root', peer.root, nbbRoot[1]);
    if (!peer.chapter.includes('CHAPTER I. The Shelf')) {
      problems.push('chapter missing the heading: ' + peer.chapter.slice(0, 80));
    }
    if (!peer.chapter.includes('The catalog is a set of books')) {
      problems.push('chapter missing the opening sentence');
    }
    if (!(peer['ttf-ms'] > 0)) problems.push('ttf-ms: ' + peer['ttf-ms']);
    if (!(peer['read-ms'] >= 0)) problems.push('read-ms: ' + peer['read-ms']);
    if (peer['ttf-ms'] < peer['read-ms']) {
      problems.push('ttf before read: ' + peer['ttf-ms'] + ' < ' + peer['read-ms']);
    }
    eq('gets', peer.gets, 1);
    eq('origin-requests', peer['origin-requests'], 0);
    eq('bytes-origin', peer['bytes-origin'], 0);
    if (!(peer['bytes-peer'] > 0)) problems.push('bytes-peer: ' + peer['bytes-peer']);
    if (!(peer.frames >= 1)) problems.push('frames: ' + peer.frames);
    if (!(Number(buildUs[1]) > 0)) problems.push('build-us: ' + buildUs[1]);
    if (!(recvMax > 0)) problems.push('recv-max: ' + recvMax);
    if (!(serveUs >= 0)) problems.push('serve-us: ' + serveUs);
    if (problems.length) throw new Error(problems.join('\n'));

    const measured = {
      catalog: 'seed library, chapter 0, 16x16 plate',
      'plate-bytes': PLATE_BYTES,
      entries: Number(nbbEntries[1]),
      peer: {
        'round-trips': peer['round-trips'],
        'bytes-peer': peer['bytes-peer'],
        'ttf-ms': peer['ttf-ms'],
        'read-ms': peer['read-ms'],
        gets: peer.gets,
        frames: peer.frames,
      },
      'build-us': Number(buildUs[1]),
      'serve-us': serveUs,
      'recv-max': recvMax,
      'tau-ms': 10000,
    };
    console.log('measure', JSON.stringify(measured));
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
