## Milestone

<!-- Milestone id and title from docs/plan/peering.md, e.g. M1.1 Messages and framing -->

## Tests run

<!-- Exact commands and counts. Baseline must stay green (see docs/plan/baseline.md):
- JVM:  cd impl/clojure && clojure -M:dev:test
- nbb:  npm run reveal:test
- nbb:  npm run idb:test
- nbb:  npm run peer:test
- nbb:  npm run mem:test
- nbb:  npm run webrtc:test
- nbb:  npm run lmdb:v1 && npm run store:test
Plus any new tests, and whether they failed before the change. -->

## Checklist items closed

<!-- Items from the latest review checklist (file:line or item id, e.g. N1) closed by this PR, or "none". -->

## Test time

<!-- No single test takes longer than about 3 s unless it carries a "JUSTIFICATION" comment (CONTRIBUTING.md). Paste the slowest 3 from dev/time_tests.clj. -->

- [ ] No test over about 3 s without a written justification
- [ ] No assertion whose count varies from run to run
