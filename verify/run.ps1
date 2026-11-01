# ============================================================================
#  run.ps1 -- PowerShell wrapper for the verify/*.sh scripts
#
#  Why this exists:
#    On Windows, "bash" is often NOT on PATH (Git Bash installs wherever it
#    wants, e.g. D:\Git instead of C:\Program Files\Git). PowerShell then
#    fails with "bash is not recognized", and the "three commands before you
#    start" in AGENTS.md become unusable.
#
#  Usage (from the repo root, in PowerShell):
#      .\verify\run.ps1 status --offline
#      .\verify\run.ps1 status
#      .\verify\run.ps1 session check
#      .\verify\run.ps1 run-regression 8117 verify-health.py
#
#  If your policy blocks .ps1:
#      powershell -ExecutionPolicy Bypass -File .\verify\run.ps1 status --offline
#
#  Override bash location if needed:
#      $env:BASH_EXE = 'D:\Git\bin\bash.exe'
# ============================================================================
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Script,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path

# allow "status" or "status.sh"
$name = $Script
if ($name -notmatch '\.sh$') { $name = "$name.sh" }
$target = Join-Path $dir $name

if (-not (Test-Path $target)) {
    Write-Host "[ERROR] no such script: $name" -ForegroundColor Red
    $all = (Get-ChildItem $dir -Filter *.sh | Select-Object -ExpandProperty Name) -join ' '
    Write-Host "        available: $all"
    exit 2
}

# 1) explicit env var  2) known install locations  3) whatever 'bash' resolves to
$candidates = @(
    $env:BASH_EXE,
    'D:\Git\bin\bash.exe',
    'C:\Program Files\Git\bin\bash.exe',
    'C:\Program Files (x86)\Git\bin\bash.exe',
    "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
) | Where-Object { $_ -and (Test-Path $_) }

if (-not $candidates) {
    $cmd = Get-Command bash -ErrorAction SilentlyContinue
    if ($cmd) { $candidates = @($cmd.Source) }
}

if (-not $candidates) {
    Write-Host "[ERROR] Git Bash not found." -ForegroundColor Red
    Write-Host "        Install Git for Windows, or set: `$env:BASH_EXE = 'C:\path\to\bash.exe'"
    exit 2
}

$bash = @($candidates)[0]
& $bash $target @Rest
exit $LASTEXITCODE
