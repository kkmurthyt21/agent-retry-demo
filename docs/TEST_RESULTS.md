# Verified test results

Verification date: October 19, 2026. Runtime: OpenJDK 17.0.20. Build tool: Maven 3.9.11.

Verification lifecycle: `mvn clean verify`. Dependencies were resolved during preparation. Result: **BUILD SUCCESS**.

The Java package is `dev.agentretry.demo`, the Maven group is `dev.agentretry`, and the runnable entry point is `dev.agentretry.demo.Main`. All suites were rerun after this package correction. The current JAR SHA-256 is `e22d7e195a2f098a22d0c90aeb67798e9858c2ad9136410c4cbaf5e3540122d9`.

| Group | Suite | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|
| Unit/regression | GatewayTest | 11 | 0 | 0 | 0 |
| Unit/regression | WorkflowTest | 31 | 0 | 0 | 0 |
| Packaged integration | PackagedDemoIT | 3 | 0 | 0 | 0 |
| Total | All suites | 45 | 0 | 0 | 0 |

The 42 unit/regression cases include normal flow, event and action duplicates, conflicting identities and parameters, schema versions and invalid structures, permission checks, stale state, delivery/processing separation, persisted backoff and exhaustion, deadline expiry, transaction rollback, gateway concurrency, Verifier failure, and restart after local completion.

The three packaged tests run the shaded JAR in child JVMs:

1. Hard halt after independent gateway commit, inspect saved files before recovery, then recover in a fresh JVM.
2. Run the four prepared commands with socket access denied. A separate probe verifies that a socket connection is denied.
3. Verify reset refuses an unmarked nonempty directory and preserves unrelated files.

The crash command exits 23 intentionally. The other stage commands exit 0. Recovery reports one instruction for the action, two total instructions for the two distinct workflows, zero new instructions during recovery, and zero gateway submission calls in the recovering process.

The Security Manager is only the JDK 17/21 integration-test mechanism for denying sockets. The normal demo commands do not enable it. No internet access is required by the packaged application.

These results describe the synthetic application and process-restart scenario. They do not measure real provider behavior, settlement, throughput, or hardware-failure durability.

A timed presenter rehearsal, laptop playback, and venue A/V checks remain separate human checks.
