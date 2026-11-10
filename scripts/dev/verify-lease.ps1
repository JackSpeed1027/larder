# V03-T03 验证：SSE 快照/重连 + 租约过期对账 + fencing 代次。
# 前置：Docker Desktop 已启动；.env 完整。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

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
function Wait-For([scriptblock]$probe, [int]$timeoutSeconds, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $probe) { return $true }
        Start-Sleep -Milliseconds 800
    }
    throw "等待超时: $what"
}

function SseGet([string[]]$headers) {
    # curl 在 --max-time 到期时返回 28，这是预期的"主动断流"，不能当脚本失败
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $curlArgs = @('-sS', '-N', '--max-time', '4', '-b', $jar) + $headers + @("http://127.0.0.1:8080/api/jobs/$jobId/events")
        (& curl.exe @curlArgs 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $old
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V03-T03') | Out-Null
$jar = Join-Path $env:TEMP 'larder-lease-cookies.txt'
Remove-Item $jar -ErrorAction SilentlyContinue

Write-Host '== 构建 api 并启动依赖 =='
Push-Location backend
& .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'backend jar 打包失败'
Pop-Location
& docker compose build api | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
& docker compose up -d --wait mysql rabbitmq api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'

Check 'api 就绪' {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String)
            if ($h -match '"status":"UP"') { return 'UP' }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'api 150 秒内未 UP'
}

& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$pw = ((Get-Content .env | Where-Object { $_ -match '^LARDER_ADMIN_PASSWORD=' }) -replace '^[^=]+=', '').Trim()
$loginFile = Join-Path $env:TEMP 'lease-login.json'
[IO.File]::WriteAllText($loginFile, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $code = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$loginFile" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($code -eq '200') "登录 $code"
    'HTTP 200'
}

$suffix = Get-Date -Format 'MMddHHmmss'
$itemFile = Join-Path $env:TEMP 'lease-item.json'
[IO.File]::WriteAllText($itemFile, '{"kind":"WEB","url":"https://Example.com/lease/' + $suffix + '"}', (New-Object Text.UTF8Encoding($false)))
$itemRaw = (& curl.exe -sS -b $jar -H "X-XSRF-TOKEN: $xsrf" -H "Idempotency-Key: lease-$suffix-01" -H 'Content-Type: application/json' --data-binary "@$itemFile" -w "`n%{http_code}" http://127.0.0.1:8080/api/items 2>&1 | Out-String)
$itemParts = $itemRaw.Trim().Split("`n")
$itemJson = $itemParts[0] | ConvertFrom-Json
$jobId = $itemJson.data.jobId
Check '收录并接管 job' {
    Assert-True ($itemParts[-1].Trim() -eq '202') "收录状态 $($itemParts[-1])"
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'RUNNING' } 60 'job RUNNING' | Out-Null
    "$jobId RUNNING"
}

Check 'SSE 首发 snapshot（权威状态）' {
    $sse = SseGet @()
    Assert-True ($sse -match 'event:snapshot') "没有 snapshot: $sse"
    Assert-True ($sse -match [regex]::Escape($jobId)) 'snapshot 里没有 jobId'
    Assert-True ($sse -match '"state":"RUNNING"') '快照状态不是 RUNNING'
    'snapshot: RUNNING'
}

Check 'SSE 断流重连（Last-Event-ID）仍能还原状态' {
    $sse = SseGet @('-H', 'Last-Event-ID: 0')
    Assert-True ($sse -match '"resumedFrom":"0"') '重连没有带上 Last-Event-ID'
    Assert-True ($sse -match 'event:snapshot') '重连没有 snapshot'
    'resumedFrom=0，snapshot 再次下发'
}

Check '租约过期 → 对账收回 → 重新派发并接管（fencing 代次递增）' {
    $tokenBefore = [int](Mysql "select lease_token from job where id='$jobId'")
    Mysql "update job set lease_until = utc_timestamp(6) - interval 1 second where id='$jobId'" | Out-Null
    Wait-For { [int](Mysql "select lease_token from job where id='$jobId'") -gt $tokenBefore } 60 '租约代次递增' | Out-Null
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'RUNNING' } 60 'job 重新 RUNNING' | Out-Null
    $tokenAfter = [int](Mysql "select lease_token from job where id='$jobId'")
    $attempt = [int](Mysql "select attempt from job where id='$jobId'")
    $outboxCount = [int](Mysql "select count(*) from outbox where job_id='$jobId'")
    Assert-True ($tokenAfter -gt $tokenBefore) "代次没递增: $tokenBefore → $tokenAfter"
    Assert-True ($attempt -ge 2) "attempt 没有递增: $attempt"
    Assert-True ($outboxCount -ge 2) "没有重新派发事件: $outboxCount"
    "token $tokenBefore → $tokenAfter，attempt=$attempt，outbox=$outboxCount"
}

Check 'SSE 竞态：连接期间发布结果，收到终态事件且 DB 一致' {
    $runId = Mysql "select run_id from job where id='$jobId'"
    $epoch = Mysql "select processing_epoch from job where id='$jobId'"
    $text = "race body $suffix"
    $sha = (Get-FileHash -InputStream ([IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($text))) -Algorithm SHA256).Hash.ToLower()
    $token = ((Get-Content .env | Where-Object { $_ -match '^LARDER_INTERNAL_TOKEN=' } | ForEach-Object { $_.Split('=', 2)[1] })).Trim()
    $payloadFile = Join-Path $env:TEMP "lease-race-$suffix.json"
    $payload = '{"jobId":"' + $jobId + '","runId":"' + $runId + '","processingEpoch":' + $epoch + ',"text":"' + $text + '","contentSha256":"' + $sha + '","extractorVersion":"extract-v1","chunkerVersion":"chunk-v1","manifestJson":"{\"chunks\":0}"}'
    [IO.File]::WriteAllText($payloadFile, $payload, (New-Object Text.UTF8Encoding($false)))

    $stream = Start-Job -ScriptBlock {
        param($jar, $jobId)
        & curl.exe -sS -N --max-time 10 -b $jar "http://127.0.0.1:8080/api/jobs/$jobId/events" 2>$null
    } -ArgumentList $jar, $jobId
    Start-Sleep -Milliseconds 800
    # /internal 只对容器网络开放（nginx 不代理）；从 runner 容器内回传，等价于真实 Runner
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
    $pub = (& docker compose exec -T runner python -c "import sys,base64,urllib.request; body=base64.b64decode(sys.argv[1]); req=urllib.request.Request('http://api:8080/internal/runs/'+sys.argv[2]+'/result', data=body, method='POST', headers={'Content-Type':'application/json','X-Internal-Token':sys.argv[3]}); print(urllib.request.urlopen(req).status)" $b64 $runId $token 2>&1 | Out-String)
    $sse = Receive-Job -Job $stream -Wait -AutoRemoveJob -ErrorAction SilentlyContinue | Out-String

    Assert-True ($pub.Trim() -eq '200') "发布失败: $pub"
    Assert-True ($sse -match 'SUCCEEDED') "SSE 没收到终态: $sse"
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'SUCCEEDED' } 30 'job SUCCEEDED' | Out-Null
    '连接期间发布：SSE 收到 SUCCEEDED，DB 一致'
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL LEASE CHECKS PASSED'
exit 0
