# v1.1.0-rc.1: Spring AI + Gemini reliable agent workflows

This conference prerelease publishes the tested 1.1.0-candidate-build4-checkpoints runtime. The original v1.0.0 offline release and tag are preserved.

## Included

- Real Gemini tool selection through Spring AI 2.0.1, with an application-owned durable tool/event loop.
- Four local JVMs, durable broker/inbox/outbox, policy checkpoints, operation identity, deterministic Executor/Verifier and mock-provider receipt reconciliation.
- Completion-consumer pause, workflow-worker crash and policy-hold scenarios; explicit scripted repeated-tool fixture.
- Build4 checkpoint replacement, change-only status exports, workflow readiness and child-process diagnostics.
- Updated architecture, fresh-start PowerShell instructions, demo walkthrough, Q&A, evidence provenance, dependency notices and checksums.

## Validation and limits

The recorded Linux build4 verification passed 95 automated tests. Three presenter-supplied Windows Java 25.0.4.1 summaries passed: scripted repeat protection, normal live Gemini, and live completion recovery using gemini-3.5-flash-lite. The publication environment repeated the scripted completion check against the same JAR.

The live recovery branch was a status query after UNKNOWN, not a fresh Gemini payment proposal. The scripted fixture separately forced a repeated submit request and produced one accepted provider record. Model latency and an HTTP 503 were observed; the latter recovered on the second API attempt for the final model response. Full Windows live traces are not included in this package.

No real money moves. VERIFIED means the mock provider record matches the approved request; settlement is outside this demo. The application is a local teaching fixture, without a production broker/scaling claim or universal exactly-once external effect guarantee.

The runnable JAR is unchanged from the tested build4 candidate. Its SHA-256 is 5ac099ff4d457c3c1ec9a87e494bda97a9b37aee3c9d7f0c1baf05bc857b4773. See docs/TEST_RESULTS.md and evidence/README.md.
