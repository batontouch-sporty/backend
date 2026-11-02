param([Parameter(Mandatory)][ValidateSet('up','down')][string]$Action)
$ErrorActionPreference = 'Stop'
$keyDir = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Sporty/sporty-k6-90'
$keyPath = Join-Path $keyDir 'jwt.key'
$tokens = Join-Path $PSScriptRoot '.run/participants.json'
Push-Location (Join-Path $PSScriptRoot '../..')
try {
    # Restore port overrides when the script finishes.
    $settings = @{
        GATEWAY_HOST_PORT='18090'; MARIADB_HOST_PORT='13316'; REDIS_HOST_PORT='16389'
        KAFKA_HOST_PORT='19092'; ZIPKIN_HOST_PORT='19411'; PROMETHEUS_HOST_PORT='19090'
        GRAFANA_HOST_PORT='13001'; ALERTMANAGER_HOST_PORT='19093'
    }
    $previous = @{}
    if ($Action -eq 'up') {
        [IO.Directory]::CreateDirectory($keyDir) | Out-Null
        if (-not [IO.File]::Exists($keyPath)) {
            $bytes = [byte[]]::new(48)
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            $temp = Join-Path $keyDir ([Guid]::NewGuid().ToString('N'))
            try {
                [IO.File]::WriteAllText($temp, [Convert]::ToBase64String($bytes))
                # Publish only a complete key. A concurrent up may have created it first.
                try { [IO.File]::Move($temp, $keyPath) }
                catch [IO.IOException] { if (-not [IO.File]::Exists($keyPath)) { throw } }
            } finally { [IO.File]::Delete($temp) }
        }
        $settings.JWT_SECRET_KEY = [IO.File]::ReadAllText($keyPath)
        if ($settings.JWT_SECRET_KEY -cnotmatch '^[A-Za-z0-9+/]{64}$') {
            throw 'Invalid test signing key. Run stack.ps1 down before starting again.'
        }
    } else {
        # down only parses the Compose file. It does not start a token issuer.
        $settings.JWT_SECRET_KEY = 'unused-for-compose-down'
    }
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
    if ($Action -eq 'down' -and [IO.File]::Exists($keyPath)) {
        [IO.File]::Delete($keyPath)
    }
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key]) }
    Pop-Location
    if ($Action -eq 'down' -and (Test-Path -LiteralPath $tokens)) {
        Remove-Item -LiteralPath $tokens
    }
}
