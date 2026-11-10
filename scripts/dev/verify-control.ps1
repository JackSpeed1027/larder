# V03-T04 验证：任务中心（列表/详情/取消/重试）+ 取消与发布竞争（A07）。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$n, [scriptblock]$b) {
    try { $d = & $b; Write-Host ("[PASS] {0}: {1}" -f $n, $d) }
    catch { $script:failures.Add("${n}: $($_.Exception.Message)"); Write-Host ("[FAIL] {0}: {1}" -f $n, $_.Exception.Message) }
}
function Assert-True([bool]$c, [string]$m) { if (-not $c) { throw $m } }
function Mysql([string]$sql) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $o = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>&1 | Out-String).Trim() } finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "mysql: $o" }
    return $o
}
function Wait-For([scriptblock]$p, [int]$t, [string]$w) {
    $d = (Get-Date).AddSeconds($t); while ((Get-Date) -lt $d) { if (& $p) { return } ; Start-Sleep -Milliseconds 800 }; throw "超时: $w"
}
function Sha256Hex([string]$s) { $b = [Text.UTF8Encoding]::new($false).GetBytes($s); ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($b)) -replace '-', '').ToLowerInvariant() }

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V03-T04') | Out-Null
Write-Host '== 构建并启动 =='
Push-Location backend; & .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null; Assert-True ($LASTEXITCODE -eq 0) '打包失败'; Pop-Location
& docker compose build api | Out-Null; Assert-True ($LASTEXITCODE -eq 0) '镜像失败'
& docker compose -f compose.yaml -f compose.dev.yaml up -d --wait mysql rabbitmq minio api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'
& docker compose restart web | Out-Null
Check 'api 就绪' {
    $d = (Get-Date).AddSeconds(150); while ((Get-Date) -lt $d) { try { $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String); if ($h -match '"status":"UP"') { return 'UP' } } catch { }; Start-Sleep -Seconds 2 }; throw '未 UP'
}
$jar = Join-Path $env:TEMP 'ctl-cookies.txt'; Remove-Item $jar -ErrorAction SilentlyContinue
& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$envLines = Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' }
$get = { param($k) ($envLines | Where-Object { $_ -match "^$k=" } | ForEach-Object { $_.Split('=', 2)[1] }).Trim() }
$pw = & $get 'LARDER_ADMIN_PASSWORD'; $internal = & $get 'LARDER_INTERNAL_TOKEN'
$lf = Join-Path $env:TEMP 'ctl-login.json'; [IO.File]::WriteAllText($lf, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $c = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$lf" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($c -eq '200') "登录 $c"; 'HTTP 200'
}
function Api([string]$m, [string]$path, [string]$file = '', [switch]$internalCall) {
    $a = @('-sS', '-b', $jar, '-X', $m)
    if ($file) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$file") }
    if ($internalCall) { $a += @('-H', "X-Internal-Token: $internal") } elseif ($m -ne 'GET') { $a += @('-H', "X-XSRF-TOKEN: $xsrf") }
    if ($m -eq 'POST' -and $path -eq '/api/items') { $a += @('-H', ('Idempotency-Key: ctl-' + [guid]::NewGuid().ToString('N').Substring(0, 12))) }
    $a += @('-w', "`n%{http_code}", "http://127.0.0.1:8081$path")
    $raw = (& curl.exe @a 2>&1 | Out-String); $parts = $raw.Trim().Split("`n")
    $json = $null; if ($parts[0].TrimStart().StartsWith('{') -or $parts[0].TrimStart().StartsWith('[')) { $json = $parts[0] | ConvertFrom-Json }
    return @{ status = $parts[-1].Trim(); json = $json; raw = $raw }
}
$suffix = Get-Date -Format 'MMddHHmmss'
$text = "取消/重试验证正文 $suffix"; $sha = Sha256Hex $text
$ib = Join-Path $env:TEMP "ctl-item-$suffix.json"; [IO.File]::WriteAllText($ib, '{"kind":"WEB","url":"https://Example.com/ctl/' + $suffix + '"}', (New-Object Text.UTF8Encoding($false)))
$created = Api 'POST' '/api/items' $ib
$jobId = $created.json.data.jobId; $runId = "run_ctl_$suffix"
Check '收录并进入 RUNNING' {
    Assert-True ($created.status -eq '202') "收录 $($created.status)"
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'RUNNING' } 60 'RUNNING'
    Mysql "update job set run_id='$runId' where id='$jobId'" | Out-Null
    'RUNNING + runId'
}
Check '任务中心列表与详情' {
    $l = Api 'GET' ("/api/jobs?itemId=" + $created.json.data.itemId)
    Assert-True ($l.status -eq '200') "列表 $($l.status)"
    Assert-True (@($l.json.jobs | Where-Object { $_.id -eq $jobId }).Count -eq 1) '列表缺 job'
    $d = Api 'GET' "/api/jobs/$jobId"
    Assert-True ($d.json.id -eq $jobId) '详情缺 job'
    "total=$($l.json.total) state=$($d.json.state)"
}
Check '取消 RUNNING 任务：写 cancel_requested_at' {
    $c = Api 'POST' "/api/jobs/$jobId/cancel"
    Assert-True ($c.status -eq '200') "取消 $($c.status)"
    Assert-True ($null -ne $c.json.cancelRequestedAt) '没有 cancelRequestedAt'
    'cancelRequestedAt 已写'
}
Check '取消写入 CANCEL_REQUESTED 事件（S05）' {
    $sql = "select count(*) from job_event where job_id='" + $jobId + "' and event_type='CANCEL_REQUESTED'"
    $count = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' | Out-String).Trim()
    Assert-True ($count -ge '1') "CANCEL_REQUESTED 事件数=$count（应为 >=1）"
    "CANCEL_REQUESTED=$count"
}

