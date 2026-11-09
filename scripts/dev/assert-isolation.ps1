# S00 隔离护栏：故障/破坏性验证必须使用独立标识，脚本先自检再允许动手。
# 用法：
#   .\scripts\dev\assert-isolation.ps1 -Selftest                 # 纯离线自检，不碰任何服务
#   .\scripts\dev\assert-isolation.ps1 -Project larder-it-s01 -Db larder_it_s01 -Vhost larder-it-s01 -BucketPrefix it-s01/ -Port 13306
# 任何一项缺失/不合规即 exit 1；不做任何清理、不连接数据库。
param(
    [switch]$Selftest,
    [string]$Project,
    [string]$Db,
    [string]$Vhost,
    [string]$BucketPrefix,
    [int]$Port
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

function Assert-Marker([string]$name, [string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { $script:failures.Add("$name 缺失") ; return }
    if ($value -notmatch '^[a-z0-9][a-z0-9_/-]{3,63}/?$') { $script:failures.Add("$name 不合规: $value（需小写字母/数字/_-，4..64）") }
    if ($value -notmatch 'larder-it|it-') { $script:failures.Add("$name 缺少隔离标识 larder-it/ it-: $value") }
}

if ($Selftest) {
    # 自检只验证判定函数本身：合规样例通过、不合规样例必须被拒绝。
    $before = $script:failures.Count
    Assert-Marker 'project' 'larder-it-s00'
    Assert-Marker 'db' 'larder_it_s00'
    Assert-Marker 'vhost' 'larder-it-s00'
    Assert-Marker 'bucket' 'it-s00/'
    if ($script:failures.Count -ne $before) { Write-Host "SELFTEST FAIL: 合规样例被拒"; exit 1 }

    $script:failures.Clear()
    Assert-Marker 'project' 'larder'
    Assert-Marker 'db' 'larder'
    Assert-Marker 'vhost' 'production'
    Assert-Marker 'bucket' '/'
    Assert-Marker 'project2' ''
    if ($script:failures.Count -lt 4) { Write-Host "SELFTEST FAIL: 不合规样例未被拒绝（$($script:failures.Count)/4）"; exit 1 }

    Write-Host "SELFTEST PASS: 合规 4/4 通过，不合规 4/4 拒绝"
    exit 0
}

Assert-Marker 'project' $Project
Assert-Marker 'db' $Db
Assert-Marker 'vhost' $Vhost
Assert-Marker 'bucket' $BucketPrefix
if (-not $Port -or $Port -lt 1024) { $failures.Add("Port 未指定或为特权端口: $Port") }
if ($BucketPrefix -and -not $BucketPrefix.EndsWith('/')) { $failures.Add('BucketPrefix 必须以 / 结尾') }

if ($failures.Count -gt 0) {
    Write-Host "ISOLATION FAIL ($($failures.Count))"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "ISOLATION OK: project=$Project db=$Db vhost=$Vhost bucket=$BucketPrefix port=$Port"
Write-Host '注意：本脚本只做静态断言，不创建/清理任何资源。'
exit 0
