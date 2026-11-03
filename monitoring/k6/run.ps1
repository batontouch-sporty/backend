param(
    [Parameter(Mandatory)][ValidateSet('smoke','list','join')][string]$Scenario,
    [ValidateRange(1,100)][int]$Vus = 100,
    [ValidatePattern('^[1-9][0-9]*(s|m)$')][string]$Duration = '60s'
)
$ErrorActionPreference = 'Stop'
$run = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Force -Path $run | Out-Null
$script = if ($Scenario -eq 'join') { 'join.js' } else { 'list.js' }
$smoke = if ($Scenario -eq 'smoke') { '1' } else { '0' }
if ($Scenario -eq 'join') {
    foreach ($name in @('join-results.json','verification.json')) {
        $old = Join-Path $run $name
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old }
    }
}
$effectiveVus = if ($Scenario -eq 'join') {
    ((Get-Content -Raw (Join-Path $run 'participants.json') | ConvertFrom-Json).members).Count
} elseif ($Scenario -eq 'smoke') { 1 } else { $Vus }
$begin = [DateTimeOffset]::UtcNow
docker run --rm --network sporty-k6-90_default --mount "type=bind,source=$PSScriptRoot,target=/tests,readonly" --mount "type=bind,source=$run,target=/results" grafana/k6:1.0.0 run --quiet --summary-export "/results/$Scenario.json" -e "SMOKE=$smoke" -e "LIST_VUS=$Vus" -e "LIST_DURATION=$Duration" "/tests/$script"
$exitCode = $LASTEXITCODE
@{ scenario=$Scenario; vus=$effectiveVus; duration=$(if ($Scenario -eq 'list') { $Duration } else { 'one iteration per VU' }); start=$begin.ToUnixTimeSeconds(); end=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds(); exitCode=$exitCode } |
    ConvertTo-Json | Set-Content (Join-Path $run "$Scenario-time.json")
if ($exitCode -ne 0) { throw "$Scenario failed (exit $exitCode). Inspect .run/$Scenario.json." }
