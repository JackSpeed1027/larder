# 从 contracts/ 生成三端消费者类型与 Mock fixture。
# 工具版本固定；生成结果写入 scripts/contracts/generated.sha256，check_contracts.py --check 可复验。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    $tsDir = Join-Path $root 'frontend\src\generated'
    $pyDir = Join-Path $root 'runner\src\larder_runner\api\models'
    $mockDir = Join-Path $root 'frontend\mocks\fixtures'
    New-Item -ItemType Directory -Force -Path $tsDir, $pyDir, $mockDir | Out-Null

    $env:HTTPS_PROXY = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { 'http://127.0.0.1:7890' }
    $env:HTTP_PROXY = $env:HTTPS_PROXY
    $env:NO_PROXY = 'localhost,127.0.0.1'
    # Windows 默认 locale 是 GBK：不设 UTF-8 模式时 datamodel-codegen 会把中文描述读成乱码
    $env:PYTHONUTF8 = '1'

    Write-Host '== TypeScript: OpenAPI 端点类型 =='
    & pnpm dlx openapi-typescript@7.13.0 contracts/runner.openapi.json -o frontend/src/generated/runner-api.ts
    if ($LASTEXITCODE -ne 0) { throw "openapi-typescript failed: $LASTEXITCODE" }

    Write-Host '== TypeScript: JSON Schema 契约类型 =='
    foreach ($pair in @(
            @{ in = 'contracts/run-request.schema.json'; out = 'frontend/src/generated/run-request.ts' },
            @{ in = 'contracts/run-response.schema.json'; out = 'frontend/src/generated/run-response.ts' },
            @{ in = 'contracts/run-event.schema.json'; out = 'frontend/src/generated/run-event.ts' })) {
        & pnpm dlx json-schema-to-typescript@15.0.4 $pair.in -o $pair.out
        if ($LASTEXITCODE -ne 0) { throw "json2ts failed for $($pair.in): $LASTEXITCODE" }
    }

    Write-Host '== Python: Pydantic 模型 =='
    $gen = 'datamodel-code-generator==0.26.5'
    foreach ($pair in @(
            @{ in = 'contracts/run-request.schema.json'; out = 'runner/src/larder_runner/api/models/run_request.py'; cls = 'RunRequest' },
            @{ in = 'contracts/run-response.schema.json'; out = 'runner/src/larder_runner/api/models/run_response.py'; cls = 'RunResponse' },
            @{ in = 'contracts/run-event.schema.json'; out = 'runner/src/larder_runner/api/models/run_event.py'; cls = 'RunEvent' })) {
        & uv tool run --from $gen datamodel-codegen --input $pair.in --output $pair.out `
            --input-file-type jsonschema --output-model-type pydantic_v2.BaseModel --target-python-version 3.12 `
            --use-standard-collections --use-union-operator --class-name $pair.cls
        if ($LASTEXITCODE -ne 0) { throw "datamodel-codegen failed for $($pair.in): $LASTEXITCODE" }
    }
    Set-Content -Path 'runner/src/larder_runner/api/models/__init__.py' -Value '"""由 contracts/ 生成的 Pydantic 模型（脚本：scripts/contracts/generate.ps1）。"""' -Encoding UTF8

    Write-Host '== Mock fixtures: 与 contracts/examples 同源 =='
    Copy-Item -Path 'contracts/examples/*.json' -Destination $mockDir -Force

    Write-Host '== 记录生成产物 hash =='
    $targets = @(
        'frontend/src/generated/runner-api.ts',
        'frontend/src/generated/run-request.ts',
        'frontend/src/generated/run-response.ts',
        'frontend/src/generated/run-event.ts',
        'runner/src/larder_runner/api/models/run_request.py',
        'runner/src/larder_runner/api/models/run_response.py',
        'runner/src/larder_runner/api/models/run_event.py'
    )
    $lines = foreach ($t in $targets) {
        if (-not (Test-Path $t)) { throw "missing generated file: $t" }
        $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $t).Hash.ToLowerInvariant()
        "$h  $($t.Replace('\','/'))"
    }
    # 用无 BOM 的 UTF-8 写清单：带 BOM 会让第一行 hash 带上 \ufeff，校验器会误报漂移
    [IO.File]::WriteAllText((Join-Path $root 'scripts/contracts/generated.sha256'), ($lines -join "`n"), (New-Object Text.UTF8Encoding($false)))
    Write-Host "generated $($targets.Count) files"
} finally {
    Pop-Location
}
