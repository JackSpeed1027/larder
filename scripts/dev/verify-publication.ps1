# V02-T03 验证：Runner 回传 → 不可变 revision → item 指针 → job SUCCEEDED；
# 以及重放幂等、旧 epoch 拒绝、artifact 落库。
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
function Sha256Hex([string]$text) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($text)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V02-T03') | Out-Null

Write-Host '== 构建并启动 =='
Push-Location backend
& .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'backend jar 打包失败'
Pop-Location
& docker compose build api | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
& docker compose -f compose.yaml -f compose.dev.yaml up -d --wait mysql rabbitmq api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'
# api 重建后 IP 会变，nginx 在启动时解析上游，必须重启 web
& docker compose restart web | Out-Null

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

$jar = Join-Path $env:TEMP 'larder-pub-cookies.txt'
Remove-Item $jar -ErrorAction SilentlyContinue
& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$envLines = Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' }
$envGet = { param($k) ($envLines | Where-Object { $_ -match "^$k=" } | ForEach-Object { $_.Split('=', 2)[1] }).Trim() }
$pw = & $envGet 'LARDER_ADMIN_PASSWORD'
$internalToken = & $envGet 'LARDER_INTERNAL_TOKEN'
$loginFile = Join-Path $env:TEMP 'pub-login.json'
[IO.File]::WriteAllText($loginFile, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $code = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$loginFile" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($code -eq '200') "登录 $code"
    'HTTP 200'
}

$suffix = Get-Date -Format 'MMddHHmmss'
function New-ItemRecord([string]$url) {
    $file = Join-Path $env:TEMP "pub-item-$suffix-$([guid]::NewGuid().ToString('N').Substring(0,6)).json"
    [IO.File]::WriteAllText($file, '{"kind":"WEB","url":"' + $url + '"}', (New-Object Text.UTF8Encoding($false)))
    $raw = (& curl.exe -sS -b $jar -H "X-XSRF-TOKEN: $xsrf" -H "Idempotency-Key: pub-$suffix-$([guid]::NewGuid().ToString('N').Substring(0,8))" -H 'Content-Type: application/json' --data-binary "@$file" -w "`n%{http_code}" http://127.0.0.1:8080/api/items 2>&1 | Out-String)
    $parts = $raw.Trim().Split("`n")
    $json = $parts[0] | ConvertFrom-Json
    return @{ status = $parts[-1].Trim(); jobId = $json.data.jobId; itemId = $json.data.itemId }
}

$text = "真实网页正文：发布链路。" + $suffix + "`n`n第二段：不可变版本与指针切换。"
$textSha = Sha256Hex $text
$runId = 'run_e2e_' + $suffix

$first = New-ItemRecord "https://Example.com/publish/$suffix"
Check '收录并接管 job' {
    Assert-True ($first.status -eq '202') "收录 $($first.status)"
    Wait-For { (Mysql "select state from job where id='$($first.jobId)'") -eq 'RUNNING' } 60 'job RUNNING' | Out-Null
    Mysql "update job set run_id='$runId' where id='$($first.jobId)'" | Out-Null
    "$($first.jobId) RUNNING, runId=$runId"
}

$resultBody = @{
    jobId = $first.jobId
    runId = $runId
    processingEpoch = 0
    text = $text
    contentSha256 = $textSha
    extractorVersion = 'extract-v1'
    chunkerVersion = 'chunk-v1'
    manifestJson = '{"chunks":2}'
} | ConvertTo-Json -Depth 5
$resultFile = Join-Path $env:TEMP "pub-result-$suffix.json"
[IO.File]::WriteAllText($resultFile, $resultBody, (New-Object Text.UTF8Encoding($false)))

function Post-Result([string]$bodyFile, [string]$pathRunId) {
    $raw = (& curl.exe -sS -H "X-Internal-Token: $internalToken" -H 'Content-Type: application/json' --data-binary "@$bodyFile" -w "`n%{http_code}" "http://127.0.0.1:8081/internal/runs/$pathRunId/result" 2>&1 | Out-String)
    $parts = $raw.Trim().Split("`n")
    return @{ status = $parts[-1].Trim(); body = ($parts[0] | ConvertFrom-Json) }
}

$created = Post-Result $resultFile $runId
Check 'Runner 回传：200 + revisionId，revision/artifact/指针/job 全部落库' {
    Assert-True ($created.status -eq '200') "状态 $($created.status) $($created.body)"
    $revId = $created.body.revisionId
    Assert-True ($revId -like 'rev_*') "revisionId 异常: $revId"
    $revCount = Mysql "select count(*) from content_revision where item_id='$($first.itemId)'"
    $pointer = Mysql "select current_revision_id from item where id='$($first.itemId)'"
    $jobState = Mysql "select state from job where id='$($first.jobId)'"
    $artifacts = Mysql "select count(*) from artifact a join revision_artifact ra on ra.artifact_id=a.id where ra.revision_id='$revId'"
    Assert-True ($revCount -eq '1') "revision 数量 $revCount"
    Assert-True ($pointer -eq $revId) "item 指针 $pointer ≠ $revId"
    Assert-True ($jobState -eq 'SUCCEEDED') "job 状态 $jobState"
    Assert-True ($artifacts -eq '1') "artifact 绑定 $artifacts"
    "rev=$revId revCount=1 pointer=OK job=SUCCEEDED artifact=1"
}

Check '重复回传（Runner 重试）幂等：200 同一 revisionId，不重复写' {
    $again = Post-Result $resultFile $runId
    Assert-True ($again.status -eq '200') "状态 $($again.status) $($again.body)"
    Assert-True ($again.body.revisionId -eq $created.body.revisionId) "revisionId 变了: $($again.body.revisionId)"
    $revCount = Mysql "select count(*) from content_revision where item_id='$($first.itemId)'"
    Assert-True ($revCount -eq '1') "重放写了新 revision: $revCount"
    "dedupe=$($again.body.dedupe)，revCount=1"
}

Check '旧 processing_epoch 不能发布：409 EPOCH_SUPERSEDED，且不写 revision' {
    $second = New-ItemRecord "https://Example.com/publish-stale/$suffix"
    Wait-For { (Mysql "select state from job where id='$($second.jobId)'") -eq 'RUNNING' } 60 '第二个 job RUNNING' | Out-Null
    $staleRun = 'run_stale_' + $suffix
    Mysql "update job set run_id='$staleRun', processing_epoch=1 where id='$($second.jobId)'" | Out-Null
    $staleBody = @{
        jobId = $second.jobId
        runId = $staleRun
        processingEpoch = 0
        text = $text
        contentSha256 = $textSha
        extractorVersion = 'extract-v1'
        chunkerVersion = 'chunk-v1'
        manifestJson = '{"chunks":2}'
    } | ConvertTo-Json -Depth 5
    $staleFile = Join-Path $env:TEMP "pub-stale-$suffix.json"
    [IO.File]::WriteAllText($staleFile, $staleBody, (New-Object Text.UTF8Encoding($false)))
    $rejected = Post-Result $staleFile $staleRun
    Assert-True ($rejected.status -eq '409') "状态 $($rejected.status) $($rejected.body)"
    Assert-True ($rejected.body.code -eq 'EPOCH_SUPERSEDED') "错误码 $($rejected.body.code)"
    $revCount = Mysql "select count(*) from content_revision where item_id='$($second.itemId)'"
    Assert-True ($revCount -eq '0') "旧 epoch 仍写了 revision: $revCount"
    "409 $($rejected.body.code)，revCount=0"
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL PUBLICATION CHECKS PASSED'
exit 0
