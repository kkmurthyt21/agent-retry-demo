# Seven-minute agent and reliability demo

Use the tested build4 JAR. Run setup and connectivity checks before the talk; see REHEARSAL_CHECKLIST.md.

| Time | Screen and explanation |
| --- | --- |
| 0:00–1:00 | Architecture: one Java agent application uses Gemini; a durable local broker connects it to the workflow application; Executor and Verifier run inside that workflow JVM; the mock provider owns its accepted records. |
| 1:00–4:00 | Launch the live completion-fault case. While the model responds, explain registered tools, application-owned payment parameters and the operation identity. Observe the fault and actual recovery choice. |
| 4:00–5:00 | Inspect the summary: one accepted record, VERIFIED, completion redelivery, invariants passed. The reported Gemini run recovered by status query. |
| 5:00–6:00 | Run the programmed repeated-tool case: another submit request reuses the verified operation; two proposals produce one provider submission and record. |
| 6:00–7:00 | Connect the outcome to stable business identity, durable state, provider evidence and inbox/outbox. New model-call and event IDs can still represent the same approved operation. |

## Main live command

```powershell
java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode gemini --fault completion --wait-ms 8000
```

| Trace marker | Explain |
| --- | --- |
| TOOL_PROPOSED | Gemini selected one of the registered tools; the Java agent application journals and dispatches it as an event. |
| COMPLETION_CONSUMER_PAUSED | The Java agent application's completion consumer pauses after claiming the completion event, before recording it or ACK. No JVM crashes in this fault. |
| TOOL_UNKNOWN | The bounded observation wait expired. UNKNOWN describes the agent's observation; the workflow/provider may already have completed their work. |
| Next TOOL_PROPOSED | Describe the actual tool. The recorded live run selected get_payment_status; a future run might select submit_payment again. |
| EVENT_OBSERVED, WorkflowVerified, deliveryCount=2 | The earlier unacknowledged completion event was redelivered. This is distinct from a fresh model tool request. |
| RUN_SUMMARY | Check structured evidence: provider.instructions=1, workflow.state=VERIFIED, completionFaultTriggered=true, maxCompletionDeliveryCount>=2 and invariantsPassed=true. |

VERIFIED confirms the accepted mock payment record matches the approved request. Settlement is outside the demo. A final model sentence cannot change the authoritative state.

## Controlled repeated-call case

```powershell
java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode scripted --fault completion --wait-ms 8000
```

The test model follows predefined decisions and calls submit_payment again after UNKNOWN. Broker, workflow, Executor, Verifier, H2 stores and mock provider still run. Expected: SCRIPTED_REPROPOSAL, submitProposals=2, freshPaymentProposalsAfterUnknown=1, verifiedReuse=1, one provider submission/accepted record and invariantsPassed=true. Label this as a controlled repeated-tool test.

## Live evidence and timing

The successful Windows build4 run reported GEMINI_STATUS_RECONCILIATION with one status query after UNKNOWN, zero fresh payment proposals, completion delivery count 2 and one accepted provider record. liveAgentRetryProven=false accurately reflects that branch. A separate HTTP 503 during the final model response recovered on the second model API attempt; it did not submit a payment again.

Observed individual model turns took roughly 27–36 seconds in the supplied excerpts. The full run duration is not established by those partial excerpts. Use the saved successful trace as a labelled fallback if the live window overruns. Public playback displays a reported summary and makes no API call:

```powershell
.\scripts\replay-captured.ps1
```

If Gemini is unavailable, present the saved real-run evidence and the explicitly scripted fault drill. Do not describe playback or a scripted decision as a new live model response.
