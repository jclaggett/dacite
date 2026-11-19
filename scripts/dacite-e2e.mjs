#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium launch, three WebRTC handshakes,
 * and three launches of the nbb peer. Browser startup and ICE are outside the 3 second
 * bound. The value is the small welcome value.
 *
 * `npx dacite` end to end (packaging slice 1): start `bin/dacite.mjs` on a
 * free port, open the Value Browser URL it prints (served by that same
 * process, with its /signal relay, room, and token in the fragment), and
 * fetch the welcome value over peering. The page strips the token from the
 * address bar. Then reload the page and fetch it again: the token comes
 * from sessionStorage, and the peer rejoins the room for a second session.
 * No request to the server carries the hash or the token, and every
 * request goes to the one port. Last, a fresh tab given the URL without
 * the token is refused (`no-token`; the server answers 401).
 *
 * The store (packaging slice 3) is the default one in a temp `DACITE_HOME`.
 * A second launch on it while the first runs exits 1 naming the first's
 * pid. Then the first is killed (SIGKILL, so its `LOCK` stays), and a
 * third launch takes the stale `LOCK` over with the warning, prints the
 * same value and entry count (the store kept it; nothing is put again),
 * and a fresh tab fetches it from that peer.
 *
 * Requires `npm run browser:build` first (the page bundle).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bundle = path.join(root, 'examples', 'web', 'browser', 'js', 'main.js');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The default store, in a temp data dir (DACITE_HOME).
const home = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'dacite-e2e-'));
const storeDir = path.join(home, 'store');

