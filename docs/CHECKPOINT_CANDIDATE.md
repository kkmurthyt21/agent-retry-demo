# Build4 checkpoint changes

Runtime: 1.1.0-candidate-build4-checkpoints. Published as the v1.1.0-rc.1 conference prerelease without changing the tested Java code or dependencies.

## Observed problem and changes

A Windows build3 workflow process reported Cannot save run checkpoint. Its wrapper hid the underlying file exception, so the precise original failure remains unconfirmed.

- CheckpointFiles uses a unique temporary file per attempt, atomic replacement first and narrow non-atomic replacement fallback where supported.
- Classified file-system failures receive at most five attempts with delays of 25, 50, 100 and 200 ms. Generic I/O failures are not blindly retried. Interruption stops retrying.
- Exhaustion retains the target path, write stage, attempt count and underlying cause. Tests cover previous-file retention during failed replacement and cleanup behavior.
- Workflow status exports are written only when contents change or the export is missing. H2 remains authoritative.
- The workflow exports committed status before publishing result events.
- The launcher waits for workflow readiness before the first model turn and reports child errors and full log paths.
- The demo.debug JVM property is forwarded to the child JVMs. Quote the entire argument in PowerShell: java "-Ddemo.debug=true" -jar ...

Payment identity, tool schemas, provider rules, workflow transitions and the Gemini adapter were unchanged by this checkpoint fix.

## Validation

The recorded Linux JDK 17 build passed 95 automated tests and a separate 45-second idle-worker fixture. The presenter then supplied three successful Windows Java 25.0.4.1 build4 summaries: scripted completion, live Gemini normal execution and live Gemini completion recovery. The checkpoint error did not recur in those three reported runs. Persistent storage failures can still stop execution; this is not a universal file-system availability guarantee.

The publication smoke check ran the exact packaged JAR's scripted completion scenario again. Public validation provenance is in TEST_RESULTS.md and ../evidence/README.md. Earlier candidate notes that said Windows validation was pending were written before these reported rehearsals.
