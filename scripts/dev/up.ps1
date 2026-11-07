# 启动八服务并等待全部健康。
# 默认是发布拓扑（只有 web 绑定 127.0.0.1:8080）；加 -Dev 才挂开发端口与管理台。
param(
    [switch]$Dev
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    $files = @('-f', 'compose.yaml')
    if ($Dev) { $files += @('-f', 'compose.dev.yaml') }
    Write-Host "docker compose $($files -join ' ') up -d --wait"
    & docker compose @files up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "compose up failed: exit $LASTEXITCODE" }
    & docker compose @files ps
} finally {
    Pop-Location
}
