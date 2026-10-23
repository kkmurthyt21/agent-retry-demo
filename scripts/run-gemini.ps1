param(
    [ValidateSet('none','completion','worker')][string]$Fault = 'none',
    [ValidateSet('approved','hold')][string]$Case = 'approved'
)
$ErrorActionPreference = 'Stop'
if (-not $env:GEMINI_API_KEY -and -not $env:GOOGLE_API_KEY) { throw 'Set GEMINI_API_KEY locally; do not paste it into this script.' }
if (-not $env:GEMINI_MODEL) { throw 'Set GEMINI_MODEL to the model you tested.' }
$jarPath = Join-Path $PSScriptRoot '../agent-retry-demo.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { $jarPath = Join-Path $PSScriptRoot '../target/agent-retry-demo.jar' }
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Cannot find agent-retry-demo.jar. Extract the release package or build the Maven project first.' }
& java -jar $jarPath agentic preflight --mode gemini --check-model
if ($LASTEXITCODE -ne 0) { throw 'Gemini preflight failed.' }
& java -jar $jarPath agentic --mode gemini --fault $Fault --case $Case --wait-ms 8000
if ($LASTEXITCODE -ne 0) { throw 'Workflow validation failed. Inspect the role logs and run summary.' }
