# 停止并移除容器。**不加 -v**：数据卷是用户内容，删除要单独确认（AGENTS §3）。
param(
    [switch]$Dev
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    $files = @('-f', 'compose.yaml')
    if ($Dev) { $files += @('-f', 'compose.dev.yaml') }
    & docker compose @files down
    Write-Host "已停止容器；数据卷保留（如需清理请明确指定卷名后手动删除）"
} finally {
    Pop-Location
}
