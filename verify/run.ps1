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
#  🔴🔴 本文件必须保存为 UTF-8 with BOM（文件头 3 字节 = EF BB BF）🔴🔴
#
#  Windows PowerShell 5.1 读"没有 BOM"的 .ps1 时按系统 ANSI 代码页（中文机器
#  就是 GBK）解码 ⇒ 中文全变乱码 ⇒ 某一行字符串的收尾引号被汉字的后半个
#  字节"吃掉" ⇒ 整个脚本解析失败。
#
#  最坑的是失败后的表现：解析失败根本不会执行任何语句，所以
#  $LASTEXITCODE 保持原值（0），调用方看到的是"退出码 0 + 零输出"的假成功。
#  2026-11-01 实测踩到，细节见 docs/architecture/ENVIRONMENT.md §1.1。
#
#  改这个文件之后，请务必确认前 3 字节还是 EF BB BF：
#      head -c 3 verify/run.ps1 | od -An -tx1
# ============================================================================
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Script,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------------------------------------------------------------------
#  ⚠️ 别给这个脚本的输出接管道
#
#  实测（2026-11-01）：`.\verify\run.ps1 status --offline | Out-String` 有两个副作用：
#    1. 中文变乱码 —— .sh 输出是 UTF-8，管道里被按 GBK 解码；
#       试过 [Console]::OutputEncoding = UTF8（确认赋值成功），这里仍然无效
#    2. $LASTEXITCODE 变成 0 —— 脚本泡在管道里，exit N 传不出来（实测过）
#  直接运行（不接管道）时中文正常、退出码也正确。
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
#  0. 自我保护：PATHEXT 被改坏时，PowerShell 不把 .exe 当可执行文件
#
#  症状（2026-11-01 实测，本机 PATHEXT 只有 ".CPL"）：
#      & 'D:\Git\bin\bash.exe' -c 'echo hi'     -> 无输出、无报错、$LASTEXITCODE 为空
#      .\verify\run.ps1 status --offline        -> exit 0、零输出
#  看起来像"跑成功了、没消息"，其实是把 .exe 当文档"打开"了，什么都没执行。
#  这是最坏的一种失败：假成功。
#
#  所以先把 .EXE 补回去，再继续。
# ---------------------------------------------------------------------------
if ($env:PATHEXT -notmatch '\.EXE') {
    $env:PATHEXT = (@($env:PATHEXT, '.EXE') | Where-Object { $_ }) -join ';'
}

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

# ---------------------------------------------------------------------------
#  1. 探活：确认解释器真的能启动
#
#  不做这一步，解释器坏了（PATHEXT 没救回来、或撞上 WSL 的 bash 桩）也会
#  一路静默走到底，最后给一个漂亮的 exit 0。宁可多花 200ms，也要把
#  "假成功" 变成 "明确失败"。
# ---------------------------------------------------------------------------
$probe = & $bash -c 'echo __RUNPS_OK__' 2>$null
if (-not ($probe -match '__RUNPS_OK__')) {
    Write-Host "[ERROR] bash 启动失败（既没输出、也没报错）：$bash" -ForegroundColor Red
    Write-Host "        典型原因：PowerShell 的 PATHEXT 不含 .EXE，.exe 被当成'文档'打开。"
    Write-Host "        当前 PATHEXT = [$env:PATHEXT]"
    Write-Host "        手工修复：`$env:PATHEXT = `"`$env:PATHEXT;.EXE`""
    Write-Host "        永久修复：系统环境变量里把 PATHEXT 补回 .COM;.EXE;.BAT;.CMD"
    exit 2
}

& $bash $target @Rest
$code = $LASTEXITCODE

# 2. 兜底：真跑完了却没拿到退出码 —— 也要说出来，不要假装成功
if ($null -eq $code) {
    Write-Host "[WARN] 脚本执行后没有拿到退出码，结果不可信。" -ForegroundColor Yellow
    exit 2
}
exit $code
