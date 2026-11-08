# V02-T02 产物链路验证：真实 MinIO + 真实 S3 SDK + 真实 MySQL 迁移。
# 前置：Docker Desktop 已启动；.env 已有 MinIO 账号。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
Add-Type -AssemblyName System.Net.Http

$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$name, [scriptblock]$body) {
    try {
        $detail = & $body
        Write-Host ("[PASS] {0}: {1}" -f $name, $detail)
    } catch {
        $script:failures.Add("${name}: $($_.Exception.Message)")
        Write-Host ("[FAIL] {0}: {1}" -f $name, $_.Exception.Message)
    }
}
function Assert-True([bool]$cond, [string]$message) {
    if (-not $cond) { throw $message }
}
function Mysql([string]$sql) {
    $out = ($sql | & docker compose exec -T mysql sh -c 'mysql -ularder -p"$MYSQL_PASSWORD" -N -B larder' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $out" }
    return $out
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V02-T02') | Out-Null

Write-Host '== 启动 MinIO（开发端口）=='
& docker compose -f compose.yaml -f compose.dev.yaml up -d --wait minio | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'minio 启动失败' }

$envFile = Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' }
$get = { param($k) ($envFile | Where-Object { $_ -match "^$k=" } | ForEach-Object { $_.Split('=', 2)[1] }).Trim() }
$env:LARDER_TEST_S3_ENDPOINT = 'http://127.0.0.1:9000'
$env:LARDER_TEST_S3_ACCESS_KEY = & $get 'LARDER_MINIO_ROOT_USER'
$env:LARDER_TEST_S3_SECRET_KEY = & $get 'LARDER_MINIO_ROOT_PASSWORD'
$env:LARDER_TEST_S3_BUCKET = 'larder-it-artifacts'

Check 'MinIO 端口可达' {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try {
            $code = & curl.exe -sS -o NUL -w "%{http_code}" --max-time 3 http://127.0.0.1:9000/minio/health/live 2>$null
            if ($code -eq '200') { return 'health/live=200' }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'MinIO 60 秒内未就绪'
}

Check '真实 S3 集成测试（写入/读取/幂等/拒绝覆盖）' {
    Push-Location backend
    try {
        $log = & .\mvnw.cmd -B -ntp -f pom.xml -Dtest=S3ObjectStoreIT -DfailIfNoTests=true test 2>&1 | Out-String
        $code = $LASTEXITCODE
        $log | Out-File (Join-Path $root 'reports\V02-T02\s3-it.log') -Encoding utf8
        Assert-True ($code -eq 0) "mvn test exit=$code"
        Assert-True ($log -match 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') '没有看到 1/0/0/0 结果'
        'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'
    } finally {
        Pop-Location
    }
}

Check 'V3 迁移已应用到真实 MySQL' {
    Push-Location backend
    try {
        & .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
        Assert-True ($LASTEXITCODE -eq 0) 'backend jar 打包失败'
    } finally {
        Pop-Location
    }
    & docker compose build api | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
    & docker compose up -d --wait api web | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) 'api/web 启动失败'

    $deadline = (Get-Date).AddSeconds(120)
    $up = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String)
            if ($h -match '"status":"UP"') { $up = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Assert-True $up 'api 120 秒内未 UP'

    $row = Mysql "select version,success from flyway_schema_history where version='3'"
    Assert-True ($row -eq "3`t1") "V3 迁移记录异常: $row"
    'flyway V3 success=1'
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL ARTIFACT CHECKS PASSED'
exit 0
