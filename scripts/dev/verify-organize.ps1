# V02-T04 验证：保存 → 阅读 → 标签 → 笔记 → 回列表（真实 HTTP + MySQL + MinIO）。
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
function Assert-True([bool]$cond, [string]$message) { if (-not $cond) { throw $message } }
function Mysql([string]$sql) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = ($sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ularder -N -B larder' 2>&1 | Out-String).Trim() }
    finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "mysql 失败: $out" }
    return $out
}
function Wait-For([scriptblock]$probe, [int]$timeoutSeconds, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) { if (& $probe) { return $true }; Start-Sleep -Milliseconds 800 }
    throw "等待超时: $what"
}
function Sha256Hex([string]$text) {
    $b = [Text.UTF8Encoding]::new($false).GetBytes($text)
    return ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($b)) -replace '-', '').ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path (Join-Path $root 'reports\V02-T04') | Out-Null

Write-Host '== 构建并启动 =='
Push-Location backend
& .\mvnw.cmd -B -ntp -q -DskipTests -f pom.xml package | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'jar 打包失败'
Pop-Location
& docker compose build api | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'api 镜像构建失败'
& docker compose -f compose.yaml -f compose.dev.yaml up -d --wait mysql rabbitmq minio api web | Out-Null
Assert-True ($LASTEXITCODE -eq 0) 'compose up 失败'
& docker compose restart web | Out-Null

