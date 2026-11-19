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
 * It prints the store, the run file, the Value Browser URL, and the
 * value's hash (the root when one is set, else the welcome value). The run
 * file `<data dir>/run/<port>.json` (mode 0600 on POSIX, removed on a clean
 * exit; `dacite.cli.run`) holds that URL with its token.
 *
 * When the port is taken (§P2) it asks `GET /dacite/version` there: our
 * peer, same version, serving the store asked for, prints the URL from its
 * run file again and exits 0; anything else exits 1 saying why.
 *
 * The relay, the room, and this launch's `/signal` token are in the URL
 * fragment, which is never sent in a request (§P1). The token is per-run: a restart
 * invalidates old tabs. `--port 0` picks a free port.
 */
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { addClassPath, loadFile } from 'nbb';
import { USAGE, UsageError, parseArgs } from '../scripts/dacite-args.mjs';
import { PROTO, VERSION, probeDacite, startDacite, versionInfo } from '../scripts/dacite-server.mjs';

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
const storeOpts = opts.memory ? { memory: true } : opts.store ? { dir: opts.store } : {};

// The peer half is ClojureScript under nbb. Loaded only when needed: the
// port checks below answer without it.
async function loadPeer() {
  addClassPath(path.join(root, 'impl', 'clojure', 'src'));
  // loadFile, not loadString: npm requires then resolve from this package,
  // whatever directory `npx dacite` runs in.
  return loadFile(path.join(root, 'bin', 'dacite-peer.cljs'));
}

// The port is taken (docs/design/packaging.md §P2): reuse our own peer of
// this version, or exit 1 saying who holds it. Never another port.
async function portTaken(port) {
  const info = await probeDacite(port);
  if (!info) {
    console.error(`port ${port} is in use by another program; pass --port.`);
    process.exit(1);
  }
  if (info.dacite !== VERSION || info.proto !== PROTO) {
    console.error(`dacite ${info.dacite} (pid ${info.pid}) is running on port ${port}; this is ${VERSION}. Stop it or pass --port.`);
    process.exit(1);
  }
  const { run } = await loadPeer();
  const r = run.reuse({ port, peerPid: info.pid, store: storeOpts });
  if (r.error) {
    console.error(r.error);
    process.exit(1);
  }
  for (const line of r.lines) out(line);
  process.exit(0);
}

let server;
try {
  server = await startDacite({ port: opts.port, token });
} catch (err) {
  if (err && err.code === 'EADDRINUSE') await portTaken(opts.port);
  throw err;
}
const room = 'dacite-' + crypto.randomBytes(12).toString('base64url');
const { start, run } = await loadPeer();
const held = start({ ws: server.signalUrl, room, token, store: storeOpts });
if (held.error) {
  // The store is in use or has the wrong engine (dacite.cli.store).
  console.error(held.error);
  await server.close().catch(() => {});
  process.exit(held.exitCode);
}
const fragment = new URLSearchParams({ signal: server.signalUrl, room, token }).toString();
const page = `${server.appUrl}browser/#${fragment}`;
// The run file (§P1, §P2): what a second `npx dacite` on this port prints.
let runFile = null;
try {
  runFile = run.writeRun({
    ...versionInfo(),
    port: server.port,
    url: server.appUrl,
    signal: server.signalUrl,
    room,
    token,
    open: page,
    store: held.path || null,
    engine: held.engine,
    value: held.value,
  });
} catch (err) {
  console.error(`could not write the run file (${err.message}); a second npx dacite on port ${server.port} will not find this one`);
}
out(`dacite listening ${server.appUrl}`);
out(`signal ${server.signalUrl}`);
out(held.path ? `store ${held.engine} ${held.path}` : `store ${held.engine}`);
if (runFile) out(`run ${runFile}`);
out(`value ${held.value}`);
out(`entries ${held.entries}`);
out(`open ${page}`);
out(`paste dacite://local#urn:dacite:${held.value}`);

const stop = () => {
  try {
    if (runFile) run.removeRun(server.port, process.pid);
    held.close();
  } finally {
    server.close().finally(() => process.exit(0));
  }
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
