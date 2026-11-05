# Contributing

Plan, review cycle and acceptance rules: [docs/plan/peering.md](docs/plan/peering.md), section 7.

## Tests

- Run the suites from the PR template: the JVM suite, `npm run reveal:test`, `npm run idb:test`, `npm run peer:test`, and `npm run store:test` (after `npm run lmdb:v1`, so the LMDB checks run). GitHub Actions (`.github/workflows/test.yml`) runs those nbb suites on pull requests and pushes to `main`. The JVM suite stays local.
- New tests fail before the change and pass after.
- No wall-clock-dependent assertions, and no assertion whose count depends on timing or randomness (a loop that asserts once per retry flaps the total; collect in the loop and assert after it).
- **A single test that takes longer than about 3 seconds is broken**, unless it carries a written justification (a comment starting with `JUSTIFICATION`) for why it needs that long. Setup and teardown harness time does not count. List the slowest tests with:

  ```
  cd impl/clojure
  clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -M:dev -i dev/time_tests.clj
  ```

## Commits and PRs

Branch from `main`, open a PR from the template, and do not push to `main` or tag.