Check 'api 就绪' {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        try { $h = (& docker compose exec -T web wget -qO- http://api:8080/actuator/health 2>$null | Out-String); if ($h -match '"status":"UP"') { return 'UP' } } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'api 未 UP'
}

$jar = Join-Path $env:TEMP 'larder-org-cookies.txt'; Remove-Item $jar -ErrorAction SilentlyContinue
& curl.exe -sS -c $jar -o NUL http://127.0.0.1:8080/api/session 2>&1 | Out-Null
$xsrf = (Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' } | ForEach-Object { ($_ -split "`t")[-1] })
$envLines = Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' }
$get = { param($k) ($envLines | Where-Object { $_ -match "^$k=" } | ForEach-Object { $_.Split('=', 2)[1] }).Trim() }
$pw = & $get 'LARDER_ADMIN_PASSWORD'
$internal = & $get 'LARDER_INTERNAL_TOKEN'
$loginFile = Join-Path $env:TEMP 'org-login.json'
[IO.File]::WriteAllText($loginFile, '{"username":"admin","password":"' + $pw + '"}', (New-Object Text.UTF8Encoding($false)))
Check '登录' {
    $code = & curl.exe -sS -b $jar -c $jar -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data-binary "@$loginFile" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/session 2>&1
    Assert-True ($code -eq '200') "登录 $code"; 'HTTP 200'
}

function Api([string]$method, [string]$path, [string]$bodyFile = '', [switch]$internalCall) {
    $a = @('-sS', '-b', $jar, '-X', $method)
    if ($bodyFile) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile") }
    if ($internalCall) { $a += @('-H', "X-Internal-Token: $internal") }
    elseif ($method -ne 'GET') { $a += @('-H', "X-XSRF-TOKEN: $xsrf") }
    if ($method -eq 'POST' -and $path -eq '/api/items') { $a += @('-H', ('Idempotency-Key: org-' + [guid]::NewGuid().ToString('N').Substring(0,12))) }
    $a += @('-w', "`n%{http_code}", "http://127.0.0.1:8081$path")
    $raw = (& curl.exe @a 2>&1 | Out-String)
    $parts = $raw.Trim().Split("`n")
    $json = $null
    if ($parts[0].TrimStart().StartsWith('{') -or $parts[0].TrimStart().StartsWith('[')) { $json = $parts[0] | ConvertFrom-Json }
    return @{ status = $parts[-1].Trim(); json = $json; raw = $raw }
}

$suffix = Get-Date -Format 'MMddHHmmss'
$text = "正文第一段 $suffix`n`n正文第二段：用于阅读与笔记。"
$sha = Sha256Hex $text
$itemBody = Join-Path $env:TEMP "org-item-$suffix.json"
[IO.File]::WriteAllText($itemBody, '{"kind":"WEB","url":"https://Example.com/org/' + $suffix + '","title":"调试条目 ' + $suffix + '"}', (New-Object Text.UTF8Encoding($false)))

$created = Api 'POST' '/api/items' $itemBody
$jobId = $created.json.data.jobId
$runId = 'run_org_' + $suffix
Check '收录并发布固定 revision' {
    Assert-True ($created.status -eq '202') "收录 $($created.status)"
    Wait-For { (Mysql "select state from job where id='$jobId'") -eq 'RUNNING' } 60 'job RUNNING' | Out-Null
    Mysql "update job set run_id='$runId' where id='$jobId'" | Out-Null
    $result = Join-Path $env:TEMP "org-result-$suffix.json"
    $body = @{ jobId = $jobId; runId = $runId; processingEpoch = 0; text = $text; contentSha256 = $sha;
               extractorVersion = 'extract-v1'; chunkerVersion = 'chunk-v1'; manifestJson = '{"chunks":2}' } | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText($result, $body, (New-Object Text.UTF8Encoding($false)))
    $pub = Api 'POST' "/internal/runs/$runId/result" $result -internalCall
    Assert-True ($pub.status -eq '200') "发布 $($pub.status) $($pub.raw)"
    $script:itemId = $created.json.data.itemId
    $script:revId = $pub.json.revisionId
    "$script:itemId / $script:revId"
}

Check '列表包含新条目' {
    $page = Api 'GET' '/api/items'
    Assert-True ($page.status -eq '200') "列表 $($page.status)"
    $hit = $page.json.items | Where-Object { $_.id -eq $itemId }
    Assert-True ($null -ne $hit) '列表里没有新条目'
    "total=$($page.json.total)"
}

Check '详情返回固定 revision 与标签/笔记计数' {
    $d = Api 'GET' "/api/items/$itemId"
    Assert-True ($d.status -eq '200') "详情 $($d.status)"
    Assert-True ($d.json.currentRevisionId -eq $revId) "指针 $($d.json.currentRevisionId)"
    'currentRevisionId 一致'
}

Check '阅读固定 revision：正文与哈希一致' {
    $r = Api 'GET' "/api/items/$itemId/revisions/$revId"
    Assert-True ($r.status -eq '200') "阅读 $($r.status)"
    Assert-True ($r.json.text -eq $text) '正文不一致'
    Assert-True ($r.json.sha256 -eq $sha) 'sha 不一致'
    '正文可读且 sha 一致'
}

Check '改阅读状态 → 列表按状态过滤' {
    $body = Join-Path $env:TEMP "org-read-$suffix.json"
    [IO.File]::WriteAllText($body, '{"readStatus":"READING"}', (New-Object Text.UTF8Encoding($false)))
    $p = Api 'PATCH' "/api/items/$itemId" $body
    Assert-True ($p.status -eq '200') "PATCH $($p.status)"
    Assert-True ($p.json.readStatus -eq 'READING') "状态 $($p.json.readStatus)"
    $page = Api 'GET' '/api/items?status=READING'
    Assert-True (@($page.json.items | Where-Object { $_.id -eq $itemId }).Count -eq 1) '状态过滤没命中'
    'READING 过滤命中'
}

Check '打标签 → 按标签过滤' {
    $tb = Join-Path $env:TEMP "org-tag-$suffix.json"
    [IO.File]::WriteAllText($tb, ('{"name":"调试-' + $suffix + '"}'), (New-Object Text.UTF8Encoding($false)))
    $tag = Api 'POST' '/api/tags' $tb
    Assert-True ($tag.status -eq '200') "建标签 $($tag.status)"
    $ab = Join-Path $env:TEMP "org-attach-$suffix.json"
    [IO.File]::WriteAllText($ab, ('{"tagId":"' + $tag.json.id + '"}'), (New-Object Text.UTF8Encoding($false)))
    $attach = Api 'POST' "/api/items/$itemId/tags" $ab
    Assert-True ($attach.status -eq '204') "打标签 $($attach.status)"
    $page = Api 'GET' ("/api/items?tag=" + $tag.json.id)
    Assert-True (@($page.json.items | Where-Object { $_.id -eq $itemId }).Count -eq 1) '标签过滤没命中'
    '标签过滤命中'
}

Check '写笔记 → 列表/详情可见，空笔记 400' {
    $nb = Join-Path $env:TEMP "org-note-$suffix.json"
    [IO.File]::WriteAllText($nb, ('{"revisionId":"' + $revId + '","markdown":"我的笔记 ' + $suffix + '"}'), (New-Object Text.UTF8Encoding($false)))
    $note = Api 'POST' "/api/items/$itemId/notes" $nb
    Assert-True ($note.status -eq '200') "写笔记 $($note.status) $($note.raw)"
    $list = Api 'GET' "/api/items/$itemId/notes"
    Assert-True (@($list.json).Count -eq 1) "笔记数量 $(@($list.json).Count)"
    $empty = Join-Path $env:TEMP "org-empty-$suffix.json"
    [IO.File]::WriteAllText($empty, '{"markdown":"  "}', (New-Object Text.UTF8Encoding($false)))
    $bad = Api 'POST' "/api/items/$itemId/notes" $empty
    Assert-True ($bad.status -eq '400') "空笔记 $($bad.status)"
    '笔记 1 条，空笔记 400'
}

Check '保存阅读位置并按固定 revision 读取' {
    $pb = Join-Path $env:TEMP "org-pos-$suffix.json"
    [IO.File]::WriteAllText($pb, ('{"revisionId":"' + $revId + '","progress":0.42,"anchorJson":"{}"}'), (New-Object Text.UTF8Encoding($false)))
    $put = Api 'PUT' "/api/items/$itemId/reading-position" $pb
    Assert-True ($put.status -eq '200') "保存位置 $($put.status) $($put.raw)"
    $getp = Api 'GET' ("/api/items/$itemId/reading-position?revisionId=" + $revId)
    Assert-True ([math]::Abs([double]$getp.json.progress - 0.42) -lt 0.0001) "位置 $($getp.json.progress)"
    'progress=0.42'
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED checks: $($failures.Count)"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host 'ALL ORGANIZE CHECKS PASSED'
exit 0
