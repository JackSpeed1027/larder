# V03-T01 Outbox 投递验证：真实 MySQL + 真实 RabbitMQ + 真实 confirm/return。
# 前置：Docker Desktop 已启动；.env 已有 RabbitMQ / 管理员密码。
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
    $out = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $out" }
    return $out
}
function Wait-Outbox([string]$eventId, [string]$expected, [int]$timeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $row = Mysql "select state,attempt from outbox where event_id='$eventId'"
        if ($row -match "^$expected`t") { return $row }
        Start-Sleep -Milliseconds 500
    }
    return Mysql "select state,attempt from outbox where event_id='$eventId'"
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V03-T01') | Out-Null

Write-Host '== 构建 api 并启动 mysql/rabbitmq/api/web =='
Push-Location backend
& .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'backend jar 打包失败'
Pop-Location
& docker compose build api | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
& docker compose up -d --wait mysql rabbitmq api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'

function ApiHealth {
    (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String).Trim()
}
Check 'api/rabbitmq 就绪' {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        try { $h = ApiHealth; if ($h -match '"status":"UP"') { break } } catch { }
        Start-Sleep -Seconds 2
    }
    $q = (& docker compose exec -T rabbitmq rabbitmqctl list_queues name messages 2>$null | Out-String)
    Assert-True ($q -match 'larder\.events\.ingest') "队列未声明: $q"
    'health=UP，队列已声明'
}

# 登录并收录一条内容，触发真实 outbox 事件
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.BaseAddress = [Uri]'http://127.0.0.1:8080/'
function Get-Csrf {
    $c = $handler.CookieContainer.GetCookies([Uri]'http://127.0.0.1:8080/')['XSRF-TOKEN']
    if (-not $c) { throw '缺少 XSRF-TOKEN' }
    return [Uri]::UnescapeDataString($c.Value)
}
$client.GetAsync('api/session').GetAwaiter().GetResult() | Out-Null
$pw = ((Get-Content .env | Where-Object { $_ -match '^LARDER_ADMIN_PASSWORD=' }) -replace '^[^=]+=', '').Trim()
$login = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/session')
$login.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
$loginBody = '{"username":"admin","password":"' + $pw + '"}'
$login.Content = New-Object System.Net.Http.StringContent($loginBody, [Text.Encoding]::UTF8, 'application/json')
$loginResp = $client.SendAsync($login).GetAwaiter().GetResult()
Check '登录' { Assert-True ($loginResp.StatusCode.value__ -eq 200) "登录 $($loginResp.StatusCode.value__)"; 'HTTP 200' }

$suffix = Get-Date -Format 'MMddHHmmss'
$itemReq = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/items')
$itemReq.Headers.Add('Idempotency-Key', "outbox-$suffix-01")
$itemReq.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
$itemBody = '{"kind":"WEB","url":"https://Example.com/outbox/' + $suffix + '"}'
$itemReq.Content = New-Object System.Net.Http.StringContent($itemBody, [Text.Encoding]::UTF8, 'application/json')
$itemResp = $client.SendAsync($itemReq).GetAwaiter().GetResult()
$itemJson = ($itemResp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
$jobId = $itemJson.data.jobId
$eventId = Mysql "select event_id from outbox where job_id='$jobId' order by created_at desc limit 1"
Check '收录写入 outbox 事件' {
    Assert-True ($itemResp.StatusCode.value__ -eq 202) "收录状态 $($itemResp.StatusCode.value__)"
    Assert-True ($eventId -like 'out_*') "没找到 outbox 事件: $eventId"
    "$jobId → $eventId"
}

Check 'Outbox 被投递并标记 SENT（真实 confirm ack）' {
    $row = Wait-Outbox $eventId 'SENT' 90
    Assert-True ($row -match "^SENT") "最终状态: $row"
    # 消费者可能在检查前就把消息取走，队列深度不是稳定断言；
    # 用 inbox 行证明“事件被消费”，用 state=SENT 证明“confirm ack 成功”。
    $inbox = Mysql "select count(*) from inbox where event_id='$eventId'"
    Assert-True ([int]$inbox -ge 1) "事件未被消费: inbox=$inbox"
    "state=SENT，inbox=$inbox"
}

Check 'DB 写入成功、MQ 断开：事件保留 PENDING，恢复后补投 SENT' {
    & docker compose stop rabbitmq | Out-Null
    $recoveryId = "out_recover_$suffix"
    Mysql "insert into outbox (event_id, owner_id, job_id, event_type, wake_generation, payload_json, payload_hash, state, available_at, attempt, created_at) select '$recoveryId', owner_id, id, 'job.queued.ingest', 1, json_object('jobId', id), sha2(id, 256), 'PENDING', utc_timestamp(6), 0, utc_timestamp(6) from job order by created_at desc limit 1" | Out-Null
    Start-Sleep -Seconds 4
    $during = Mysql "select state,attempt from outbox where event_id='$recoveryId'"
    Assert-True ($during -notmatch '^SENT') "MQ 断开时不应 SENT: $during"
    & docker compose start rabbitmq | Out-Null
    # 先等 broker 真正可用：容器 "Started" 不等于 rabbitmqctl 能连上
    $deadline = (Get-Date).AddSeconds(120)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            & docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Assert-True $ready 'RabbitMQ 120 秒内未恢复'
    $after = Wait-Outbox $recoveryId 'SENT' 90
    Assert-True ($after -match '^SENT') "恢复后仍未投递: $after"
    "断开时=$during → 恢复后=$after"
}

Check 'unroutable（无绑定）：不能标 SENT，退避后进 DEAD' {
    $bogusId = "out_bogus_$suffix"
    Mysql "insert into outbox (event_id, owner_id, job_id, event_type, wake_generation, payload_json, payload_hash, state, available_at, attempt, created_at) select '$bogusId', owner_id, id, 'bogus.no.route', 1, json_object('jobId', id), sha2('bogus', 256), 'PENDING', utc_timestamp(6), 0, utc_timestamp(6) from job order by created_at desc limit 1" | Out-Null
    $row = Wait-Outbox $bogusId 'DEAD' 120
    Assert-True ($row -match '^DEAD') "最终状态: $row（应 DEAD）"
    Assert-True ($row -notmatch '^SENT') 'unroutable 被错误标记为 SENT'
    "state=DEAD attempt=$($row.Split("`t")[1])"
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL OUTBOX CHECKS PASSED'
exit 0
