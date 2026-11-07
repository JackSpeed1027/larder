# 把 V01-T01 的产物从暂存目录落位到项目，并生成校验日志与文件清单。
# 只新增/覆盖本任务自己产出的文件；不动交接包已落位的 122 个文件。
$ErrorActionPreference = 'Stop'
$stage = 'D:\Work\AgentWorkSpace\DSHVibe\stage'
$proj  = 'D:\Work\Project\larder'
$runId = '20261106-195106-v01t01'
$runRel = "reports\V01-T01\$runId"
$runDir = Join-Path $proj $runRel
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ---------- 1) 补齐 starlette 的注册源证据（锁清单里有该条目） ----------
$pypiDir = Join-Path $runDir 'raw\pypi'
New-Item -ItemType Directory -Force -Path $pypiDir | Out-Null
if (-not (Test-Path (Join-Path $pypiDir 'starlette.trimmed.json'))) {
    $url = 'https://pypi.org/pypi/starlette/json'
    $r = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 40
    [IO.File]::WriteAllBytes((Join-Path $pypiDir 'starlette.full.json'), $r.RawContentStream.ToArray())
    $j = $r.Content | ConvertFrom-Json
    $trim = [ordered]@{
        name              = $j.info.name
        version           = $j.info.version
        requiresPython    = $j.info.requires_python
        license           = $j.info.license
        licenseExpression = $j.info.license_expression
        summary           = $j.info.summary
        uploadTime        = ($j.releases.($j.info.version) | Select-Object -First 1).upload_time_iso_8601
        sourceUrl         = $url
    }
    [IO.File]::WriteAllText((Join-Path $pypiDir 'starlette.trimmed.json'), ($trim | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))
    Add-Content -Path (Join-Path $runDir 'fetch.log') -Encoding UTF8 -Value ("GET  {0}`n     -> {1} len={2}" -f $url, $r.StatusCode, $r.RawContentStream.Length)
    "fetched starlette metadata: " + $j.info.version
}

# ---------- 2) 复制产物 ----------
$copied = 0
$dirs = @(
    (Join-Path $stage 'docs\adr\versions'),
    (Join-Path $stage $runRel),
    (Join-Path $stage 'handoffs\V01-T01')
)
# 单文件
$single = @(
    (Join-Path $stage 'README.md'),
    (Join-Path $stage 'docs\versions.lock.json'),
    (Join-Path $stage 'docs\交接资料导航.md'),
    (Join-Path $stage 'handoffs\V01-T01\handoff.json')
)
foreach ($f in $single) {
    $rel = $f.Substring($stage.Length + 1)
    $dst = Join-Path $proj $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    Copy-Item -LiteralPath $f -Destination $dst -Force
    $copied++
}

$skipPattern = '\\(target|\.venv|node_modules|__pycache__)\\'
foreach ($d in $dirs) {
    Get-ChildItem -LiteralPath $d -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($stage.Length + 1)
        if ($rel -match $skipPattern) { return }
        if ($rel -match '\\raw\\') { return }   # raw 已由采集脚本直接写入项目
        $dst = Join-Path $proj $rel
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $dst -Force
        $copied++
    }
}

# 把验证脚手架里的日志统一收进 logs\，使锁清单里的证据路径成立
$logsDir = Join-Path $runDir 'logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$logMap = @{
    (Join-Path $runDir 'compat-check-runner\uv-venv.log')          = 'uv-venv.log'
    (Join-Path $runDir 'compat-check-runner\uv-pip-install.log')   = 'uv-pip-install.log'
    (Join-Path $runDir 'compat-check-runner\smoke.log')            = 'smoke.log'
    (Join-Path $runDir 'compat-check-frontend\pnpm-install-lockfile-only.log') = 'pnpm-install-lockfile-only.log'
}
foreach ($k in $logMap.Keys) {
    if (Test-Path $k) { Copy-Item -LiteralPath $k -Destination (Join-Path $logsDir $logMap[$k]) -Force; $copied++ }
}

# ---------- 3) 校验锁清单 ----------
Push-Location $proj
$vout = & python "$runDir\tools\validate_versions_lock.py" $proj 2>&1 | Out-String
$vcode = $LASTEXITCODE
Pop-Location
[IO.File]::WriteAllText((Join-Path $runDir 'validate.log'), $vout, (New-Object Text.UTF8Encoding($false)))
$vout
if ($vcode -ne 0) { "VALIDATOR FAILED exit=$vcode (manifest not written)"; exit 1 }

# ---------- 4) 生成 file-manifest.json ----------
$manifestPath = Join-Path $proj 'handoffs\V01-T01\file-manifest.json'
$scopeFiles = New-Object System.Collections.Generic.List[object]
function Add-Scope([string]$abs) {
    if (Test-Path -LiteralPath $abs -PathType Leaf) { $scopeFiles.Add((Get-Item -LiteralPath $abs)) }
}
Add-Scope (Join-Path $proj 'README.md')
Add-Scope (Join-Path $proj 'docs\versions.lock.json')
Add-Scope (Join-Path $proj 'docs\交接资料导航.md')
Add-Scope (Join-Path $proj 'handoffs\V01-T01\handoff.json')
foreach ($f in (Get-ChildItem -LiteralPath (Join-Path $proj 'docs\adr\versions') -Recurse -File)) { $scopeFiles.Add($f) }
foreach ($f in (Get-ChildItem -LiteralPath $runDir -Recurse -File)) {
    if ($f.FullName -match $skipPattern) { continue }
    $scopeFiles.Add($f)
}

$entries = foreach ($f in $scopeFiles) {
    $rel = $f.FullName.Substring($proj.Length + 1).Replace('\', '/')
    if ($rel -eq 'handoffs/V01-T01/file-manifest.json') { continue }
    [ordered]@{
        path         = $rel
        changeType   = 'added'
        baseSha256   = $null
        resultSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash.ToLowerInvariant()
        size         = $f.Length
    }
}
$manifest = [ordered]@{
    taskId    = 'V01-T01'
    sliceId   = 'V01-T01.FULL'
    runId     = $runId
    generatedAt = (Get-Date -Format 'o')
    note      = 'baseSha256 为 null 表示新增文件；本仓库尚未 git init。file-manifest.json 自身不进清单。'
    files     = $entries
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))

"copied_files=$copied"
"manifest_entries=" + $entries.Count
"validate_exit=$vcode"
"done"
