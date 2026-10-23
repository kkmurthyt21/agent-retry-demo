# Gemini setup for build4

Build: 1.1.0-candidate-build4-checkpoints. Integration: Spring AI 2.0.1 GoogleGenAiChatModel with the Gemini Developer API. This is a standalone Java application without Spring Boot.

The packaged JAR needs a Java runtime. Live mode additionally needs a privately configured GEMINI_API_KEY (GOOGLE_API_KEY is the fallback), an explicit tested GEMINI_MODEL and internet access. The agent process owns model credentials; the broker, workflow and mock provider run locally without receiving those credentials.

The presenter supplied successful Windows Temurin 25.0.4.1 runs of build4 with gemini-3.5-flash-lite. Earlier gemini-3.8-flash calls experienced 503s/timeouts. These observations are from the reported session and are not a global model-service availability assessment.

```powershell
$env:GEMINI_MODEL = "gemini-3.5-flash-lite"
java -jar .\agent-retry-demo.jar agentic preflight --mode gemini --check-model
java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode gemini --fault none --wait-ms 8000
java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode gemini --fault completion --wait-ms 8000
```

Run one command at a time from the folder containing the JAR. Configure the key before projecting the terminal. For a complete fresh-start sequence and masked key entry, read REHEARSAL_CHECKLIST.md. Shell $env: assignments do not persist after that terminal closes; Windows user-level settings are inherited by new processes.

Preflight prints build, source, integration and model, then MODEL_CONNECTED on success. It is a model connectivity check. The normal and completion-fault commands exercise the event-driven workflow. The model adapter does not fall back to scripted mode after an API error.

Each scenario uses a fresh run directory by default. The public agent-trace.jsonl and run-summary.json distinguish model decisions, event deliveries and authoritative results. The --wait-ms value bounds tool observation, not the model request. Each model API attempt has a separate 45-second deadline and five-second wait progress; the application saves a three-attempt budget per pending turn. Model turns and calls per response are also bounded. See SPRING_AI_INTEGRATION.md.

| Evidence | Meaning |
| --- | --- |
| LIVE_API_RUN | A real Gemini API supplied model replies |
| GEMINI_STATUS_RECONCILIATION | Gemini queried durable status after UNKNOWN |
| GEMINI_REPROPOSAL | Gemini selected a fresh submit_payment request after UNKNOWN |
| liveAgentRetryProven=true | A real Gemini payment reproposal occurred and the scenario invariants passed |
| SCRIPTED_REHEARSAL_ONLY | A programmed fixture supplied tool choices |
| PROTOCOL_TEST | A local protocol server supplied simulated Gemini responses |

In the reported live build4 completion run, Gemini reconciled status; liveAgentRetryProven=false is correct. The separate scripted case exercised repeated submit protection. Read TEST_RESULTS.md and ../evidence/README.md for provenance and observed limits.

Optional secondary scenarios use --fault worker or --case hold. The worker fault restarts only the workflow JVM after provider commit; the hold case must create no provider record. Packaged integration tests cover them. They are not claimed as additional Windows build4 live rehearsals.

Resume requires an existing matching mode/model/integration/fault/case configuration. Use fresh directories for the conference walkthrough. Earlier REST/build3 run directories are historical evidence and should not be resumed using build4.

Primary references:

- https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html
- https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html
- https://ai.google.dev/gemini-api/docs/function-calling
