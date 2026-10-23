# Build4 validation

Runtime build: 1.1.0-candidate-build4-checkpoints. Prerelease tag: v1.1.0-rc.1.

## Automated verification

The build4 preparation environment recorded mvn verify on Linux OpenJDK 17.0.20 and Maven 3.9.11: **95 tests: 85 unit and 10 integration; zero failures, errors or skipped tests**. The repository contains the [recorded test report](../evidence/automated/build4-results.json) and [idle-worker rehearsal report](../evidence/automated/idle-rehearsal.json). The detailed `evidence/automated/final-verify.log` is included in `agent-retry-demo-v1.1.0-rc.1.zip` on the [prerelease page](https://github.com/kkmurthyt21/agent-retry-demo/releases/tag/v1.1.0-rc.1); it is not a tracked repository file.

Tests cover contract and policy boundaries, state/correlation, provider idempotency, bounded failures, checkpoint replacement and diagnostics, workflow readiness, normal operation, completion redelivery, real worker-JVM crash recovery and packaged Spring AI protocol exchange. Protocol tests simulate Gemini responses and are labelled separately from live API evidence.

A separate 45-second idle-worker rehearsal confirmed status was not rewritten while unchanged, all roles remained alive, and a direct event fixture subsequently reached VERIFIED with one provider record. Its source is DIRECT_EVENT_FIXTURE_NO_MODEL.

## Presenter-reported Windows runs

Platform: Windows; Temurin Java 25.0.4.1. Live model: gemini-3.5-flash-lite through Spring AI 2.0.1.

| Scenario | Decisions and outcome |
| --- | --- |
| Scripted completion fault | Two submit proposals; one new proposal after UNKNOWN; verifiedReuse=1; one provider submission and accepted record; completion delivery count 2; invariants passed |
| Live normal | Three model replies; one submit proposal; no UNKNOWN; one provider record; VERIFIED; invariants passed |
| Live completion fault | Four model replies; one submit proposal; one status query after UNKNOWN; no fresh payment proposal; completion delivery count 2; one provider record; VERIFIED; invariants passed |

These payloads were supplied by the presenter as console output. The publication process did not independently read the Windows files. Exact reported run IDs and provenance are in [windows/provenance.json](../evidence/windows/provenance.json). No additional Windows worker-crash or policy-hold run is claimed here.

The live fault run recovered from an HTTP 503 on the second API attempt for the final model response. This was a model API retry, not another payment submission. Supplied excerpts show individual model turns around 27–36 seconds; they do not establish complete run duration.

## Publication check and unchanged runtime

The publication environment reran the scripted completion scenario against the exact recovered build4 JAR. It passed with two proposals and one provider submission/record. That result is explicitly scripted and does not add a live Gemini run. No new Java or dependency changes were made for publication; the recorded 95-test result belongs to that tested runtime.

JAR SHA-256: 5ac099ff4d457c3c1ec9a87e494bda97a9b37aee3c9d7f0c1baf05bc857b4773.

Each scenario's invariants apply to this mock provider and local durable stores. VERIFIED is not settlement; no universal exactly-once or production deployment guarantee is established.
