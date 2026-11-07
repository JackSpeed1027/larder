# V01-T01 版本证据采集脚本：从权威注册源拉取真实版本元数据并原样存档。
# 只做 HTTP GET 与本地写文件，不修改任何依赖、不写项目源码目录。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root   = 'D:\Work\Project\larder'
$runId  = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-v01t01'
$runDir = Join-Path $root "reports\V01-T01\$runId"
$raw    = Join-Path $runDir 'raw'
foreach ($d in @($runDir, $raw, "$raw\maven", "$raw\npm", "$raw\pypi", "$raw\docker", "$raw\platform")) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
$log = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $log.Add($s); }

function Get-Save([string]$url, [string]$outFile) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 40
        $bytes = $r.RawContentStream.ToArray()
        [IO.File]::WriteAllBytes($outFile, $bytes)
        Log ("GET  {0}`n     -> {1} len={2} {3}" -f $url, $r.StatusCode, $bytes.Length, $outFile)
        return $r.Content
    } catch {
        Log ("FAIL {0}`n     -> {1}" -f $url, $_.Exception.Message)
        return $null
    }
}

# ---------- Maven Central ----------
$maven = @(
  @{ key='spring-boot-starter-parent';       path='org/springframework/boot/spring-boot-starter-parent' },
  @{ key='spring-boot-dependencies';         path='org/springframework/boot/spring-boot-dependencies' },
  @{ key='mybatis-plus-spring-boot3-starter'; path='com/baomidou/mybatis-plus-spring-boot3-starter' },
  @{ key='flyway-core';                      path='org/flywaydb/flyway-core' },
  @{ key='flyway-mysql';                     path='org/flywaydb/flyway-mysql' },
  @{ key='mysql-connector-j';                path='com/mysql/mysql-connector-j' },
  @{ key='testcontainers';                   path='org/testcontainers/testcontainers' },
  @{ key='minio-java';                       path='io/minio/minio' }
)
foreach ($m in $maven) {
    $url = "https://repo1.maven.org/maven2/$($m.path)/maven-metadata.xml"
    Get-Save $url (Join-Path "$raw\maven" "$($m.key).maven-metadata.xml") | Out-Null
}

# ---------- npm ----------
$npm = @('react','react-dom','vite','typescript','tailwindcss','@tanstack/react-query','@vitejs/plugin-react','vitest','react-router','zustand','shadcn','pnpm')
foreach ($p in $npm) {
    $safe = ($p -replace '[/@]', '_')
    $url = "https://registry.npmjs.org/$([uri]::EscapeDataString($p))/latest"
    Get-Save $url (Join-Path "$raw\npm" "$safe.latest.json") | Out-Null
}

# ---------- PyPI ----------
$pypi = @('fastapi','uvicorn','pydantic','pydantic-settings','httpx','pytest')
foreach ($p in $pypi) {
    $url = "https://pypi.org/pypi/$p/json"
    $body = Get-Save $url (Join-Path "$raw\pypi" "$p.full.json")
    if ($body) {
        try {
            $j = $body | ConvertFrom-Json
            $trim = [ordered]@{
                name           = $j.info.name
                version        = $j.info.version
                requiresPython = $j.info.requires_python
                license        = $j.info.license
                licenseExpression = $j.info.license_expression
                summary        = $j.info.summary
                uploadTime     = ($j.releases.($j.info.version) | Select-Object -First 1).upload_time_iso_8601
                sourceUrl      = $url
            }
            [IO.File]::WriteAllText((Join-Path "$raw\pypi" "$p.trimmed.json"), ($trim | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))
        } catch { Log "PYPI-PARSE-FAIL $p $($_.Exception.Message)" }
    }
}

