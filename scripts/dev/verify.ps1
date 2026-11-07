# 端到端自检：Web → API → MySQL，以及 Runner 与五个依赖的健康。
# 任一检查失败以非 0 退出，便于接进 accept.py / CI。
param(
    [string]$Root = ''
)

$ErrorActionPreference = 'Continue'

if (-not $Root) {
    if ($PSScriptRoot) {
        $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    } else {
        $Root = (Get-Location).Path
    }
}
$script:failed = 0

function Check([string]$name, [scriptblock]$probe) {
    try {
        $detail = & $probe
        Write-Host ("[PASS] {0}: {1}" -f $name, $detail)
    } catch {
        $script:failed++
        Write-Host ("[FAIL] {0}: {1}" -f $name, $_.Exception.Message)
    }
}

function Get-Body($response) {
    # PowerShell 5.1 的 Invoke-WebRequest 在 text/plain 下会把 Content 返回成 byte[]
    if ($response.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($response.Content)
    }
    return [string]$response.Content
}

function Invoke-Mysql([string]$sql) {
    # SQL 走 stdin，避免 PowerShell 把 -e "select ..." 的引号拆坏
    $sql | & docker compose exec -T mysql sh -c 'mysql -ularder -p"$MYSQL_PASSWORD" -N -B larder'
    if ($LASTEXITCODE -ne 0) { throw "mysql exit=$LASTEXITCODE" }
}

function Compose-Exec([string]$service, [string[]]$argv) {
    $out = (& docker compose exec -T $service @argv 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "exit=$LASTEXITCODE :: $out" }
    return $out
}

Push-Location $Root
try {
    Check 'web /healthz' {
        $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/healthz' -TimeoutSec 10
        if ($r.StatusCode -ne 200) { throw "status=$($r.StatusCode)" }
        $body = (Get-Body $r).Trim()
        if ($body -ne 'ok') { throw "body=$body" }
        'ok'
    }

    Check 'web -> api -> mysql (/api/ping)' {
        $r = Invoke-RestMethod 'http://127.0.0.1:8080/api/ping' -TimeoutSec 15
        if ($r.schemaVersion -ne 'V1') { throw "schemaVersion=$($r.schemaVersion)" }
        "service=$($r.service) version=$($r.version) schemaVersion=$($r.schemaVersion)"
    }

    Check 'api /actuator/health' {
        $raw = Compose-Exec 'web' @('wget', '-qO-', 'http://api:8080/actuator/health')
        $json = $raw | ConvertFrom-Json
        if ($json.status -ne 'UP') { throw "status=$($json.status)" }
        $json.status
    }

    Check 'runner /v1/health' {
        $raw = Compose-Exec 'web' @('wget', '-qO-', 'http://runner:8000/v1/health')
        $json = $raw | ConvertFrom-Json
        if ($json.status -ne 'ok') { throw "status=$($json.status)" }
        "version=$($json.version) protocol=$($json.protocolVersion)"
    }

    Check 'minio /minio/health/live' {
        Compose-Exec 'web' @('wget', '-qO-', 'http://minio:9000/minio/health/live') | Out-Null
        'live'
    }

    Check 'mysql flyway history' {
        $row = (Invoke-Mysql 'select version, success from flyway_schema_history order by installed_rank desc limit 1' | Out-String).Trim()
        if ($row -notmatch '^1\s+1$') { throw "unexpected: $row" }
        $row
    }

    Check 'mysql app_meta row' {
        $row = (Invoke-Mysql "select meta_value from app_meta where meta_key = 'schema_version'" | Out-String).Trim()
        if ($row -ne 'V1') { throw "unexpected: $row" }
        'schema_version=V1'
    }

    Check 'meili /health' {
        $raw = Compose-Exec 'web' @('wget', '-qO-', 'http://meili:7700/health')
        $json = $raw | ConvertFrom-Json
        if ($json.status -ne 'available') { throw "status=$($json.status)" }
        $json.status
    }

    Check 'rabbitmq ping' {
        Compose-Exec 'rabbitmq' @('rabbitmq-diagnostics', '-q', 'ping') | Out-Null
        'pong'
    }

    Check 'redis ping' {
        $pong = (Compose-Exec 'redis' @('redis-cli', 'ping'))
        if ($pong -ne 'PONG') { throw "reply=$pong" }
        'PONG'
    }
} finally {
    Pop-Location
}

if ($script:failed -gt 0) {
    Write-Host "FAILED checks: $script:failed"
    exit 1
}
Write-Host 'ALL CHECKS PASSED'
exit 0
