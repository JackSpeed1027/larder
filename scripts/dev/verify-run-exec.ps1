# S02.EXEC 闭环验证：公开收录 → Outbox/Relay → Inbox 接管 → 派发 Runner →
# Runner 抓取/抽取/分段 → 回传 Java 发布 → item.current_revision_id 可读。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
Add-Type -AssemblyName System.Net.Http

$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$n, [scriptblock]$b) {
    try { $d = & $b; Write-Host ("[PASS] {0}: {1}" -f $n, $d) }
    catch { $script:failures.Add("${n}: $($_.Exception.Message)"); Write-Host ("[FAIL] {0}: {1}" -f $n, $_.Exception.Message) }
}
function Assert-True([bool]$c, [string]$m) { if (-not $c) { throw $m } }
function Mysql([string]$sql) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $o = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>&1 | Out-String).Trim() }
    finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "mysql: $o" }
    return $o
}
function Wait-For([scriptblock]$p, [int]$t, [string]$w) {
    $d = (Get-Date).AddSeconds($t); while ((Get-Date) -lt $d) { if (& $p) { return } ; Start-Sleep -Milliseconds 1000 }; throw "超时: $w"
}

Push-Location $root
& .\backend\mvnw.cmd -B -ntp -q -DskipTests -f backend\pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'backend 打包失败'
& docker compose build api runner | Out-Null
Assert-True ($LASTEXITCODE -eq 0) '镜像构建失败'
& docker compose -f compose.yaml -f compose.dev.yaml up -d --wait mysql rabbitmq minio api web runner | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'
& docker compose restart web | Out-Null
Pop-Location

Check 'api/runner 就绪' {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        try { $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String); $r = (& docker compose exec -T runner python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8000/v1/health',timeout=2).status)" 2>$null | Out-String)
            if ($h -match '"status":"UP"' -and $r.Trim() -eq '200') { return 'api=UP runner=200' } } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'api/runner 未就绪'
}

$jar = Join-Path $env:TEMP 'exec-cookies.txt'; Remove-Item $jar -ErrorAction SilentlyContinue
& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$envLines = Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' }
$get = { param($k) ($envLines | Where-Object { $_ -match "^$k=" } | ForEach-Object { $_.Split('=', 2)[1] }).Trim() }
$pw = & $get 'LARDER_ADMIN_PASSWORD'
$lf = Join-Path $env:TEMP 'exec-login.json'
[IO.File]::WriteAllText($lf, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $c = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$lf" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($c -eq '200') "登录 $c"; 'HTTP 200'
}

$suffix = Get-Date -Format 'MMddHHmmss'
$url = 'https://example.com/?exec=' + $suffix
$ib = Join-Path $env:TEMP "exec-item-$suffix.json"
[IO.File]::WriteAllText($ib, '{"kind":"WEB","url":"' + $url + '"}', (New-Object Text.UTF8Encoding($false)))
$raw = (& curl.exe -sS -b $jar -H "X-XSRF-TOKEN: $xsrf" -H "Idempotency-Key: exec-$suffix" -H 'Content-Type: application/json' --data-binary "@$ib" -w "`n%{http_code}" http://127.0.0.1:8080/api/items 2>&1 | Out-String)
$parts = $raw.Trim().Split("`n"); $item = $parts[0] | ConvertFrom-Json
$itemId = $item.data.itemId; $jobId = $item.data.jobId
Check '收录返回 202 + itemId/jobId' {
    Assert-True ($parts[-1].Trim() -eq '202') "状态 $($parts[-1])"
    "$itemId / $jobId"
}

Check '全链路：job SUCCEEDED 且 item 指向 revision' {
    Wait-For { (Mysql "select current_revision_id from item where id='$itemId'") -ne 'NULL' } 180 'revision 发布'
    $rev = Mysql "select current_revision_id from item where id='$itemId'"
    $state = Mysql "select state from job where id='$jobId'"
    Assert-True ($rev -like 'rev_*') "revision=$rev"
    Assert-True ($state -eq 'SUCCEEDED') "job=$state"
    "rev=$rev job=$state"
}

Check '正文可读且来自 Runner 抽取' {
    $rev = Mysql "select current_revision_id from item where id='$itemId'"
    $raw = (& curl.exe -sS -b $jar "http://127.0.0.1:8080/api/items/$itemId/revisions/$rev" 2>&1 | Out-String)
    $body = $raw | ConvertFrom-Json
    Assert-True ($body.text.Length -gt 0) '正文为空'
    Assert-True ($body.sha256.Length -eq 64) 'sha 异常'
    "len=$($body.text.Length) sha=$($body.sha256.Substring(0,12))…"
}

Write-Host ''
if ($failures.Count -gt 0) { Write-Host "FAILED checks: $($failures.Count)"; $failures | ForEach-Object { Write-Host "  - $_" }; exit 1 }
Write-Host 'ALL EXEC CHECKS PASSED'; exit 0