# ---------- Docker Hub ----------
$docker = @(
  @{ repo='library/mysql';       filter='8.4' },
  @{ repo='library/redis';       filter='' },
  @{ repo='library/rabbitmq';    filter='4' },
  @{ repo='getmeili/meilisearch'; filter='v1' },
  @{ repo='minio/minio';         filter='' }
)
foreach ($d in $docker) {
    $safe = ($d.repo -replace '[/]', '_')
    $url = "https://hub.docker.com/v2/repositories/$($d.repo)/tags?page_size=100"
    if ($d.filter) { $url += "&name=$($d.filter)" }
    $body = Get-Save $url (Join-Path "$raw\docker" "$safe.tags.json")
    if ($body) {
        try {
            $j = $body | ConvertFrom-Json
            $rows = @()
            foreach ($t in $j.results) {
                $rows += [ordered]@{
                    tag         = $t.name
                    digest      = $t.digest
                    lastUpdated = $t.last_updated
                    fullSize    = $t.full_size
                    amd64Digest = ($t.images | Where-Object { $_.architecture -eq 'amd64' } | Select-Object -First 1).digest
                }
            }
            [IO.File]::WriteAllText((Join-Path "$raw\docker" "$safe.tags.trimmed.json"), (ConvertTo-Json -InputObject $rows -Depth 4), (New-Object Text.UTF8Encoding($false)))
        } catch { Log "DOCKER-PARSE-FAIL $($d.repo) $($_.Exception.Message)" }
    }
}

# ---------- 平台/运行时 ----------
Get-Save 'https://www.python.org/ftp/python/' (Join-Path "$raw\platform" 'python-ftp-index.html') | Out-Null
Get-Save 'https://nodejs.org/dist/index.json' (Join-Path "$raw\platform" 'node-index.json') | Out-Null
Get-Save 'https://docs.spring.io/spring-boot/3.5/system-requirements.html' (Join-Path "$raw\platform" 'spring-boot-3.5-system-requirements.html') | Out-Null
Get-Save 'https://endoflife.date/api/spring-boot.json' (Join-Path "$raw\platform" 'spring-boot-eol.json') | Out-Null

# ---------- 本机工具链 ----------
$envLines = New-Object System.Collections.Generic.List[string]
$envLines.Add("collectedAt=" + (Get-Date -Format 'o'))
$envLines.Add("os=" + [Environment]::OSVersion.VersionString)
$envLines.Add("host=" + $env:COMPUTERNAME + " user=" + $env:USERNAME)
$envLines.Add("--- java -version ---"); $envLines.Add((& java -version 2>&1 | Out-String).Trim())
$envLines.Add("--- mvn -v ---");        $envLines.Add((& mvn -v 2>&1 | Out-String).Trim())
$envLines.Add("--- node -v ---");       $envLines.Add((& node -v 2>&1 | Out-String).Trim())
$envLines.Add("--- pnpm -v ---");       $envLines.Add((& pnpm -v 2>&1 | Out-String).Trim())
$envLines.Add("--- python -V ---");     $envLines.Add((& python -V 2>&1 | Out-String).Trim())
$envLines.Add("--- uv --version ---");  $envLines.Add((& uv --version 2>&1 | Out-String).Trim())
$envLines.Add("--- git --version ---"); $envLines.Add((& git --version 2>&1 | Out-String).Trim())
$envLines.Add("--- docker --version ---")
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCmd) { $envLines.Add((& docker --version 2>&1 | Out-String).Trim()) } else { $envLines.Add("docker CLI: NOT FOUND on PATH") }
$envLines.Add("dockerDesktopInstalled=" + (Test-Path 'C:\Program Files\Docker\Docker\Docker Desktop.exe'))
[IO.File]::WriteAllText((Join-Path $runDir 'environment.txt'), ($envLines -join "`r`n"), (New-Object Text.UTF8Encoding($false)))

$log.Add('RUNID=' + $runId)
[IO.File]::WriteAllText((Join-Path $runDir 'fetch.log'), ($log -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
"RUN_DIR=$runDir"
"requests=" + (($log | Where-Object { $_ -match '^(GET|FAIL)' }).Count)
($log | Where-Object { $_ -match '^FAIL' }) -join "`n"
