param([string]$Jar = "agent-retry-demo.jar")
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
function Invoke-Demo([string]$Scenario, [int]$ExpectedExit = 0) {
    & java -jar $Jar demo $Scenario
    if ($LASTEXITCODE -ne $ExpectedExit) {
        throw "Unexpected exit code $LASTEXITCODE for $Scenario. Expected $ExpectedExit."
    }
}
Invoke-Demo "reset"
Invoke-Demo "preflight"
Invoke-Demo "normal"
Invoke-Demo "boundaries"
Invoke-Demo "crash" 23
Invoke-Demo "recover"
