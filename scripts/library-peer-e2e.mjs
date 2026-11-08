#!/usr/bin/env node
/**
 * JUSTIFICATION: two headless Chromium navigations of the library reader,
 * plus a third that waits out a short peer timeout, a JVM origin, and a
 * WebRTC handshake with the nbb peer. Browser startup, the origin
 * process, and ICE are outside the 3 second bound. The value is the
 * seed catalog and its plate.
 *
 * M5.3: the library page reads chapter 0 and the plate from the nbb
 * peer. A room with no answerer falls back to the origin. Origin-only
 * is loaded twice; store-protocol round trips and body bytes must match.
 * Wall-clock times are recorded and are not required to match.
 *
 * Requires `npm run library:build` first (examples/web/library/js/main.js).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { startServer } from './webrtc-signal-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bundle = path.join(root, 'examples', 'web', 'library', 'js', 'main.js');
const PLATE_BYTES = 100;
const PLATE_PX = 16;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startOrigin() {
  const child = spawn(
    'clojure',
    ['-M:library-origin', '--port', '0'],
    { cwd: path.join(root, 'impl', 'clojure'), stdio: ['ignore', 'pipe', 'pipe'] },
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

function startNbb(wsUrl, room) {
  const child = spawn(
    path.join(root, 'node_modules', '.bin', 'nbb'),
    ['-m', 'dacite.examples.library-nbb', '--ws', wsUrl, '--room', room],
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

function sameRead(a, b) {
  const problems = [];
  const eq = (name) => {
    if (a[name] !== b[name]) problems.push(name + ': ' + a[name] + ' !== ' + b[name]);
  };
  eq('round-trips');
  eq('origin-requests');
  eq('gets');
  eq('frames');
  eq('bytes-peer');
  eq('bytes-origin');
  eq('image-bytes');
  eq('root');
  eq('chapter');
  eq('naturalWidth');
  return problems;
}

async function main() {
  if (!fs.existsSync(bundle)) {
    throw new Error('missing ' + bundle + ' — run npm run library:build');
  }
  const { chromium } = await import('playwright');
  const signal = await startServer({ port: 0, host: '127.0.0.1' });
  const origin = startOrigin();
  const room = 'm53-' + Math.random().toString(16).slice(2);
  const nbb = startNbb(signal.url, room);
  let browser;
  try {
    const originRoot = await waitMatch(origin.log, /^root ([0-9a-f]{64})$/m, 180000, 'origin root');
    const originEntries = await waitMatch(origin.log, /^entries (\d+)$/m, 5000, 'origin entries');
    const originUrl = await waitMatch(origin.log, /^origin (http:\/\/127\.0\.0\.1:\d+)$/m, 5000, 'origin url');
    await waitMatch(origin.log, /^ready$/m, 5000, 'origin ready');
    const nbbRoot = await waitMatch(nbb.log, /^root ([0-9a-f]{64})$/m, 60000, 'nbb root');
    const nbbEntries = await waitMatch(nbb.log, /^entries (\d+)$/m, 5000, 'nbb entries');
    const buildUs = await waitMatch(nbb.log, /^build-us (\d+)$/m, 5000, 'build-us');
    const tau = await waitMatch(nbb.log, /^tau-ms (\d+)$/m, 5000, 'tau-ms');

    const problems = [];
    const eq = (name, actual, expected) => {
      if (actual !== expected) problems.push(name + ': ' + actual + ' !== ' + expected);
    };
    eq('root', nbbRoot[1], originRoot[1]);
    eq('entries', nbbEntries[1], originEntries[1]);
    eq('tau-ms', tau[1], '10000');
    if (problems.length) throw new Error(problems.join('\n'));

    browser = await chromium.launch({
      headless: true,
      args: [
        '--disable-features=WebRtcHideLocalIpsWithMdns',
        '--no-sandbox',
        '--disable-dev-shm-usage',
      ],
    });

    const base = originUrl[1] + '/app/library/?chapter=0';
    const originA = await openRead(browser, base);
    const originB = await openRead(browser, base);
    const peer = await openRead(
      browser,
      base + '&signal=' + encodeURIComponent(signal.url) + '&room=' + room,
    );
    await waitMatch(nbb.log, /^served$/m, 5000, 'served');
    await waitMatch(nbb.log, /^recv-max (\d+)$/m, 5000, 'recv-max');
    await waitMatch(nbb.log, /^serve-us (\d+)$/m, 5000, 'serve-us');
    const recvMax = Number(nbb.log.out.match(/^recv-max (\d+)$/m)[1]);
    const serveUs = Number(nbb.log.out.match(/^serve-us (\d+)$/m)[1]);
    const fallback = await openRead(
      browser,
      base + '&signal=' + encodeURIComponent(signal.url) +
        '&room=m53-miss-' + Math.random().toString(16).slice(2) +
        '&peer-timeout=400',
    );

    const check = (row, mode) => {
      eq(mode + ' mode', row.mode, mode);
      eq(mode + ' image-bytes', row['image-bytes'], PLATE_BYTES);
      eq(mode + ' width', row.naturalWidth, PLATE_PX);
      eq(mode + ' height', row.naturalHeight, PLATE_PX);
      eq(mode + ' root', row.root, originRoot[1]);
      if (!row.chapter.includes('CHAPTER I. The Shelf')) {
        problems.push(mode + ' chapter missing the heading: ' + row.chapter.slice(0, 80));
      }
      if (!row.chapter.includes('The catalog is a set of books')) {
        problems.push(mode + ' chapter missing the opening sentence');
      }
      if (!(row['ttf-ms'] > 0)) problems.push(mode + ' ttf-ms: ' + row['ttf-ms']);
      if (!(row['read-ms'] >= 0)) problems.push(mode + ' read-ms: ' + row['read-ms']);
      if (row['ttf-ms'] < row['read-ms']) {
        problems.push(mode + ' ttf before read: ' + row['ttf-ms'] + ' < ' + row['read-ms']);
      }
    };
    problems.push(...sameRead(originA, originB).map((p) => 'origin rerun ' + p));
    check(originA, 'origin');
    check(peer, 'peer');
    check(fallback, 'origin-fallback');
    eq('origin bytes-peer', originA['bytes-peer'], 0);
    eq('origin gets', originA.gets, 0);
    eq('origin frames', originA.frames, 0);
    if (!(originA['bytes-origin'] > 0)) problems.push('origin bytes-origin: ' + originA['bytes-origin']);
    if (!(originA['round-trips'] >= 1)) problems.push('origin round-trips: ' + originA['round-trips']);
    eq('peer gets', peer.gets, 1);
    if (!(peer['bytes-peer'] > peer['bytes-origin'])) {
      problems.push('peer bytes: peer ' + peer['bytes-peer'] + ' origin ' + peer['bytes-origin']);
    }
    if (!(peer['bytes-origin'] > 0)) problems.push('peer still names the root from the origin');
    if (!(peer.frames >= 1)) problems.push('peer frames: ' + peer.frames);
    if (!(fallback['bytes-origin'] > peer['bytes-origin'])) {
      problems.push('fallback should read the catalog from the origin, bytes ' + fallback['bytes-origin']);
    }
    eq('tau', Number(tau[1]), 10000);
    if (!(Number(buildUs[1]) > 0)) problems.push('build-us: ' + buildUs[1]);
    if (!(recvMax > 0)) problems.push('recv-max: ' + recvMax);
    if (!(serveUs >= 0)) problems.push('serve-us: ' + serveUs);
    if (problems.length) throw new Error(problems.join('\n'));

    const measured = {
      catalog: 'seed library, chapter 0, 16x16 plate',
      'plate-bytes': PLATE_BYTES,
      entries: Number(originEntries[1]),
      origin: {
        'round-trips': originA['round-trips'],
        'bytes-peer': originA['bytes-peer'],
        'bytes-origin': originA['bytes-origin'],
        'ttf-ms': originA['ttf-ms'],
        'read-ms': originA['read-ms'],
        'origin-requests': originA['origin-requests'],
      },
      'origin-rerun': {
        'round-trips': originB['round-trips'],
        'bytes-origin': originB['bytes-origin'],
        'ttf-ms': originB['ttf-ms'],
        'read-ms': originB['read-ms'],
      },
      peer: {
        'round-trips': peer['round-trips'],
        'bytes-peer': peer['bytes-peer'],
        'bytes-origin': peer['bytes-origin'],
        'ttf-ms': peer['ttf-ms'],
        'read-ms': peer['read-ms'],
        gets: peer.gets,
        frames: peer.frames,
        'origin-requests': peer['origin-requests'],
      },
      fallback: {
        'round-trips': fallback['round-trips'],
        'bytes-peer': fallback['bytes-peer'],
        'bytes-origin': fallback['bytes-origin'],
        'ttf-ms': fallback['ttf-ms'],
        'read-ms': fallback['read-ms'],
        gets: fallback.gets,
        frames: fallback.frames,
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
    if (origin.child.exitCode == null) origin.child.kill('SIGTERM');
    await signal.close().catch(() => {});
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
