#!/usr/bin/env node
/**
 * Static file server for examples/web, mounted at /app/. It serves files
 * only: no store protocol, no root. A path or symlink that leaves the
 * served directory is refused (403). `/app` and any directory without its
 * trailing slash redirect (301) to the slash form. The e2e scripts import startStatic;
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

/**
 * The /app/ request handler on its own, for a server that serves more
 * than files (scripts/dacite-server.mjs). `root` defaults to examples/web.
 *
 * Containment: the normalized path must stay under `root`, and so must
 * its real path (fs.realpath), so a symlink under `root` cannot serve a
 * file from outside it. Either escape is a 403.
 */
export function staticHandler({ root: base = webRoot } = {}) {
  const realBase = fs.realpathSync(base);
  const inside = (p, dir) => p === dir || p.startsWith(dir + path.sep);
  return (req, res) => {
    const u = new URL(req.url || '/', 'http://127.0.0.1');
    const redirect = (to) => {
      res.writeHead(301, { location: to + u.search, 'content-type': 'text/plain' });
      res.end(`moved to ${to}`);
    };
    const fail = (code, msg) => {
      res.writeHead(code, { 'content-type': 'text/plain' });
      res.end(msg);
    };
    if (u.pathname === '/app') return redirect('/app/');
    if (!u.pathname.startsWith('/app/')) return fail(404, 'not found');
    let rel;
    try {
      rel = decodeURIComponent(u.pathname.slice('/app/'.length));
    } catch {
      return fail(400, 'bad path');
    }
    const dirRequest = rel === '' || rel.endsWith('/');
    if (dirRequest) rel += 'index.html';
    const file = path.normalize(path.join(base, rel));
    if (!inside(file, base)) return fail(403, 'forbidden');
    fs.realpath(file, (rerr, real) => {
      if (rerr) return fail(404, 'not found');
      if (!inside(real, realBase)) return fail(403, 'forbidden');
      fs.stat(real, (serr, st) => {
        if (serr) return fail(404, 'not found');
        // A directory asked for without its trailing slash: redirect, so
        // the page's relative URLs resolve inside the directory.
        if (st.isDirectory()) {
          return dirRequest ? fail(404, 'not found') : redirect(u.pathname + '/');
        }
        fs.readFile(real, (err, buf) => {
          if (err) return fail(404, 'not found');
          res.writeHead(200, {
            'content-type': contentType(file),
            'cache-control': 'no-store',
          });
          res.end(buf);
        });
      });
    });
  };
}

export function startStatic({ port = 0, host = '127.0.0.1', root: base = webRoot } = {}) {
  const server = http.createServer(staticHandler({ root: base }));
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
