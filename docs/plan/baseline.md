# Dacite regression baseline (M0)

Recorded Fri Oct 30, 2026 (America/New_York) on the Grok Bot box (Linux x86_64) as part of milestone M0 in [peering.md](peering.md).

- Repo: `jclaggett/dacite`, fresh clone of `main`
- Commit baselined: `a2b9638adeca6836318dd79e544989dbc72bc9b0` ("Show the root notice stays up beside open reveals."). This is `main` HEAD at the time of the run and matches the plan's baseline.
- Proposed tag: `regression-baseline-a2b9638` (not pushed; Jonathan to create after merge or on a2b9638 directly).

## Result

| Suite | Command | Result |
|---|---|---|
| JVM | `cd impl/clojure && clojure -M:dev:test` | **564 tests, 3062 assertions, 0 failures, 0 errors** (about 45 s wall) |
| nbb reveal | `npm run reveal:test` | **pass** (exit 0; prints `reveal cljs tests passed`) |
| nbb idb | `npm run idb:test` | **pass** (exit 0; prints `idb tests passed`) |

The two nbb suites are plain scripts, not `clojure.test`: they print one pass line and throw (reveal) or exit 1 (idb) on failure, so there are no test or assertion counts. By inspection `reveal_cljs_test.cljs` runs 3 cases (`lone-decay`, `floor-at-a-power-of-two`, `page-is-later-than-the-constant-rate`) and `idb_test.cljs` runs 7 chained cases (`roundtrip`, `put-survives-reopen`, `rooted-swap`, `cache-in-front`, `batch-merge`, `cache-evicts`, `another-writer-is-visible`). These are the numbers to compare against later. No failures were seen in any suite.

## Environment

- JDK: Temurin 21.0.13+9 (`~/tools/jdk-21.0.13+9`)
- Clojure CLI: tools 1.12.6.1673 (Clojure 1.12.4 per `deps.edn`)
- Node v20.19.2, npm from `/usr/local/bin`; `nbb` ^1.3.196 and `fake-indexeddb` ^6.2.6 from `package-lock.json` via `npm install` (23 packages, 0 vulnerabilities)

Setup used for every run:

```sh
export JAVA_HOME=~/tools/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:~/tools/clojure-posix/bin:$PATH
```

Note on the Clojure CLI: `~/tools/ClojureTools` holds the Windows (PowerShell) build and `~/tools/cli.tgz` is a 9-byte "Not Found" file, so there was no `clojure` launcher on the box. The POSIX launcher from the official `clojure-tools-1.12.6.1673.tar.gz` release was installed to `~/tools/clojure-posix` (the jar is byte-identical to the one in `~/tools/ClojureTools`; `install.sh` needed its `PREFIX` placeholder replaced by hand because the box has no perl). Maven and git deps resolved normally into `~/.m2` and `~/.gitlibs`.

## Commands

```sh
git clone https://github.com/jclaggett/dacite.git && cd dacite    # HEAD a2b9638

# JVM suite. The :test alias carries the --add-opens flags
# (java.base/java.nio and java.base/sun.nio.ch = ALL-UNNAMED) and the
# cognitect test-runner as main; :dev adds the dev/ and test/ paths and test.check.
cd impl/clojure
clojure -M:dev:test

# nbb suites, from the repo root
npm install
npm run reveal:test      # nbb -cp impl/clojure/src:impl/clojure/test -m dacite.reveal-cljs-test
npm run idb:test         # nbb -cp impl/clojure/src:impl/clojure/test -m dacite.store.idb-test
```

## Findings

1. **The plan's JVM command is wrong.** `docs/plan/peering.md` section 5 (M0) says `clojure -M:test`. That fails at load time with `FileNotFoundException: Could not locate dacite/dev/inspect...` in `dacite.dev.inspect-test`, because the `dev/` source path only comes from the `:dev` alias. Use `clojure -M:dev:test`. (The plan text was left as-is in `peering.md`; a follow-up edit is suggested.)
2. JVM run is 36 test namespaces, no failures, no errors, no skipped tests reported.
3. The JVM tests need `--add-opens` for LMDB (lmdbjava); these come from the `:test` alias, so nothing extra needs passing on the command line.
4. The JVM output reports `Ran 564 tests containing 3062 assertions.` matching the plan's "564 JVM tests green".
5. Running the suites leaves the working tree clean (no generated files to ignore).
