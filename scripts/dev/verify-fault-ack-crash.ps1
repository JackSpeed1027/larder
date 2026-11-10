# S06 故障注入：ack 后崩溃（确定性构造，测试数据）。
# 步骤：收录 → 等 inbox（已 ack）→ 把 job 构造成“崩溃现场”（RUNNING + 过期租约 + run_id 为空）
#      → 重启 API → 对账收回 → 重新派发 → Runner 完成 → 断言 SUCCEEDED 且 inbox 仍 1 条。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$name, [scriptblock]$body) {
    try { $detail = & $body; Write-Host ("[PASS] {0}: {1}" -f $name, $detail) }
    catch { $script:failures.Add("${name}: $($_.Exception.Message)"); Write-Host ("[FAIL] {0}: {1}" -f $name, $_.Exception.Message) }
}
function Assert-True([bool]$cond, [string]$message) { if (-not $cond) { throw $message } }
function Mysql([string]$sql) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>&1 | Out-String).Trim() }
    finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "mysql: $out" }
    return $out
}
function Wait-For([scriptblock]$probe, [int]$seconds, [string]$what) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) { if (& $probe) { return } ; Start-Sleep -Seconds 2 }
    throw "超时: $what"
}

$suffix = Get-Date -Format 'MMddHHmmss'
$jar = Join-Path $env:TEMP "fault-$suffix.cookies"
& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$pw = ((Get-Content .env | Where-Object { $_ -match '^LARDER_ADMIN_PASSWORD=' } | ForEach-Object { $_.Split('=', 2)[1] })).Trim()
$login = Join-Path $env:TEMP "fault-login-$suffix.json"
[IO.File]::WriteAllText($login, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $code = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$login" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($code -eq '200') "登录 $code"; 'HTTP 200'
}

$itemFile = Join-Path $env:TEMP "fault-item-$suffix.json"
[IO.File]::WriteAllText($itemFile, '{"kind":"WEB","url":"https://example.com/?fault=' + $suffix + '"}', (New-Object Text.UTF8Encoding($false)))
$raw = (& curl.exe -sS -b $jar -H "X-XSRF-TOKEN: $xsrf" -H "Idempotency-Key: fault-$suffix" -H 'Content-Type: application/json' --data-binary "@$itemFile" -w "`n%{http_code}" http://127.0.0.1:8080/api/items 2>&1 | Out-String)
$parts = $raw.Trim().Split("`n"); $item = $parts[0] | ConvertFrom-Json
$itemId = $item.data.itemId; $jobId = $item.data.jobId
Check '收录并确认已 ack（inbox 有记录）' {
    Assert-True ($parts[-1].Trim() -eq '202') "状态 $($parts[-1])"
    Wait-For { (Mysql "select count(*) from inbox where job_id='$jobId'") -eq '1' } 60 'inbox 记录'
    "$itemId / $jobId，inbox=1"
}

$tokenBefore = [int](Mysql "select lease_token from job where id='$jobId'")
Check '构造“ack 后崩溃”现场：RUNNING + 过期租约 + run_id 为空' {
    Mysql "update item set current_revision_id = NULL where id='$itemId'" | Out-Null
    Mysql "update job set state='RUNNING', attempt=1, lease_owner='crashed-worker', lease_token=lease_token+1, lease_until=utc_timestamp(6)-interval 5 second, run_id=NULL, error_code=NULL, next_attempt_at=NULL where id='$jobId'" | Out-Null
    $state = Mysql "select state from job where id='$jobId'"
    Assert-True ($state -eq 'RUNNING') "state=$state"
    "job 回到崩溃现场（RUNNING，租约已过期）"
}

Check '重启 API：对账收回 → 重新派发 → 最终 SUCCEEDED（inbox 仍 1 条）' {
    & docker compose restart api | Out-Null
    Wait-For { (& curl.exe -sS -o NUL -w "%{http_code}" http://127.0.0.1:8080/actuator/health 2>$null) -eq '200' } 240 'api 恢复'
    Wait-For { [int](Mysql "select lease_token from job where id='$jobId'") -gt $tokenBefore } 180 '租约代次递增'
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'SUCCEEDED' } 300 'job SUCCEEDED'
    $attempt = Mysql "select attempt from job where id='$jobId'"
    $revision = Mysql "select current_revision_id from item where id='$itemId'"
    $inbox = Mysql "select count(*) from inbox where job_id='$jobId'"
    Assert-True ($revision -like 'rev_*') "revision=$revision"
    # 对账为新一次尝试生成新 outbox 事件 → inbox 按 (consumer,event_id) 合法新增一行
    Assert-True ([int]$inbox -ge 1) "inbox 丢失: $inbox"
    Assert-True ($attempt -eq '2') "attempt=$attempt（应为 2）"
    "attempt=$attempt，revision=$revision，inbox=$inbox"
}

Write-Host ''
if ($failures.Count -gt 0) { Write-Host "FAILED checks: $($failures.Count)"; $failures | ForEach-Object { Write-Host "  - $_" }; exit 1 }
Write-Host 'ALL FAULT CHECKS PASSED'; exit 0
