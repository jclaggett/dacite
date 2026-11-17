#!/usr/bin/env node
/**
 * Checks for scripts/static-server.mjs.
 *
 *   npm run web:serve-test
 *
 * `/app` and a directory without its trailing slash redirect (301) to the
 * slash form, keeping the query. `/app/` serves index.html. A path that
 * climbs out of examples/web is refused. The server binds 127.0.0.1.
 */
import assert from 'assert';
import http from 'http';
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
  console.log('static-server ok');
} finally {
  await srv.close();
}