function startDacite() {
  const child = spawn(process.execPath, [path.join(root, 'bin', 'dacite.mjs'), '--port', '0'], {
    cwd: home,
    env: { ...process.env, DACITE_HOME: home },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const log = { child, out: '', err: '' };
  child.stdout.on('data', (d) => { log.out += d; process.stdout.write(d); });
  child.stderr.on('data', (d) => { log.err += d; process.stderr.write(d); });
  return log;
}

async function waitMatch(log, re, ms, label, stream = 'out') {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const m = log[stream].match(re);
    if (m) return m;
    if (log.child.exitCode != null) break;
    await sleep(50);
  }
  throw new Error(`timed out waiting for ${label}\n--- stdout ---\n${log.out}\n--- stderr ---\n${log.err}`);
}

async function fetchHash(page, hex) {
  await page.fill('#uri', 'dacite://local#urn:dacite:' + hex);
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
  return {
    code: await page.locator('#status').getAttribute('data-code'),
    entries: await page.locator('#entries').getAttribute('data-count'),
    verified: await page.locator('#verified').getAttribute('data-count'),
  };
}

async function main() {
  if (!fs.existsSync(bundle)) throw new Error('missing ' + bundle + ' — run npm run browser:build');
  const { chromium } = await import('playwright');
  const d = startDacite();
  let browser;
  let third;
  let origin3 = 'none';
  try {
    const open = (await waitMatch(d, /^open (\S+)$/m, 20000, 'open'))[1];
    const hex = (await waitMatch(d, /^value ([0-9a-f]{64})$/m, 1000, 'value'))[1];
    const entries = (await waitMatch(d, /^entries (\d+)$/m, 1000, 'entries'))[1];
    const storeLine = (await waitMatch(d, /^store (lmdb|file) (.+)$/m, 1000, 'store'));
    const origin = new URL(open).origin;
    const frag = new URLSearchParams(new URL(open).hash.slice(1));
    const signal = frag.get('signal');
    const token = frag.get('token');
    if (new URL(open).search) throw new Error('the printed URL has a query: ' + open);
    if (!/^[A-Za-z0-9_-]{43}$/.test(token || '')) throw new Error('no 43-character token in the fragment: ' + open);
    if (signal !== origin.replace(/^http/, 'ws') + '/signal') throw new Error('signal is not on the page port: ' + signal);

    browser = await chromium.launch({
      headless: true,
      args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--no-sandbox', '--disable-dev-shm-usage'],
    });
    const context = await browser.newContext();
    const page = await context.newPage();
    const urls = [];
    page.on('request', (req) => urls.push(req.url()));
    page.on('pageerror', (err) => console.log('pageerror', err));

    const problems = [];
    const eq = (name, a, b) => { if (a !== b) problems.push(`${name}: ${a} !== ${b}`); };
    for (const round of [1, 2]) {
      if (round === 1) await page.goto(open, { waitUntil: 'domcontentloaded' });
      else await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForFunction(
        () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
        null,
        { timeout: 20000 },
      );
      eq(`round ${round} signal field`, await page.inputValue('#signal'), signal);
      eq(`round ${round} token in the address bar`, page.url().includes(token), false);
      const got = await fetchHash(page, hex);
      eq(`round ${round} code`, got.code, 'verified');
      eq(`round ${round} entries`, got.entries, entries);
      eq(`round ${round} verified`, got.verified, entries);
      await waitMatch(d, new RegExp(`^session ${round} connected$`, 'm'), 10000, `session ${round}`, 'err');
    }
    // A fresh tab (no sessionStorage) given the URL without the token.
    frag.delete('token');
    const bare = await context.newPage();
    await bare.goto(`${origin}/app/browser/#${frag}`, { waitUntil: 'domcontentloaded' });
    await bare.waitForFunction(
      () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
      null,
      { timeout: 20000 },
    );
    await bare.fill('#uri', 'dacite://local#urn:dacite:' + hex);
    await bare.fill('#urn', '');
    await bare.click('#fetch');
    await bare.waitForFunction(
      () => (document.getElementById('status')?.dataset.code || '') !== '',
      null,
      { timeout: 20000 },
    );
    eq('no token code', await bare.locator('#status').getAttribute('data-code'), 'no-token');
    await bare.close();
    eq('store dir', storeLine[2], storeDir);
    // One line: the engine, a space, and its store format version.
    eq('engine marker', fs.readFileSync(path.join(storeDir, 'ENGINE'), 'utf8'),
      `${storeLine[1]} ${{ lmdb: 2, file: 1 }[storeLine[1]]}\n`);
    eq('LOCK holds the pid', fs.readFileSync(path.join(storeDir, 'LOCK'), 'utf8').trim(), String(d.child.pid));

    // A second peer on the same store is refused while the first runs.
    const second = startDacite();
    await new Promise((r) => (second.child.exitCode != null ? r() : second.child.once('exit', r)));
    eq('second launch exit', second.child.exitCode, 1);
    if (!second.err.includes(`is in use by pid ${d.child.pid}`)) problems.push('second launch: ' + second.err);

    // Kill the first without cleanup, then start again on the same store.
    const killed = d.child.pid;
    d.child.kill('SIGKILL');
    await new Promise((r) => (d.child.exitCode != null || d.child.signalCode ? r() : d.child.once('exit', r)));
    third = startDacite();
    await waitMatch(third, new RegExp(`^taking over a stale LOCK from pid ${killed}$`, 'm'), 20000, 'takeover', 'err');
    const open3 = (await waitMatch(third, /^open (\S+)$/m, 20000, 'open after restart'))[1];
    eq('value after restart', (await waitMatch(third, /^value ([0-9a-f]{64})$/m, 1000, 'value'))[1], hex);
    eq('entries after restart', (await waitMatch(third, /^entries (\d+)$/m, 1000, 'entries'))[1], entries);
    const again = await context.newPage();
    again.on('request', (req) => urls.push(req.url()));
    await again.goto(open3, { waitUntil: 'domcontentloaded' });
    await again.waitForFunction(
      () => (document.getElementById('status')?.textContent || '') === 'paste a peer',
      null,
      { timeout: 20000 },
    );
    const got3 = await fetchHash(again, hex);
    eq('restart code', got3.code, 'verified');
    eq('restart entries', got3.entries, entries);
    await again.close();
    origin3 = new URL(open3).origin;
    const token3 = new URLSearchParams(new URL(open3).hash.slice(1)).get('token');
    if (urls.some((u) => u.includes(token3))) problems.push('restart token in a request URL');

    const withToken = urls.filter((u) => u.includes(token));
    if (withToken.length) problems.push('token in a request URL: ' + withToken.join(' | '));
    const elsewhere = urls.filter((u) => !u.startsWith(origin + '/') && !u.startsWith(origin3 + '/'));
    if (elsewhere.length) problems.push('requests off the one port: ' + elsewhere.join(' | '));
    const leaked = urls.filter((u) => u.includes(hex) || u.includes('urn:dacite') || u.includes('/node/'));
    if (leaked.length) problems.push('hash requested over HTTP: ' + leaked.join(' | '));
    if (problems.length) throw new Error(problems.join('\n'));
    console.log('ok', JSON.stringify({
      entries: Number(entries), sessions: 3, refusedWithoutToken: true,
      engine: storeLine[1], secondRefused: true, staleLockTakenOver: true,
    }));
  } finally {
    if (browser) await browser.close().catch(() => {});
    for (const p of [d, third]) if (p && p.child.exitCode == null) p.child.kill('SIGTERM');
    if (third) await new Promise((r) => (third.child.exitCode != null ? r() : third.child.once('exit', r)));
    fs.rmSync(home, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
