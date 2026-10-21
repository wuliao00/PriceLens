# PriceLens Desktop 2.6.0 (Windows x64, signed portable) downloader
# Downloads the 13 Gitee parts (each < 10 MB so anonymous raw download works),
# verifies every sha256, then extracts with 7-Zip if installed.
# Nothing is executed from the archive automatically.
$ErrorActionPreference = 'Stop'
$base = 'https://gitee.com/wuliao11541/PriceLens/raw/dist'
$dir = Join-Path $PSScriptRoot 'PriceLens-2.6.0-signed'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$sumUrl = "$base/SHA256SUMS-desktop-2.6.0-9m.txt"
$sumFile = Join-Path $dir 'SHA256SUMS-desktop-2.6.0-9m.txt'
Write-Host "Downloading checksums..."
Invoke-WebRequest -UseBasicParsing -Uri $sumUrl -OutFile $sumFile

$parts = @()
foreach ($line in Get-Content -LiteralPath $sumFile) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') {
        $parts += [pscustomobject]@{ Hash = $matches[1].ToLower(); Name = $matches[2].Trim() }
    }
}
if ($parts.Count -eq 0) { throw 'No checksums found in SHA256SUMS file' }

foreach ($p in $parts) {
    $out = Join-Path $dir $p.Name
    if (Test-Path -LiteralPath $out) {
        $old = (Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash.ToLower()
        if ($old -eq $p.Hash) { Write-Host "OK (cached) $($p.Name)"; continue }
    }
    Write-Host "Downloading $($p.Name) ..."
    Invoke-WebRequest -UseBasicParsing -Uri "$base/$($p.Name)" -OutFile $out
    $new = (Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash.ToLower()
    if ($new -ne $p.Hash) { throw "sha256 mismatch: $($p.Name) expected $($p.Hash) got $new" }
    Write-Host "OK $($p.Name)"
}

$first = Join-Path $dir $parts[0].Name
$sevenZip = @(
    (Join-Path $env:ProgramFiles '7-Zip\7z.exe'),
    (Join-Path ${env:ProgramFiles(x86)} '7-Zip\7z.exe')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

if ($sevenZip) {
    $extract = Join-Path $dir 'app'
    Write-Host "Extracting with $sevenZip ..."
    & $sevenZip x $first "-o$extract" -y | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "7-Zip failed with exit code $LASTEXITCODE" }
    Write-Host "Done: $extract\PriceLens.exe"
    Write-Host "Signature is self-signed: CN=PriceLens (Self-Signed), SHA-256 B253FA470910B222FF7531669E6454A551ADCD7079CBA7CA623F71EC75F501AF"
} else {
    Write-Host "All parts verified in: $dir"
    Write-Host "7-Zip not found. Install it, then right-click $($parts[0].Name) and extract."
}
