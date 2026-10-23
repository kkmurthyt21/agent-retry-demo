# Follow a model-selected tool through Java and events

1. AgenticDemo launches the local broker, mock provider and workflow JVMs, waits for readiness, then starts the agent loop with the user task.
2. GeminiPlannerModel uses Spring AI GoogleGenAiChatModel to send the system instructions, registered tool schemas and current conversation. Business facts arrive through explicit tool results.
3. Gemini returns structured function-call parts. The adapter preserves provider call IDs and signed content in the private journal for protocol continuity. Java accepts only registered tool names and allowed invoiceId arguments.
4. AgenticPlanner saves the selected tool call and its command outbox before dispatching a versioned domain event. get_invoice_context maps to ContextRequested, get_payment_status to StatusRequested, and submit_payment to ActionProposed.
5. AgenticWorkflow checks scope and current policy and saves the approved parameters with its PLANNED transition, event inbox and TaskPlanned outbox. Trusted payment fields and operation identity belong to application code, not model arguments.
6. The execution handler saves EXECUTING and its attempt, then Executor looks for matching provider evidence before submitting. The provider atomically protects the key, request fingerprint and accepted payment record.
7. The workflow saves the receipt and EXECUTED state and publishes ActionCompleted. Verifier reads the provider record and compares it with the approved request; the workflow saves VERIFIED and publishes WorkflowVerified.
8. The Java agent application correlates and persists a completion result before ACK and supplies it to Gemini on the next turn.

The operation identity demo-tenant:INV-1042:approval-v1:submit-payment survives both a new model tool-call ID and a new event ID for the same approved action.

## Completion observation fault

The completion consumer in the Java agent application pauses after claiming WorkflowVerified, before saving its result or acknowledging delivery. After the observation deadline, the agent returns UNKNOWN to the model. The accepted provider record and workflow state already exist. The unacknowledged completion event can redeliver.

In the reported Windows live run, Gemini chose get_payment_status and observed VERIFIED. It did not submit a fresh payment request. In the scripted fixture, ScriptedPlannerModel deliberately selected submit_payment again; the workflow reused the saved verified result and the provider still had one accepted record.

## Legacy proposal interface

AgentDecisionProvider and ScriptedAgentDecisionProvider remain part of the earlier offline demo command family. The current agentic live path uses PlannerModel, GeminiPlannerModel and AgenticPlanner. Do not use the legacy fixture interface to claim how the live Spring AI adapter is connected.
