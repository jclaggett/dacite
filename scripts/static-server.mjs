#!/usr/bin/env node
/**
 * Static file server for examples/web, mounted at /app/. It serves files
 * only: no store protocol, no root. The e2e scripts import startStatic;
 * `npm run web:serve` runs it on http://127.0.0.1:8080/app/ for the hand
 * steps in docs/plan/peering.md.
 *
 *   node scripts/static-server.mjs [--port 8080] [--host 127.0.0.1]
 */
import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const webRoot = path.join(root, 'examples', 'web');

function contentType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  return 'application/octet-stream';
}

export function startStatic({ port = 0, host = '127.0.0.1' } = {}) {
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
    server.listen(port, host, () => {
      const addr = server.address();
      resolve({
        port: addr.port,
        url: `http://${host}:${addr.port}/app/`,
        close: () => new Promise((res, rej) => server.close((e) => (e ? rej(e) : res()))),
      });
    });
  });
}

function arg(name, dflt) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

function isDirectRun() {
  const a = process.argv[1];
  if (!a) return false;
  return import.meta.url === pathToFileURL(path.resolve(a)).href;
}

if (isDirectRun()) {
  const port = Number(arg('--port', '8080'));
  const host = arg('--host', '127.0.0.1');
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    console.error('--port must be an integer from 0 to 65535');
    process.exit(1);
  }
  try {
    const srv = await startStatic({ port, host });
    fs.writeSync(1, `serving ${srv.url}\n`);
  } catch (err) {
    console.error(err);
    process.exit(1);
  }
}
