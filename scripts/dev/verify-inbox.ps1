# V03-T02 Inbox 验证：真实 RabbitMQ 重复投递 + 死信队列 + 持久接管。
# 前置：Docker Desktop 已启动；.env 完整。
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
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>&1 | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $old
    }
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $out" }
    return $out
}
function Rabbit([string]$cmd) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        (& docker compose exec -T rabbitmq sh -c "rabbitmqctl $cmd" 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $old
    }
}
function Wait-For([scriptblock]$probe, [int]$timeoutSeconds, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $probe) { return $true }
        Start-Sleep -Milliseconds 800
    }
    throw "等待超时: $what"
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V03-T02') | Out-Null

Write-Host '== 启动 mysql/rabbitmq，重建队列，再起 api =='
& docker compose up -d --wait mysql rabbitmq | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'mysql/rabbitmq 启动失败'
& docker compose stop api | Out-Null
Wait-For { ((& docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping 2>&1 | Out-String) -match 'Ping succeeded') } 90 'rabbitmq 就绪' | Out-Null
# 旧队列没有 DLX 参数，重新声明会 PRECONDITION_FAILED；开发环境直接删掉重建
Rabbit 'delete_queue larder.events.ingest' | Out-Null
Rabbit 'delete_queue larder.events.ingest.dlq' | Out-Null

Push-Location backend
& .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'backend jar 打包失败'
Pop-Location
& docker compose build api | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
& docker compose up -d --wait api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api/web 启动失败'

Check '队列与死信队列已声明' {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String)
            if ($h -match '"status":"UP"') { break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    $q = (Rabbit 'list_queues name' | Out-String)
    Assert-True ($q -match 'larder\.events\.ingest\b') "缺少 ingest 队列: $q"
    Assert-True ($q -match 'larder\.events\.ingest\.dlq') "缺少 DLQ: $q"
    'ingest + dlq 均存在'
}

# 登录 + 收录，产生真实事件
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
$loginBody = '{"username":"admin","password":"' + $pw + '"}'
$login = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/session')
$login.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
$login.Content = New-Object System.Net.Http.StringContent($loginBody, [Text.Encoding]::UTF8, 'application/json')
$loginResp = $client.SendAsync($login).GetAwaiter().GetResult()
Check '登录' { Assert-True ($loginResp.StatusCode.value__ -eq 200) "登录 $($loginResp.StatusCode.value__)"; 'HTTP 200' }

$suffix = Get-Date -Format 'MMddHHmmss'
$itemBody = '{"kind":"WEB","url":"https://Example.com/inbox/' + $suffix + '"}'
$itemReq = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, 'api/items')
$itemReq.Headers.Add('Idempotency-Key', "inbox-$suffix-01")
$itemReq.Headers.Add('X-XSRF-TOKEN', (Get-Csrf))
$itemReq.Content = New-Object System.Net.Http.StringContent($itemBody, [Text.Encoding]::UTF8, 'application/json')
$itemResp = $client.SendAsync($itemReq).GetAwaiter().GetResult()
$itemJson = ($itemResp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
$jobId = $itemJson.data.jobId
Check '收录产生事件' {
    Assert-True ($itemResp.StatusCode.value__ -eq 202) "收录 $($itemResp.StatusCode.value__)"
    $script:eventId = Mysql "select event_id from outbox where job_id='$jobId' limit 1"
    Assert-True ($script:eventId -like 'out_*') "没有事件: $($script:eventId)"
    "$jobId → $($script:eventId)"
}

Check '消费者接管 job（RUNNING + 租约）并写 inbox' {
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'RUNNING' } 60 'job RUNNING' | Out-Null
    Wait-For { (Mysql "select count(*) from inbox where event_id='$eventId'") -eq '1' } 30 'inbox 一行' | Out-Null
    $row = Mysql "select state, lease_owner, lease_token, lease_until is not null from job where id='$jobId'"
    $inbox = Mysql "select count(*), max(accepted_at is not null) from inbox where event_id='$eventId'"
    Assert-True ($row -match '^RUNNING\s+\S+\s+1\s+1$') "job 接管异常: $row"
    Assert-True ($inbox -match '^1\s+1$') "inbox 异常: $inbox"
    "job=$row inbox=$inbox"
}

Check '重复投递（同 event_id 重发）不重复接管' {
    $tokenBefore = (Mysql "select lease_token from job where id='$jobId'")
    Mysql "update outbox set state='PENDING', available_at=utc_timestamp(6) where event_id='$eventId'" | Out-Null
    Start-Sleep -Seconds 4
    $tokenAfter = (Mysql "select lease_token from job where id='$jobId'")
    $inboxCount = (Mysql "select count(*) from inbox where event_id='$eventId'")
    Assert-True ($tokenAfter -eq $tokenBefore) "重复消息导致重新接管: $tokenBefore → $tokenAfter"
    Assert-True ($inboxCount -eq '1') "inbox 出现重复行: $inboxCount"
    "lease_token 仍为 $tokenAfter，inbox=1"
}

Check '坏消息进死信队列（可诊断），不写 inbox' {
    $bogus = "out_bogus_$suffix"
    Mysql "insert into outbox (event_id, owner_id, job_id, event_type, wake_generation, payload_json, payload_hash, state, available_at, attempt, created_at) select '$bogus', owner_id, id, 'job.queued.ingest', 1, json_object('bogus', true), sha2('bogus', 256), 'PENDING', utc_timestamp(6), 0, utc_timestamp(6) from job where id='$jobId'" | Out-Null
    Wait-For { ((Rabbit 'list_queues name messages' | Out-String) -match 'larder\.events\.ingest\.dlq\s+[1-9]') } 60 'DLQ 有消息' | Out-Null
    Start-Sleep -Seconds 2
    $inboxBogus = (Mysql "select count(*) from inbox where event_id='$bogus'")
    Assert-True ($inboxBogus -eq '0') "坏消息被写进了 inbox: $inboxBogus"
    'DLQ 收到 1 条，inbox=0'
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL INBOX CHECKS PASSED'
exit 0
