# Start after a laptop restart

Use the extracted v1.1.0-rc.1 package or the already tested build4 directory. Keep one PowerShell window open throughout setup and presentation. Running a packaged JAR does not require an IDE.

1. Connect power and internet, open PowerShell, and enter the folder containing the JAR.

   ```powershell
   Set-Location "C:\Users\KKM\Documents\agent-retry-demo-checkpoint-build4"
   Test-Path .\agent-retry-demo.jar
   java -version
   ```

   This is the presenter's tested path. Adapt it to your extraction. Test-Path must return True. The reported Windows runtime was Temurin 25.0.4.1.

2. Before going on stage, check local process startup and repeated-call protection.

   ```powershell
   java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode scripted --fault completion --wait-ms 8000
   ```

   Confirm build=1.1.0-candidate-build4-checkpoints, SCRIPTED, submitProposals=2, provider.submissionCalls=1, provider.instructions=1 and invariantsPassed=true. The controlled fault deliberately waits for the tool-observation deadline.

3. Configure the existing private Gemini key and the tested model. Masked entry is needed only when this terminal has no key.

   ```powershell
   if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
       $demoSecureKey = Read-Host "Gemini API key" -AsSecureString
       $env:GEMINI_API_KEY = ([System.Net.NetworkCredential]::new("", $demoSecureKey)).Password
   }
   $env:GEMINI_MODEL = "gemini-3.5-flash-lite"
   ```

   PowerShell $env: assignments apply to this process and its children. Windows user environment variables configured persistently are inherited by newly launched terminals. Enter credentials before screen sharing.

4. Check live model connectivity before the talk.

   ```powershell
   java -jar .\agent-retry-demo.jar agentic preflight --mode gemini --check-model
   ```

   Check build4, GEMINI, SPRING_AI_GOOGLE_GENAI_2.0.1, the intended model, and MODEL_CONNECTED. A preflight success does not guarantee the later workflow call will succeed. If a 503 or timeout occurs, retry the preflight once. If it remains unavailable, use the explicitly labelled captured evidence and local scripted scenario.

5. Prepare the architecture diagram and a saved successful live run. The presenter's build4 live recovery directory was runs/agentic-ba50c192-06d6-4f70-9f7c-67c90b84b26f. Its full agent-trace.jsonl remains on that laptop. This public package supplies its reported summary under evidence/windows/live-completion/.

6. On stage, clear the display and run the main live fault.

   ```powershell
   Clear-Host
   java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode gemini --fault completion --wait-ms 8000
   ```

   Explain tool selection, the application-owned operation identity, and which consumer is paused. Watch UNKNOWN and the model's actual next tool. Check the final summary rather than relying on final model prose. Keep the planned three-minute live window; move to captured evidence if the live call overruns.

7. Show the controlled repeated-tool case.

   ```powershell
   java "-Ddemo.debug=true" -jar .\agent-retry-demo.jar agentic --mode scripted --fault completion --wait-ms 8000
   ```

   Explain that the next tool choices are predefined. Show two submit proposals, verifiedReuse=1, one provider submission/record, delivery count 2 and invariantsPassed=true.

Each scenario gets a fresh runs/agentic-UUID directory by default. Preflight does not create a scenario directory. No reset, deletion, resume, rebuild or cloud deployment is required between these commands. Keep successful evidence intact. Full private H2 journals are not presentation or public-release assets.
