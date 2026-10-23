# Spring AI Gemini integration

The live agent uses Spring AI 2.0.1 `GoogleGenAiChatModel` with Google's Gemini Developer API. The model ID remains explicit in `GEMINI_MODEL`. The API key stays in `GEMINI_API_KEY`, with `GOOGLE_API_KEY` as the fallback. The framework and its runtime dependencies are packaged in the runnable JAR.

The adapter is configured programmatically. The demo uses Spring AI without a Spring Boot web application. Its four Java processes and durable event protocol remain the same.

## Actual execution path

1. `GeminiPlannerModel.request` creates a Spring AI `Prompt` with a `SystemMessage`, conversation messages and three `ToolCallback` definitions from `agentic/tools.json`.
2. `GoogleGenAiChatModel.call` sends the prompt and schemas to Gemini using the Google GenAI client. Tool choice is AUTO. The application never fabricates a live model decision.
3. Spring AI returns the response. The adapter preserves the provider's full content parts in private response metadata and decodes the requested function names and arguments.
4. `AgenticPlanner` validates each request, saves the pending call and command outbox, and publishes the corresponding versioned event.
5. The domain worker enforces policy, executes or recovers the same approved operation, and verifies the provider record.
6. The agent correlates completion events with tool requests, persists their results and supplies function responses on the next model request.

Spring AI's low-level `ChatModel` does not execute tools automatically. The registered callbacks provide schemas. Their direct execution throws `DURABLE_EVENT_DISPATCH_REQUIRED`, because the application must dispatch the selected requests through its journal and event outbox. No callback calls the payment provider directly.

## Preserving Gemini protocol data

Spring AI 2.0.1 reconstructs Gemini content from its message abstractions and omits provider function-call IDs during conversion. The demo must retain those IDs, signed parts and their ordering across an agent restart.

The adapter uses two explicit extension points:

- A small `GoogleGenAiChatModel` subclass retains the SDK candidate's original content as private response metadata.
- The Google SDK's `HttpOptions.extraBody` supplies the journal's unchanged `contents` on the next call. Spring AI supplies the system prompt, registered tool schemas, tool choice and model call.

The public trace filters thought text and signatures. Private H2 conversation journals are excluded from release packages. Loopback protocol tests verify the actual HTTP bodies, including parallel call IDs and signatures after restart. No copied model text is presented as a real Gemini decision.

## Retry and timeout ownership

Spring AI's retry policy permits zero additional retries. The Google SDK is configured for one attempt. The saved application counter therefore remains the owner of the three-attempt model API budget. A protocol test sends HTTP 429 and verifies exactly three HTTP requests for three application attempts.

The adapter reports wait progress every five seconds and bounds a model call to 45 seconds. It cancels the waiting task and configures the underlying SDK HTTP timeout as well. These model API attempts are separate from workflow execution attempts and broker deliveries.

The build imports the Jackson 3.1.5 BOM used by Spring AI's common module. This aligns the shared annotation library while retaining the legacy Jackson 2 event/schema implementation. The shaded JAR merges service-provider resources and has its own separate-process Spring AI protocol test.

## Rehearsal

Use a fresh extraction and data directory for this candidate. Resume requires the same mode, model and model integration. Previous REST-candidate run directories remain baseline evidence and should not be resumed by this build.

```powershell
$env:GEMINI_MODEL = "gemini-3.5-flash-lite"
java -jar .\agent-retry-demo.jar agentic preflight --mode gemini --check-model
java -jar .\agent-retry-demo.jar agentic --mode gemini --fault none --wait-ms 8000
java -jar .\agent-retry-demo.jar agentic --mode gemini --fault completion --wait-ms 8000
```

Preflight must identify `integration=SPRING_AI_GOOGLE_GENAI_2.0.1`. A successful model connectivity check prints `MODEL_CONNECTED`. Inspect the final summary's `modelIntegration`, `observedBehavior`, provider record count and invariants.

The presenter supplied successful Windows build4 runs of normal live Gemini execution, live completion-fault recovery and a separate scripted repeated-tool scenario using Java 25.0.4.1. The live fault run chose status reconciliation and passed invariants with one provider record. Full automated verification recorded 95 passing tests on Linux JDK 17; protocol tests remain separately labelled. See TEST_RESULTS.md and ../evidence/README.md for provenance and limits.

## Primary references

- https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html
- https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html
- https://ai.google.dev/gemini-api/docs/function-calling
- https://ai.google.dev/gemini-api/docs/thought-signatures
