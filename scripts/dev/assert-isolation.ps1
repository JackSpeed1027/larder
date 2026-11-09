# S00 隔离护栏：破坏性/故障验证必须使用带 larder-it / it- 标识的独立资源。
# 用法：
#   .\scripts\dev\assert-isolation.ps1 -Selftest
#   .\scripts\dev\assert-isolation.ps1 -Project larder-it-s01 -Db larder_it_s01 -Vhost larder-it-s01 -BucketPrefix it-s01/ -Port 13306
# 只做静态断言：不创建、不清理、不连接任何服务。
param(
    [switch]$Selftest,
    [string]$Project,
    [string]$Db,
    [string]$Vhost,
    [string]$BucketPrefix,
    [int]$Port
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 纯函数：返回错误消息数组，不共享可变状态
function Test-Marker([string]$name, [string]$value) {
    $errors = @()
    if ([string]::IsNullOrWhiteSpace($value)) {
        return @("$name 缺失")
    }
    if ($value -notmatch '^[a-z0-9][a-z0-9_/-]{2,63}$') {
        $errors += "$name 格式不合规（需小写字母/数字/_-/，3..64 字符）: $value"
    }
    if ($value -notmatch 'it-|it_|larder_it|larder-it') {
        $errors += "$name 缺少隔离标识（larder-it 或 it-）: $value"
    }
    return $errors
}

function Test-Port([int]$value) {
    if ($value -lt 1024) { return @("Port 缺失或为特权端口: $value") }
    return @()
}

function Test-Bucket([string]$value) {
    $errors = Test-Marker 'bucket' $value
    if (-not [string]::IsNullOrWhiteSpace($value) -and -not $value.EndsWith('/')) {
        $errors += "bucket 必须以 / 结尾: $value"
    }
    return $errors
}

if ($Selftest) {
    $failures = @()
    $valid = @(
        @('project', 'larder-it-s00'),
        @('db', 'larder_it_s00'),
        @('vhost', 'larder-it-s00'),
        @('bucket', 'it-s00/')
    )
    foreach ($case in $valid) {
        $errors = Test-Marker $case[0] $case[1]
        if (@($errors).Count -gt 0) { $failures += "合规样例被拒: $($case[0])=$($case[1]) -> $($errors -join '; ')" }
    }
    $portOk = @(Test-Port 13306)
    if ($portOk.Count -ne 0) { $failures += '合规端口 13306 被拒' }
    if (Test-Bucket 'it-s00/') { $failures += '合规 bucket 被拒' }

    $invalid = @(
        @('project', 'larder'),
        @('db', 'larder'),
        @('vhost', 'production'),
        @('bucket', 'it-s00'),
        @('project', '')
    )
    $rejected = 0
    foreach ($case in $invalid) {
        $errors = if ($case[0] -eq 'bucket') { Test-Bucket $case[1] } else { Test-Marker $case[0] $case[1] }
        if (@($errors).Count -gt 0) { $rejected += 1 } else { $failures += "不合规样例未被拒: $($case[0])=$($case[1])" }
    }
    $portBad = @(Test-Port 80)
    if ($portBad.Count -eq 0) { $failures += '特权端口 80 未被拒' } else { $rejected += 1 }

    if ($failures.Count -gt 0) {
        Write-Host "SELFTEST FAIL ($($failures.Count))"
        $failures | ForEach-Object { Write-Host "  - $_" }
        exit 1
    }
    Write-Host "SELFTEST PASS: 合规 6/6 通过，不合规 6/6 拒绝"
    exit 0
}

$errors = @()
$errors += Test-Marker 'project' $Project
$errors += Test-Marker 'db' $Db
$errors += Test-Marker 'vhost' $Vhost
$errors += Test-Bucket $BucketPrefix
$errors += Test-Port $Port

if (@($errors).Count -gt 0) {
    Write-Host "ISOLATION FAIL ($($errors.Count))"
    $errors | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "ISOLATION OK: project=$Project db=$Db vhost=$Vhost bucket=$BucketPrefix port=$Port"
exit 0
