#!/usr/bin/env node
/**
 * JUSTIFICATION: one headless Chromium launch and two WebRTC handshakes
 * with the nbb peer. Browser startup and ICE are outside the 3 second
 * bound. The value is the small welcome value.
 *
 * `npx dacite` end to end (packaging slice 1): start `bin/dacite.mjs` on a
 * free port, open the Value Browser URL it prints (served by that same
 * process, with its /signal relay and room in the query), and fetch the
 * welcome value over peering. Then reload the page and fetch it again: the
 * peer rejoins the room for a second session. No request to the server
 * carries the hash, and every request goes to the one port.
 *
 * Requires `npm run browser:build` first (the page bundle).
 */
import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bundle = path.join(root, 'examples', 'web', 'browser', 'js', 'main.js');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startDacite() {
  const child = spawn(process.execPath, [path.join(root, 'bin', 'dacite.mjs'), '--port', '0'], {
    cwd: fs.mkdtempSync(path.join(fs.realpathSync('/tmp'), 'dacite-e2e-')),
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
  try {
    const open = (await waitMatch(d, /^open (\S+)$/m, 20000, 'open'))[1];
    const hex = (await waitMatch(d, /^value ([0-9a-f]{64})$/m, 1000, 'value'))[1];
    const entries = (await waitMatch(d, /^entries (\d+)$/m, 1000, 'entries'))[1];
    const origin = new URL(open).origin;
    const signal = new URL(open).searchParams.get('signal');
    if (signal !== origin.replace(/^http/, 'ws') + '/signal') throw new Error('signal is not on the page port: ' + signal);

    browser = await chromium.launch({
      headless: true,
      args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--no-sandbox', '--disable-dev-shm-usage'],
    });
    const page = await (await browser.newContext()).newPage();
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
      const got = await fetchHash(page, hex);
      eq(`round ${round} code`, got.code, 'verified');
      eq(`round ${round} entries`, got.entries, entries);
      eq(`round ${round} verified`, got.verified, entries);
      await waitMatch(d, new RegExp(`^session ${round} connected$`, 'm'), 10000, `session ${round}`, 'err');
    }
    const elsewhere = urls.filter((u) => !u.startsWith(origin + '/'));
    if (elsewhere.length) problems.push('requests off the one port: ' + elsewhere.join(' | '));
    const leaked = urls.filter((u) => u.includes(hex) || u.includes('urn:dacite') || u.includes('/node/'));
    if (leaked.length) problems.push('hash requested over HTTP: ' + leaked.join(' | '));
    if (problems.length) throw new Error(problems.join('\n'));
    console.log('ok', JSON.stringify({ entries: Number(entries), sessions: 2 }));
  } finally {
    if (browser) await browser.close().catch(() => {});
    if (d.child.exitCode == null) d.child.kill('SIGTERM');
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
