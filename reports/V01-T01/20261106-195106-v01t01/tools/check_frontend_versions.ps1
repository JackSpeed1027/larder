# 前端固定版本组合解析验证：只做 lockfile 解析（不下载包体、不生成应用），
# 目的是让 pnpm 真实校验 peer 依赖是否互相兼容，并留下 pnpm-lock.yaml 作为证据。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$runDir = 'D:\Work\Project\larder\reports\V01-T01\20261106-195106-v01t01'
$work   = 'D:\Work\AgentWorkSpace\DSHVibe\stage\reports\V01-T01\20261106-195106-v01t01\compat-check-frontend'
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Get-Json([string]$url) {
    try {
        return (Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 30).Content | ConvertFrom-Json
    } catch { return $null }
}

$typesReactDom = Get-Json 'https://registry.npmjs.org/@types/react-dom/latest'
if (-not $typesReactDom) { throw 'cannot resolve @types/react-dom latest' }

$pkg = [ordered]@{
    name    = 'larder-version-compat-check-frontend'
    private = $true
    version = '0.0.0-V01T01'
    type    = 'module'
    packageManager = 'pnpm@11.25.0'
    engines = [ordered]@{ node = '>=24.22.0 <25'; pnpm = '11.25.0' }
    dependencies = [ordered]@{
        'react'                  = '19.3.1'
        'react-dom'              = '19.3.1'
        'react-router'           = '8.5.1'
        '@tanstack/react-query'  = '5.106.1'
        'zustand'                = '5.0.16'
    }
    devDependencies = [ordered]@{
        'vite'                    = '8.3.6'
        '@vitejs/plugin-react'    = '6.2.1'
        'typescript'              = '7.0.2'
        'tailwindcss'             = '4.3.4'
        '@tailwindcss/vite'       = '4.3.4'
        'vitest'                  = '5.1.0'
        '@types/react'            = '19.3.0'
        '@types/react-dom'        = $typesReactDom.version
        '@types/node'             = $typesNode.version
    }
}
[IO.File]::WriteAllText((Join-Path $work 'package.json'), ($pkg | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))

Push-Location $work
$lines = & pnpm install --lockfile-only --ignore-scripts 2>&1 | Out-String
$code = $LASTEXITCODE
Pop-Location
[IO.File]::WriteAllText((Join-Path $work 'pnpm-install-lockfile-only.log'), $lines, (New-Object Text.UTF8Encoding($false)))
"types-react-dom=$($typesReactDom.version)"
"pnpm exit=$code lockfile=" + (Test-Path (Join-Path $work 'pnpm-lock.yaml'))
$lines
