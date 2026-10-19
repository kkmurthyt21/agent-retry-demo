# Seven-minute demonstration script

Begin at presentation minute 27. End at minute 34. The terminal is already open beside the deck. The JAR and fresh fixture directory are prepared before speaking. Run each command in a new JVM from the same working directory.

## Before the session

Run `java -version`, then `java -jar agent-retry-demo.jar demo reset` and `java -jar agent-retry-demo.jar demo preflight`. Expect zero instructions and schemas `1.0,1.1`. Use a font readable from the back of the room. Test a long rejection line and the final recovery counts. Keep the backup MP4 and transcript open locally. Do not build or reset during the live demonstration.

## 27:00–28:15: normal workflow

Say: “The request is USD 250 to a synthetic beneficiary. The Planner proposes the instruction. Application policy checks it, the Executor submits it, and the Verifier reads the gateway’s record.”

```text
java -jar agent-retry-demo.jar demo normal
```

Point to `PLANNER`, `GATEWAY`, `EXECUTOR`, and `VERIFIER`. The workflow is `COMPLETED` at version 4. Highlight `plannerRuns=1`, `instructionsForWorkflow=1`, and `totalInstructions=1`.

Say: “One approved instruction produced one acceptance. This is our baseline.”

## 28:15–30:00: duplicate and rejection boundaries

```text
java -jar agent-retry-demo.jar demo boundaries
```

Point to `DUPLICATE_EVENT` first. Say: “The same delivered fact is already processed.” Then point to `REUSED_ACTION`. Say: “This envelope has a fresh event ID but asks for the same completed business action.”

Show `UNSUPPORTED_SCHEMA_VERSION` under `VALIDATION_REJECTED`. Show `BENEFICIARY_DENIED` under `POLICY_REJECTED`. Say: “The second rejection is structurally valid JSON. Policy makes a separate decision.”

Highlight `totalInstructions=1`. None of these inputs added a business instruction. Do not explain every schema keyword or open source files.

## 30:00–31:45: the deliberate crash

Say before running: “The next command will stop its JVM with exit code 23. That is the prepared fault. Watch the gateway count and the unfinished local state.”

```text
java -jar agent-retry-demo.jar demo crash
```

Point to `toolCommitted=true`, `localAcknowledged=false`, `savedState=EXECUTING`, and `localReceiptSaved=false`. There is one instruction for `wf-crash` and two in total, because `wf-normal` is a different intended action.

Say: “The process has stopped. The gateway already accepted the instruction. The workflow has not recorded success. A missing acknowledgement has not undone the acceptance.”

Pause briefly for the audience to predict what recovery should do.

## 31:45–34:00: restart and recover

```text
java -jar agent-retry-demo.jar demo recover
```

Point to `savedState=EXECUTING`, `savedStateVersion=2`, `plannerRuns=1`, and the original action key. This is a new process reading durable state. It does not run the Planner again.

Show `RECOVERED_RECEIPT`. The receipt ID matches the crash command. Then show Verifier completion, `instructionsForWorkflow=1`, `totalInstructions=2`, `additionalInstructionsDuringRecovery=0`, and `gatewaySubmissionCallsThisRun=0`.

Say: “Recovery found the existing acceptance, saved its receipt, and completed the remaining verification. It added no instruction and made no new submission call.”

Return to slide 16 at minute 34: one intended action, one stable key, one accepted instruction.

## Fallback transition

If a command produces an unexpected result, spend at most 20 seconds identifying the observed state. Say: “I have the captured run of this same packaged application. Let’s inspect the boundary there.” Switch to the matching backup chapter and narrate it. The full backup lasts seven minutes; use only the remaining chapters if the live demonstration has already started. Do not reset, rebuild, inspect SQL, or debug onstage.
