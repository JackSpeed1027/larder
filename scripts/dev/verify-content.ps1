# V02-T01 端到端验证：真实 MySQL + 真实容器，验证幂等、URL 去重、并发唯一、迁移可重复。
#
# 前置：Docker Desktop 已启动；.env 已有 LARDER_ADMIN_PASSWORD。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev/verify-content.ps1
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

Write-Host '== 重新构建 api 并启动 mysql/api/web =='
& docker compose build api | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'api 镜像构建失败' }
& docker compose up -d --wait mysql api web | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'compose up 失败' }

function ApiHealth {
    $raw = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'api 健康检查不可达' }
    return $raw
}
Check 'api 健康' {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try { $h = ApiHealth; if ($h -match '"status":"UP"') { return $h } } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'api 120 秒内未 UP'
}

# HttpClient + CookieContainer：显式控制 session 与 CSRF，并发请求共享同一会话
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.CookieContainer = New-Object System.Net.CookieContainer
$handler.UseCookies = $true
$client = New-Object System.Net.Http.HttpClient($handler)
$client.BaseAddress = [Uri]'http://127.0.0.1:8080/'

function Get-Csrf {
    $cookies = $handler.CookieContainer.GetCookies([Uri]'http://127.0.0.1:8080/')
    $c = $cookies['XSRF-TOKEN']
    if (-not $c) { throw '缺少 XSRF-TOKEN cookie' }
    return [Uri]::UnescapeDataString($c.Value)
}

Check 'GET /api/session 下发 CSRF cookie' {
    $resp = $client.GetAsync('api/session').GetAwaiter().GetResult()
    Assert-True ($resp.StatusCode.value__ -eq 401) "未登录应为 401，实际 $($resp.StatusCode.value__)"
    'csrf=' + (Get-Csrf).Substring(0, 8) + '...'
}

$adminPassword = (Get-Content .env | Where-Object { $_ -match '^LARDER_ADMIN_PASSWORD=' } | ForEach-Object { $_.Split('=', 2)[1] }).Trim()
$loginBody = '{"username":"admin","password":"' + $adminPassword + '"}'
$loginReq = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/session')
$loginReq.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
$loginReq.Content = New-Object System.Net.Http.StringContent($loginBody, [Text.Encoding]::UTF8, 'application/json')
$loginResp = $client.SendAsync($loginReq).GetAwaiter().GetResult()
Check '登录成功' {
    Assert-True ($loginResp.StatusCode.value__ -eq 200) "登录返回 $($loginResp.StatusCode.value__)"
    'HTTP 200'
}

function New-ItemRequest([string]$key, [string]$json) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/items')
    $req.Headers.Add('Idempotency-Key', $key)
    $req.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
    $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    return $req
}

function Send-Json($req) {
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null
    if ($text -and $text.TrimStart().StartsWith('{')) { $json = $text | ConvertFrom-Json }
    return [pscustomobject]@{ Status = $resp.StatusCode.value__; Json = $json; Raw = $text }
}

$suffix = (Get-Date -Format 'MMddHHmmss')
$urlA = 'https://Example.com/e2e/article?id=' + $suffix
$bodyA = '{"kind":"WEB","url":"' + $urlA + '"}'
$keyA = 'e2e-' + $suffix + '-01'

$first = Send-Json (New-ItemRequest $keyA $bodyA)
Check '首次收录返回 202 + itemId/jobId' {
    Assert-True ($first.Status -eq 202) "期望 202，实际 $($first.Status)，响应: $($first.Raw)"
    Assert-True ($first.Json.data.itemId -like 'itm_*') "itemId 异常: $($first.Json.data.itemId)"
    Assert-True ($first.Json.data.jobId -like 'job_*') "jobId 异常: $($first.Json.data.jobId)"
    "$($first.Json.data.itemId) / $($first.Json.data.jobId)"
}
$itemId = $first.Json.data.itemId

Check '同 key 同 body 重放返回 200 且 itemId 不变' {
    $again = Send-Json (New-ItemRequest $keyA $bodyA)
    Assert-True ($again.Status -eq 200) "期望 200，实际 $($again.Status)"
    Assert-True ($again.Json.data.itemId -eq $itemId) "itemId 变了: $($again.Json.data.itemId)"
    'HTTP 200 / ' + $again.Json.data.itemId
}

Check '同 key 不同 body 返回 409 IDEMPOTENCY_CONFLICT' {
    $conflict = Send-Json (New-ItemRequest $keyA '{"kind":"WEB","url":"https://example.com/other"}')
    Assert-True ($conflict.Status -eq 409) "期望 409，实际 $($conflict.Status)"
    Assert-True ($conflict.Json.code -eq 'IDEMPOTENCY_PAYLOAD_MISMATCH') "错误码异常: $($conflict.Json.code)"
    $conflict.Json.code
}

Check '不同 key 同规范化 URL 返回 200 同一 item' {
    $dedupe = Send-Json (New-ItemRequest ('e2e-' + $suffix + '-02') $bodyA)
    Assert-True ($dedupe.Status -eq 200) "期望 200，实际 $($dedupe.Status)"
    Assert-True ($dedupe.Json.data.itemId -eq $itemId) "未去重到同一 item: $($dedupe.Json.data.itemId)"
    $dedupe.Json.data.itemId
}

Check '8 个并发请求同 URL 只产生 1 个 item' {
    $tasks = @()
    for ($i = 3; $i -le 10; $i++) {
        $key = 'e2e-' + $suffix + '-0' + $i
        $tasks += $client.SendAsync((New-ItemRequest $key $bodyA))
    }
    [System.Threading.Tasks.Task]::WaitAll($tasks)
    $statuses = @()
    $ids = @()
    foreach ($t in $tasks) {
        $resp = $t.Result
        $json = ($resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        $statuses += $resp.StatusCode.value__
        if ($json.data.itemId) { $ids += $json.data.itemId }
    }
    Assert-True (($statuses | Where-Object { $_ -ge 500 }).Count -eq 0) "出现 5xx: $($statuses -join ',')"
    $unique = @($ids | Select-Object -Unique)
    Assert-True ($unique.Count -eq 1) "并发返回了多个 itemId: $($unique -join ',')"
    Assert-True ($unique[0] -eq $itemId) "并发返回的 item 不是同一个: $($unique[0])"
    "statuses=$($statuses -join ',')"
}

Check 'MySQL 中该 item 只有 1 条，且有 1 个 job / 1 条 outbox' {
    $row = Mysql "select (select count(*) from item where id='$itemId'), (select count(*) from job where item_id='$itemId'), (select count(*) from outbox o join job j on o.job_id=j.id where j.item_id='$itemId')"
    Assert-True ($row -eq "1`t1`t1") "期望 1/1/1，实际: $row"
    $row -replace "`t", '/'
}

Check 'Flyway 迁移记录包含 V2 且成功' {
    $v = Mysql 'select version,success from flyway_schema_history order by installed_rank'
    Assert-True ($v -match '2\s+1') "V2 迁移未成功: $v"
    ($v -replace "`r`n", ' / ')
}

Check '重启 api 后迁移不重复执行' {
    $before = Mysql 'select count(*) from flyway_schema_history'
    & docker compose restart api | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try { $h = ApiHealth; if ($h -match '"status":"UP"') { break } } catch { }
        Start-Sleep -Seconds 2
    }
    $after = Mysql 'select count(*) from flyway_schema_history'
    Assert-True ($before -eq $after) "迁移记录从 $before 变成 $after（重复执行）"
    "migrations=$after（重启前后一致）"
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL CONTENT CHECKS PASSED'
exit 0
