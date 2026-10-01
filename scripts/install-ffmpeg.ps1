# Optional portable FFmpeg, installed only inside this repository. No PATH or system changes.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$downloadDir = Join-Path $repoRoot 'target/ffmpeg-download'
New-Item -ItemType Directory -Force $downloadDir | Out-Null
$archivePath = Join-Path $downloadDir 'ffmpeg.zip'
$checksumPath = Join-Path $downloadDir 'ffmpeg.sha256'
Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip' -OutFile $archivePath
Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256' -OutFile $checksumPath
$expectedHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'FFmpeg checksum mismatch; nothing installed.'
}
$stagingDir = Join-Path $downloadDir ([guid]::NewGuid().ToString())
Expand-Archive -LiteralPath $archivePath -DestinationPath $stagingDir
$packageDir = Get-ChildItem -LiteralPath $stagingDir -Directory | Select-Object -First 1
$installDir = Join-Path $repoRoot 'tools/ffmpeg'
New-Item -ItemType Directory -Force $installDir | Out-Null
Copy-Item -LiteralPath (Join-Path $packageDir.FullName 'bin') -Destination $installDir -Recurse -Force
Get-ChildItem -LiteralPath $packageDir.FullName -File | Copy-Item -Destination $installDir -Force
Write-Output "FFmpeg installed in $installDir (SHA256 $expectedHash)."
