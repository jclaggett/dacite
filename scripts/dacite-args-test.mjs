#!/usr/bin/env node
/**
 * `npx dacite` argument parsing (scripts/dacite-args.mjs): `--port`,
 * `--store <dir>`, `--memory`, and the usage errors. No processes, no I/O.
 */
import assert from 'assert/strict';
import path from 'path';
import { DEFAULT_PORT, UsageError, parseArgs } from './dacite-args.mjs';

const cwd = path.resolve('/work');
const cases = [];
const test = (name, f) => cases.push([name, f]);
const usage = (argv, re) => assert.throws(() => parseArgs(argv, cwd), (e) => e instanceof UsageError && re.test(e.message));

test('defaults: the default port and the default store', () => {
  assert.deepEqual(parseArgs([], cwd), { port: DEFAULT_PORT, store: null, memory: false, help: false });
});
test('--port', () => {
  assert.equal(parseArgs(['--port', '0'], cwd).port, 0);
  assert.equal(parseArgs(['--port', '65535'], cwd).port, 65535);
  usage(['--port', '65536'], /--port must be/);
  usage(['--port', '1.5'], /--port must be/);
  usage(['--port'], /--port needs a value/);
});
test('--store resolves against cwd', () => {
  assert.equal(parseArgs(['--store', 'data'], cwd).store, path.join(cwd, 'data'));
  const abs = path.resolve('/elsewhere/s');
  assert.equal(parseArgs(['--store', abs], cwd).store, abs);
  assert.equal(parseArgs(['--port', '0', '--store', 'x'], cwd).port, 0);
});
test('--store needs a value', () => {
  usage(['--store'], /--store needs a value/);
  usage(['--store', ''], /--store needs a value/);
  usage(['--store', '--memory'], /--store needs a value/);
});
test('--memory', () => {
  const o = parseArgs(['--memory'], cwd);
  assert.equal(o.memory, true);
  assert.equal(o.store, null);
});
test('--store and --memory together are refused', () => {
  usage(['--store', 'x', '--memory'], /not both/);
  usage(['--memory', '--store', 'x'], /not both/);
});
test('help and unknown arguments', () => {
  assert.equal(parseArgs(['-h'], cwd).help, true);
  assert.equal(parseArgs(['--help'], cwd).help, true);
  usage(['--stor', 'x'], /unknown argument: --stor/);
});

let failed = 0;
for (const [name, f] of cases) {
  try {
    f();
    console.log('ok', name);
  } catch (e) {
    failed += 1;
    console.log('FAIL', name, e.message);
  }
}
console.log(`dacite args: ${cases.length} tests, ${failed} failed`);
if (failed) process.exit(1);
