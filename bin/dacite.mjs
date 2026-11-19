#!/usr/bin/env node
/**
 * `npx dacite`: the local peer (docs/design/packaging.md).
 *
 *   npx dacite [--port 8790] [--store <dir> | --memory]
 *
 * One process on one port, bound to 127.0.0.1:
 *   - static app code at http://127.0.0.1:<port>/app/ (examples/web),
 *   - WebSocket signaling at ws://127.0.0.1:<port>/signal,
 *   - an nbb peer (`dacite.cli.peer`) answering in a room made for this
 *     launch.
 *
 * The peer's store (§P3, `dacite.cli.store`) is `<data dir>/store` by
 * default (the data dir is `DACITE_HOME`, else the platform's: macOS
 * `~/Library/Application Support/Dacite`, Linux
 * `${XDG_DATA_HOME:-~/.local/share}/dacite`, Windows
 * `%LOCALAPPDATA%\Dacite`), or any `--store <dir>`, created if missing.
 * LMDB when the optional `lmdb` loads, else the file store with a
 * warning. One peer per store (its `LOCK`). `--memory` keeps the store in
 * memory and writes nothing. An empty store gets one welcome value.
 *
 * It prints the store, the Value Browser URL, and the value's hash (the
 * root when one is set, else the welcome value). The relay, the room,
 * and this launch's `/signal` token are in the URL fragment, which is
 * never sent in a request (§P1). The token is per-run: a restart
 * invalidates old tabs. `--port 0` picks a free port.
 */
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { addClassPath, loadFile } from 'nbb';
import { USAGE, UsageError, parseArgs } from '../scripts/dacite-args.mjs';
import { startDacite } from '../scripts/dacite-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const out = (s) => fs.writeSync(1, s + '\n');

let opts;
try {
  opts = parseArgs(process.argv.slice(2));
} catch (err) {
  if (!(err instanceof UsageError)) throw err;
  console.error(err.message);
  console.error(USAGE);
  process.exit(2);
}
if (opts.help) {
  console.error(USAGE);
  process.exit(2);
}
// The /signal token: 256 bits, base64url (43 characters, no padding),
// held in memory for this run only.
const token = crypto.randomBytes(32).toString('base64url');
let server;
try {
  server = await startDacite({ port: opts.port, token });
} catch (err) {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`port ${opts.port} is in use; pass --port`);
    process.exit(1);
  }
  throw err;
}
const room = 'dacite-' + crypto.randomBytes(12).toString('base64url');
addClassPath(path.join(root, 'impl', 'clojure', 'src'));
// loadFile, not loadString: npm requires then resolve from this package,
// whatever directory `npx dacite` runs in.
const start = await loadFile(path.join(root, 'bin', 'dacite-peer.cljs'));
const storeOpts = opts.memory ? { memory: true } : opts.store ? { dir: opts.store } : {};
const held = start({ ws: server.signalUrl, room, token, store: storeOpts });
if (held.error) {
  // The store is in use or has the wrong engine (dacite.cli.store).
  console.error(held.error);
  await server.close().catch(() => {});
  process.exit(held.exitCode);
}
const fragment = new URLSearchParams({ signal: server.signalUrl, room, token }).toString();
const page = `${server.appUrl}browser/#${fragment}`;
out(`dacite listening ${server.appUrl}`);
out(`signal ${server.signalUrl}`);
out(held.path ? `store ${held.engine} ${held.path}` : `store ${held.engine}`);
out(`value ${held.value}`);
out(`entries ${held.entries}`);
out(`open ${page}`);
out(`paste dacite://local#urn:dacite:${held.value}`);

const stop = () => {
  try {
    held.close();
  } finally {
    server.close().finally(() => process.exit(0));
  }
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
