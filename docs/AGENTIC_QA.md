# Questions for the Spring AI Gemini demonstration

**Which part is the agent?** The Java agent application runs the model/tool loop. Gemini interprets the supplied task/context and chooses a registered tool. The application executes that choice through domain events and returns authoritative results. Gemini alone is not the entire agent.

**How does Gemini know the business contract?** Every turn supplies business instructions, tool names/descriptions and parameter schemas, together with earlier tool results. The demo retrieves approval, hold and payment status facts explicitly. Training does not grant knowledge of our company database or current approval state.

**How does Java understand the request?** The Gemini response includes structured functionCall parts. Java dispatches only the registered name and allowed arguments. `submit_payment` becomes an ActionProposed event. No application parses free-form prose to authorize money movement.

**What makes this event-driven if it uses HTTP?** HTTP carries broker protocol operations and provider API requests. Domain requests and completion are durable messages; the agent's progress does not depend on receiving an executor's original HTTP response. Transport choice and workflow coordination are different concerns.

**What is specific to agents?** A model can create a fresh semantically equivalent tool request after it receives UNKNOWN or stale context. A new event ID alone would not deduplicate that request. Application-owned business operation identity protects the effect across both fresh model proposals and infrastructure redelivery. The underlying idempotency and transaction patterns also apply to ordinary applications.

**Which application crashes?** In the secondary fault, only the workflow-worker JVM halts after the mock provider commits. The agent/launcher, broker and provider remain running. In the primary fault, no JVM crashes: the agent completion consumer pauses before saving and acknowledging its event.

**Why a deterministic executor and verifier?** Domain code can consistently enforce approval, select trusted money fields and compare provider evidence. This example needs one model planner, not three model agents. A model verifier would introduce additional uncertainty into an exact evidence comparison.

**Is this a Saga?** This candidate coordinates one external accepted payment request. It has no multi-service compensation sequence. Describe it as a durable event-driven workflow with inbox/outbox, idempotency and reconciliation.

**Did Gemini retry?** Read the recorded branch. GEMINI_REPROPOSAL means a fresh submit_payment call after UNKNOWN. GEMINI_STATUS_RECONCILIATION means the model queried durable status. Both can resolve uncertainty, but only the former proves a fresh model payment proposal. SCRIPTED results prove the plumbing and failure drill, not a model's decisions.

**Does an accepted request mean the money settled?** The mock provider saves a payment record. The verifier checks it against the approved payment parameters. Settlement is outside the demo. Real settlement needs its own business event and reconciliation rules.

**Does this guarantee exactly once?** The mock atomically commits the operation key with its accepted payment record. A real provider must offer adequate idempotency and evidence lookup with documented retention and parameter-conflict behavior. Without that protection, retries cannot establish a universal exactly-once external effect guarantee.

**Does this run on Google Cloud?** Gemini runs remotely through Google's API. The Java processes run locally for this rehearsal. A local integration demo does not establish a Pub/Sub, Cloud Run, Kubernetes or AWS deployment.

**Is this production-ready?** It is a narrow reference fixture with test invoice records, one domain worker and a small local broker. Production requires real business authorization, provider contracts, identity/security, broker operations and operational handling of unresolved outcomes. The talk demonstrates architectural boundaries and recovery evidence.

**Where is Spring AI used?** GeminiPlannerModel constructs a Spring AI Prompt and ToolCallback definitions and calls GoogleGenAiChatModel. The application controls the tool loop so journal commits, event publication and completion deadlines remain explicit.

**Why no automatic framework tool loop?** This demo must persist a model decision before dispatching its business request and must return UNKNOWN when completion is not observed. The custom loop owns those checkpoints. Spring AI's low-level ChatModel API supports that orchestration.

**What live Spring AI evidence is available?** The presenter supplied build4 Windows summaries for normal Gemini execution and completion-fault recovery using gemini-3.5-flash-lite, Java 25.0.4.1 and Spring AI 2.0.1. Both passed invariants with one accepted provider record. The fault run chose status reconciliation, not a fresh payment proposal. A separate scripted run tested repeat protection. Full live traces remain on the presenter's laptop; public summaries and a console excerpt have explicit provenance in ../evidence/.

**Does Spring AI retry the payment automatically?** The model adapter disables extra Spring AI and SDK retries. The application owns the saved model API budget. Payment authorization, execution budgets and provider idempotency remain in the domain workflow.
