#!/usr/bin/env node
/**
 * `npx dacite`: the local peer (docs/design/packaging.md, slice 1).
 *
 *   npx dacite [--port 8790]
 *
 * One process on one port, bound to 127.0.0.1:
 *   - static app code at http://127.0.0.1:<port>/app/ (examples/web),
 *   - WebSocket signaling at ws://127.0.0.1:<port>/signal,
 *   - an nbb peer (`dacite.cli.peer`) answering in a room made for this
 *     launch. It holds one welcome value in memory.
 *
 * It prints the Value Browser URL (with the relay and the room in the
 * query) and the welcome value's hash. `--port 0` picks a free port.
 */
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { addClassPath, loadFile } from 'nbb';
import { startDacite } from '../scripts/dacite-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const DEFAULT_PORT = 8790;

function usage(msg) {
  if (msg) console.error(msg);
  console.error('usage: npx dacite [--port 8790]');
  process.exit(2);
}

function parseArgs(argv) {
  const opts = { port: DEFAULT_PORT };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--port') {
      const p = Number(argv[i + 1]);
      if (!Number.isInteger(p) || p < 0 || p > 65535) usage('--port must be an integer from 0 to 65535');
      opts.port = p;
      i += 1;
    } else if (a === '-h' || a === '--help') {
      usage();
    } else {
      usage(`unknown argument: ${a}`);
    }
  }
  return opts;
}

const out = (s) => fs.writeSync(1, s + '\n');

const opts = parseArgs(process.argv.slice(2));
let server;
try {
  server = await startDacite({ port: opts.port });
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
const held = start({ ws: server.signalUrl, room });
const page = `${server.appUrl}browser/?signal=${encodeURIComponent(server.signalUrl)}&room=${encodeURIComponent(room)}`;
out(`dacite listening ${server.appUrl}`);
out(`signal ${server.signalUrl}`);
out(`value ${held.value}`);
out(`entries ${held.entries}`);
out(`open ${page}`);
out(`paste dacite://local#urn:dacite:${held.value}`);

const stop = () => {
  server.close().finally(() => process.exit(0));
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
