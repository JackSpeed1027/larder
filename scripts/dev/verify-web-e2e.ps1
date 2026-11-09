# Web E2E 门禁：自起 Vite dev server → 跑 Playwright(Edge) → 杀进程。
# 依赖：docker compose 的 api/web 已在 8080；frontend 依赖已安装。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$env:E2E_ADMIN_PASSWORD = ((Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^LARDER_ADMIN_PASSWORD=' } | ForEach-Object { $_.Split('=', 2)[1] })).Trim()

$dev = Start-Process -FilePath "cmd.exe" -ArgumentList "/c pnpm dev --host 127.0.0.1 --port 5173" -WorkingDirectory (Join-Path $root 'frontend') -PassThru -WindowStyle Hidden
try {
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 1
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect('127.0.0.1', 5173)
            $client.Close()
            $ready = $true
            break
        } catch { }
    }
    if (-not $ready) { throw 'Vite dev server 60 秒内未就绪' }

    Push-Location (Join-Path $root 'frontend')
    try {
        $output = & cmd /c "pnpm exec playwright test e2e --reporter=line 2>&1" | Out-String
        $code = $LASTEXITCODE
        Write-Host $output
        if ($code -ne 0) { exit $code }
        if ($output -notmatch '(\d+) passed') { throw 'Playwright 没有报告 passed' }
        exit 0
    } finally {
        Pop-Location
    }
} finally {
    & taskkill /PID $dev.Id /T /F 2>&1 | Out-Null
}
