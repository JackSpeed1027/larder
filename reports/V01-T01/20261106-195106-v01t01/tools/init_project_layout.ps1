# 初始化 D:\Work\Project\larder 目录并把交接包按"仅缺失才复制"落位。
# 说明：本脚本只做目录创建与文件复制，不删除、不覆盖任何已存在文件；
# 同名文件只做 SHA256 比较并记录差异，交由人工判断。
$ErrorActionPreference = 'Stop'
$target  = 'D:\Work\Project\larder'
$source  = 'D:\Work\Project\vibe-project\larder-agent-playbook-2026-11-05'
$topDirs = @('docs', 'contracts', 'tasks', 'prompts', 'design', 'scripts')

function Get-Sha256([string]$path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $target)) {
    New-Item -ItemType Directory -Path $target | Out-Null
    "created: $target"
} else {
    "exists:  $target (keep existing content)"
}
foreach ($d in $topDirs) {
    $p = Join-Path $target $d
    if (-not (Test-Path -LiteralPath $p)) { New-Item -ItemType Directory -Path $p | Out-Null }
}

$copied = New-Object System.Collections.Generic.List[string]
$same   = New-Object System.Collections.Generic.List[string]
$diff   = New-Object System.Collections.Generic.List[string]

$items = @((Join-Path $source 'AGENTS.md'))
foreach ($d in $topDirs) { $items += (Get-ChildItem -LiteralPath (Join-Path $source $d) -Recurse -File | ForEach-Object { $_.FullName }) }

foreach ($src in $items) {
    $rel = $src.Substring($source.Length + 1)
    $dst = Join-Path $target $rel
    $dstDir = Split-Path -Parent $dst
    if (-not (Test-Path -LiteralPath $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
    if (-not (Test-Path -LiteralPath $dst)) {
        Copy-Item -LiteralPath $src -Destination $dst
        $copied.Add($rel)
    } elseif ((Get-Sha256 $src) -eq (Get-Sha256 $dst)) {
        $same.Add($rel)
    } else {
        $diff.Add($rel)   # 同名但内容不同：保留现有文件，仅记录
    }
}

"copied=$($copied.Count) identical=$($same.Count) differing=$($diff.Count)"
if ($diff.Count -gt 0) { "DIFFERING:"; $diff | ForEach-Object { "  $_" } }
if ($same.Count -gt 0) { "IDENTICAL-SKIPPED:"; $same | ForEach-Object { "  $_" } }
