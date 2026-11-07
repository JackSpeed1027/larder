# 构建 MinIO 的 Linux 二进制（容器用），并校验 sha256 与版本锁一致。
# 官方下载渠道已关闭（ADR-0003），这是唯一的可复核构建路径。
param(
    [string]$Tag = 'RELEASE.2025-10-15T17-29-55Z',
    [string]$ExpectedSha256 = '1511a2b15f6513d10cf89d86f8a96e836dfb9bddf6e9c1f3e0c8d9e2cda1920a',
    [string]$WorkDir = "$env:TEMP\larder-minio-build"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$go = (Get-Command go -ErrorAction SilentlyContinue).Source
if (-not $go) { $go = 'C:\Program Files\Go\bin\go.exe' }
if (-not (Test-Path $go)) { throw '需要 Go 工具链（winget install GoLang.Go）' }

if (-not (Test-Path $WorkDir)) {
    git clone --depth 1 --branch $Tag https://github.com/minio/minio.git $WorkDir
}
Push-Location $WorkDir
try {
    $env:GOPROXY = 'https://goproxy.cn,direct'
    $env:CGO_ENABLED = '0'
    $env:GOOS = 'linux'
    $env:GOARCH = 'amd64'
    $ld = "-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.ReleaseTag=$Tag -X github.com/minio/minio/cmd.CopyrightYear=2025"
    & $go build -trimpath -ldflags $ld -o minio-linux-amd64 .
    if ($LASTEXITCODE -ne 0) { throw "go build failed: $LASTEXITCODE" }

    $sha = (Get-FileHash -Algorithm SHA256 ./minio-linux-amd64).Hash.ToLowerInvariant()
    Write-Host "built sha256=$sha"
    if ($ExpectedSha256 -and $sha -ne $ExpectedSha256) {
        throw "sha256 mismatch: expected $ExpectedSha256 (版本锁), got $sha —— 不要静默替换二进制"
    }
    Copy-Item ./minio-linux-amd64 (Join-Path $root 'infra\minio\minio-linux-amd64') -Force
    Write-Host "已复制到 infra/minio/minio-linux-amd64"
} finally {
    Pop-Location
}
