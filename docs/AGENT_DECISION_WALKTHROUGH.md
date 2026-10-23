# Follow a model-selected tool through Java and events

1. AgenticDemo launches the local broker, mock provider and workflow JVMs, waits for readiness, then starts the agent loop with the user task.
2. GeminiPlannerModel uses Spring AI GoogleGenAiChatModel to send the system instructions, registered tool schemas and current conversation. Business facts arrive through explicit tool results.
3. Gemini returns structured function-call parts, for example `{"functionCall":{"name":"submit_payment","args":{"invoiceId":"INV-1042"}}}`. This requests a registered Java application function; it does not contact the payment provider. The adapter preserves provider call IDs and signed content in the private journal for protocol continuity. Java accepts only registered tool names and allowed invoiceId arguments.
4. AgenticPlanner saves the selected tool call and its command outbox before dispatching a versioned domain event. get_invoice_context maps to ContextRequested, get_payment_status to StatusRequested, and submit_payment to ActionProposed.
5. AgenticWorkflow checks scope and current policy and saves the approved parameters with its PLANNED transition, event inbox and TaskPlanned outbox. Trusted payment fields and operation identity belong to application code, not model arguments.
6. When the workflow worker consumes TaskPlanned, AgenticWorkflow.handle(...) saves EXECUTING and its attempt count, then calls Executor.execute(...). Executor looks up the same operation at the provider and recovers a matching receipt if present; otherwise it submits the saved approved request. The provider atomically protects the key, request fingerprint and accepted payment record.
7. One workflow transaction saves the receipt and EXECUTED state, records TaskPlanned in the inbox and saves ActionCompleted in the outbox. After publication, the same worker consumes ActionCompleted and calls Verifier.verify(...). Verifier reads the provider's accepted payment record and receipt, checks their identity and compares both request fingerprints with the saved approved request. On a match, AgenticWorkflow saves VERIFIED and emits WorkflowVerified for each waiting caller. Executor and Verifier are ordinary Java classes in this worker, not separate model agents.
8. The Java agent application correlates and persists a completion result before ACK and supplies it to Gemini on the next turn.

The operation identity `demo-tenant:INV-1042:approval-v1:submit-payment` identifies the approved USD 250.00 request for invoice INV-1042 to demo-beneficiary-001, approval version 1. It survives both a new model tool-call ID and a new event ID for that request. `demo-tenant` identifies the example organization using the application. The separate mock provider owns its accepted payment records and receipts; the workflow saves and checks a copy of that evidence. VERIFIED confirms a matching accepted record, not settlement.

## Completion observation fault

The completion consumer in the Java agent application pauses after claiming WorkflowVerified, before saving its result or acknowledging delivery. After the observation deadline, the agent returns UNKNOWN to the model. The accepted provider record and workflow state already exist. The unacknowledged completion event can redeliver.

In the reported Windows live run, Gemini chose get_payment_status and observed VERIFIED. It did not submit a fresh payment request. In the scripted fixture, ScriptedPlannerModel deliberately selected submit_payment again; the workflow reused the saved verified result and the provider still had one accepted record.

## Legacy proposal interface

AgentDecisionProvider and ScriptedAgentDecisionProvider remain part of the earlier offline demo command family. The current agentic live path uses PlannerModel, GeminiPlannerModel and AgenticPlanner. Do not use the legacy fixture interface to claim how the live Spring AI adapter is connected.
