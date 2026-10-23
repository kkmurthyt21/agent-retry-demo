param(
    [ValidateSet('live-completion','live-normal','scripted-completion')]
    [string]$Scenario = 'live-completion'
)
$ErrorActionPreference = 'Stop'
$demoEvidenceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'evidence/windows'
$demoSummaryPath = Join-Path (Join-Path $demoEvidenceRoot $Scenario) 'run-summary.json'
if (-not (Test-Path -LiteralPath $demoSummaryPath)) { throw "Cannot find packaged evidence: $demoSummaryPath" }
Write-Host "SAVED PRESENTER-REPORTED EVIDENCE: $Scenario; build4; Windows Java 25.0.4.1"
Write-Host 'Playback only: no model API call, payment submission or workflow resume.'
$demoCapture = Get-Content -LiteralPath $demoSummaryPath -Raw | ConvertFrom-Json
$demoCapture | ConvertTo-Json -Depth 10
