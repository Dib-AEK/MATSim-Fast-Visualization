$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    python (Join-Path $PSScriptRoot 'build-portable.py')
    if ($LASTEXITCODE -ne 0) { throw "Portable build failed (exit $LASTEXITCODE)" }
} finally { Pop-Location }
