# 输出 IDEA / 本机调试所需的环境变量（从 .env 读取，映射到开发端口）。
#
# 用法：
#   powershell -File scripts/dev/print-debug-env.ps1            # 打印 KEY=VALUE
#   powershell -File scripts/dev/print-debug-env.ps1 -AsNewLine # 每行一个 KEY=VALUE（便于粘贴）
param([switch]$AsNewLine)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envPath = Join-Path $root '.env'
if (-not (Test-Path $envPath)) { throw "缺少 $envPath（先复制 .env.example 并填值）" }

$envMap = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $envMap[$k.Trim()] = $v.Trim()
}

# 开发端口：compose.dev.yaml 把 mysql/rabbitmq/minio 映射到本机
$pairs = [ordered]@{
    'LARDER_DB_HOST'          = '127.0.0.1'
    'LARDER_DB_PORT'          = '3306'
    'LARDER_DB_NAME'          = $envMap['LARDER_DB_NAME']
    'LARDER_DB_USER'          = $envMap['LARDER_DB_USER']
    'LARDER_DB_PASSWORD'      = $envMap['LARDER_DB_PASSWORD']
    'LARDER_ADMIN_USERNAME'   = $envMap['LARDER_ADMIN_USERNAME']
    'LARDER_ADMIN_PASSWORD'   = $envMap['LARDER_ADMIN_PASSWORD']
    'LARDER_INTERNAL_TOKEN'   = $envMap['LARDER_INTERNAL_TOKEN']
    'LARDER_S3_ENDPOINT'      = 'http://127.0.0.1:9000'
    'LARDER_S3_BUCKET'        = 'larder-artifacts'
    'LARDER_S3_ACCESS_KEY'    = $envMap['LARDER_MINIO_ROOT_USER']
    'LARDER_S3_SECRET_KEY'    = $envMap['LARDER_MINIO_ROOT_PASSWORD']
    'LARDER_OUTBOX_ENABLED'   = 'true'
    'LARDER_RECONCILE_ENABLED'= 'true'
    'SPRING_RABBITMQ_HOST'    = '127.0.0.1'
    'SPRING_RABBITMQ_PORT'    = '15673'   # 开发映射：容器内 5672 → 本机 15673
    'SPRING_RABBITMQ_USERNAME'= $envMap['LARDER_RABBITMQ_USER']
    'SPRING_RABBITMQ_PASSWORD'= $envMap['LARDER_RABBITMQ_PASSWORD']
}

if ($AsNewLine) {
    $pairs.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
} else {
    "IDEA Run Configuration → Environment variables（粘贴这一整行）："
    ($pairs.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ';'
}
Write-Host ''
Write-Host '提示：IDEA 调试前先停掉容器 api，避免 8080 端口冲突：'
Write-Host '    docker compose stop api web'
Write-Host '计算正文 sha256（PowerShell）：'
Write-Host '    $b=[Text.Encoding]::UTF8.GetBytes("你的正文"); ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($b)) -replace "-","").ToLower()'
