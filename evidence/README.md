# Public validation evidence

Evidence is separated by source. None of the simulated model responses or scripted fixture decisions is labelled as a live Gemini decision.

| Evidence | Provenance and limit |
| --- | --- |
| automated/build4-results.json and final-verify.log | Recorded Linux OpenJDK 17.0.20 / Maven 3.9.11 build4 verification: 95 tests, zero failures/errors/skips. Simulated model protocol tests and real multi-JVM process tests are included. |
| automated/idle-rehearsal.json | Separate 45-second idle-worker test followed by a direct event fixture; explicitly DIRECT_EVENT_FIXTURE_NO_MODEL. |
| windows/provenance.json and scenario summaries | Three distinct Windows Java 25.0.4.1 summaries transcribed from presenter-provided console output. The publication process did not directly read the Windows files. |
| windows/live-completion/console-excerpt.txt | Partial presenter-provided live recovery excerpt: status query, completion redelivery and one recovered HTTP 503 during the final model response. |
| publication/scripted-completion-summary.json | Additional Linux smoke check of the exact packaged JAR during publication; SCRIPTED_REHEARSAL_ONLY. |

All three reported Windows scenarios passed their invariants with one accepted mock payment record and VERIFIED state. The live completion run had one payment proposal and one status query after UNKNOWN. Its liveAgentRetryProven=false flag is correct: it did not capture a fresh Gemini payment proposal. The separate scripted completion run forced a repeat proposal and reused the verified result.

Full Windows public traces remain on the presenter's laptop. This directory supplies summaries and an excerpt, not a complete live trace or recording. Private H2 journals and credentials are excluded. The reported failure did not recur during these three Windows runs; this does not eliminate model service availability or every possible file-system failure.
