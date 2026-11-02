param([Parameter(Mandatory)][ValidateSet('up','down')][string]$Action)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '../..')
try {
    # Restore port overrides when the script finishes.
    $settings = @{
        GATEWAY_HOST_PORT='18090'; MARIADB_HOST_PORT='13316'; REDIS_HOST_PORT='16389'
        KAFKA_HOST_PORT='19092'; ZIPKIN_HOST_PORT='19411'; PROMETHEUS_HOST_PORT='19090'
        GRAFANA_HOST_PORT='13001'; ALERTMANAGER_HOST_PORT='19093'
    }
    $previous = @{}
    foreach ($key in $settings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $settings[$key])
    }
    if ($Action -eq 'up') {
        docker compose -p sporty-k6-90 up -d --build --wait gateway-service user-service match-service facility-service notification-service prometheus grafana zipkin
    } else {
        docker compose -p sporty-k6-90 down --volumes
    }
    if ($LASTEXITCODE -ne 0) { throw "Compose $Action failed" }
    if ($Action -eq 'up') {
        # Docker health can turn green before Eureka starts routing requests.
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            try {
                $response = Invoke-WebRequest 'http://127.0.0.1:18090/match-service/api/matches' -TimeoutSec 4 -UseBasicParsing
                if ($response.StatusCode -eq 200) { $ready = $true; break }
            } catch { Start-Sleep -Seconds 3 }
        }
        if (-not $ready) { throw 'Gateway did not serve the match list within the startup window.' }
    }
    if ($Action -eq 'down') {
        $tokens = Join-Path $PSScriptRoot '.run/participants.json'
        if (Test-Path -LiteralPath $tokens) { Remove-Item -LiteralPath $tokens }
    }
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key]) }
    Pop-Location
}
