# Demonstration fallback

Prepare one successful live trace before the talk and keep the local scripted scenario runnable. Live Gemini calls have shown variable latency and a recovered HTTP 503; connectivity preflight does not remove that dependency.

The supplied public evidence contains presenter-reported build4 run summaries and a console excerpt. It does not contain the full live agent-trace.jsonl or private H2 journals. The presenter retains the full public trace on the Windows laptop.

Use the planned three-minute live window. If the live call overruns or fails, show saved successful evidence and state that it is a previous live run. Then demonstrate the local repeated-tool fixture and identify its decisions as programmed.

```powershell
.\scripts\replay-captured.ps1
java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode scripted --fault completion --wait-ms 8000
```

The playback script only reads packaged JSON. It does not contact Gemini, submit a payment or resume a workflow. The scripted scenario launches the local JVMs and tests real event processing against the mock provider, but makes no model API call.

Evidence to highlight: one accepted provider record, one submission, VERIFIED, completion redelivery and invariantsPassed=true. The live capture's recovery choice was a status query. The scripted case forced a new submit request. Keep those two claims separate.

Do not delete successful run directories to prepare a fresh agentic run; new scenario directories are generated automatically. Do not distribute private model journals or run databases as backup assets.
