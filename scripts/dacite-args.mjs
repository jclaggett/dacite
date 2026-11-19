/**
 * Command-line arguments of `npx dacite` (bin/dacite.mjs).
 *
 *   npx dacite [--port 8790] [--store <dir> | --memory]
 *
 * `parseArgs` returns `{ port, store, memory, help }` and throws a
 * `UsageError` on a bad argument; the bin prints it with `USAGE` and exits 2.
 * `store` is the `--store` dir resolved against `cwd` (null when not given:
 * the default store in the data dir, docs/design/packaging.md §P3).
 */
import path from 'path';

export const DEFAULT_PORT = 8790;

export const USAGE = 'usage: npx dacite [--port 8790] [--store <dir> | --memory]';

export class UsageError extends Error {}

function value(argv, i, flag) {
  const v = argv[i + 1];
  if (v === undefined || v === '' || v.startsWith('-')) throw new UsageError(`${flag} needs a value`);
  return v;
}

export function parseArgs(argv, cwd = process.cwd()) {
  const opts = { port: DEFAULT_PORT, store: null, memory: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--port') {
      const p = Number(value(argv, i, '--port'));
      if (!Number.isInteger(p) || p < 0 || p > 65535) throw new UsageError('--port must be an integer from 0 to 65535');
      opts.port = p;
      i += 1;
    } else if (a === '--store') {
      opts.store = path.resolve(cwd, value(argv, i, '--store'));
      i += 1;
    } else if (a === '--memory') {
      opts.memory = true;
    } else if (a === '-h' || a === '--help') {
      opts.help = true;
    } else {
      throw new UsageError(`unknown argument: ${a}`);
    }
  }
  if (opts.memory && opts.store) throw new UsageError('pass --store or --memory, not both');
  return opts;
}