Check '取消后迟到产物不能发布（A07）' {
    $rb = Join-Path $env:TEMP "ctl-result-$suffix.json"
    $body = @{ jobId = $jobId; runId = $runId; processingEpoch = 0; text = $text; contentSha256 = $sha;
               extractorVersion = 'v1'; chunkerVersion = 'v1'; manifestJson = '{}' } | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText($rb, $body, (New-Object Text.UTF8Encoding($false)))
    $pub = Api 'POST' "/internal/runs/$runId/result" $rb -internalCall
    Assert-True ($pub.status -eq '409') "发布应为 409，实际 $($pub.status) $($pub.raw)"
    Assert-True ($pub.json.code -eq 'CANCELLED_CONFLICT') "错误码 $($pub.json.code)"
    $rev = Mysql "select count(*) from content_revision where item_id='$($created.json.data.itemId)'"
    Assert-True ($rev -eq '0') "取消后仍写了 revision: $rev"
    "409 $($pub.json.code)，revision=0"
}
Check '失败任务可重试：attempt+1 并重新派发' {
    Mysql "update job set state='FAILED', attempt=2, error_code='TEST_FAIL' where id='$jobId'" | Out-Null
    $before = [int](Mysql "select count(*) from outbox where job_id='$jobId'")
    $r = Api 'POST' "/api/jobs/$jobId/retry"
    Assert-True ($r.status -eq '200') "重试 $($r.status) $($r.raw)"
    $row = Mysql "select attempt, state from job where id='$jobId'"
    $after = [int](Mysql "select count(*) from outbox where job_id='$jobId'")
    Assert-True ($row -match '^3\s+(QUEUED|RUNNING)') "attempt/state: $row"
    Assert-True ($after -gt $before) "没有重新派发 outbox: $before → $after"
    "$row，outbox $before→$after"
}
Write-Host ''
if ($failures.Count -gt 0) { Write-Host "FAILED checks: $($failures.Count)"; $failures | ForEach-Object { Write-Host "  - $_" }; exit 1 }
Write-Host 'ALL CONTROL CHECKS PASSED'; exit 0
