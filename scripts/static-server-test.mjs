#!/usr/bin/env node
/**
 * Checks for scripts/static-server.mjs.
 *
 *   npm run web:serve-test
 *
 * `/app` and a directory without its trailing slash redirect (301) to the
 * slash form, keeping the query. `/app/` serves index.html. A path that
 * climbs out of examples/web is refused. The server binds 127.0.0.1.
 * In a temporary root, a symlink to a file or a directory outside it is
 * refused (403), and a symlink that stays inside is served.
 */
import assert from 'assert';
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { startStatic } from './static-server.mjs';

function get(port, p) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: p, method: 'GET' }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

const srv = await startStatic({ port: 0 });
try {
  assert.ok(srv.url.startsWith('http://127.0.0.1:'), srv.url);
  let r = await get(srv.port, '/app');
  assert.strictEqual(r.status, 301);
  assert.strictEqual(r.headers.location, '/app/');
  r = await get(srv.port, '/app?x=1');
  assert.strictEqual(r.headers.location, '/app/?x=1');
  r = await get(srv.port, '/app/library?root=ab');
  assert.strictEqual(r.status, 301);
  assert.strictEqual(r.headers.location, '/app/library/?root=ab');
  r = await get(srv.port, '/app/');
  assert.strictEqual(r.status, 200);
  assert.match(r.headers['content-type'], /text\/html/);
  r = await get(srv.port, '/app/library/');
  assert.strictEqual(r.status, 200);
  r = await get(srv.port, '/app/no-such-file.js');
  assert.strictEqual(r.status, 404);
  r = await get(srv.port, '/app/..%2f..%2fpackage.json');
  assert.strictEqual(r.status, 403);
  r = await get(srv.port, '/app/%E0%A4%A');
  assert.strictEqual(r.status, 400);
  r = await get(srv.port, '/elsewhere');
  assert.strictEqual(r.status, 404);
} finally {
  await srv.close();
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dacite-static-'));
try {
  const site = path.join(tmp, 'site');
  const outside = path.join(tmp, 'outside');
  fs.mkdirSync(site);
  fs.mkdirSync(outside);
  fs.writeFileSync(path.join(site, 'index.html'), '<p>in</p>');
  fs.writeFileSync(path.join(site, 'ok.txt'), 'ok');
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'secret');
  fs.symlinkSync(path.join(outside, 'secret.txt'), path.join(site, 'out.txt'));
  fs.symlinkSync(outside, path.join(site, 'outdir'));
  fs.symlinkSync(path.join(site, 'ok.txt'), path.join(site, 'in.txt'));
  const s2 = await startStatic({ port: 0, root: site });
  try {
    let r = await get(s2.port, '/app/out.txt');
    assert.strictEqual(r.status, 403, 'symlinked file outside the root');
    assert.ok(!r.body.includes('secret'));
    r = await get(s2.port, '/app/outdir/secret.txt');
    assert.strictEqual(r.status, 403, 'file under a symlinked directory outside the root');
    r = await get(s2.port, '/app/outdir/');
    assert.strictEqual(r.status, 404, 'no index.html there, so nothing to refuse');
    r = await get(s2.port, '/app/in.txt');
    assert.strictEqual(r.status, 200);
    assert.strictEqual(r.body, 'ok');
  } finally {
    await s2.close();
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
console.log('static-server ok');
