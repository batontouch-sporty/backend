param([Parameter(Mandatory)][ValidateSet('smoke','list','join')][string]$Scenario)
$ErrorActionPreference = 'Stop'
$run = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Force -Path $run | Out-Null
$script = if ($Scenario -eq 'join') { 'join.js' } else { 'list.js' }
$smoke = if ($Scenario -eq 'smoke') { '1' } else { '0' }
$begin = [DateTimeOffset]::UtcNow
docker run --rm --network sporty-k6-90_default --mount "type=bind,source=$PSScriptRoot,target=/tests,readonly" --mount "type=bind,source=$run,target=/results" grafana/k6:1.0.0 run --quiet --summary-export "/results/$Scenario.json" -e "SMOKE=$smoke" "/tests/$script"
$exitCode = $LASTEXITCODE
@{ start=$begin.ToUnixTimeSeconds(); end=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds(); exitCode=$exitCode } |
    ConvertTo-Json | Set-Content (Join-Path $run "$Scenario-time.json")
if ($exitCode -ne 0) { throw "$Scenario failed (exit $exitCode). Inspect .run/$Scenario.json." }
