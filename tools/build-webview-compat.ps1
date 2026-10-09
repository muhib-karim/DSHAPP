# 生成 WebView 兼容资产（webview-compat.zip）
#
# ── 为什么需要它 ──────────────────────────────────────────────────────
#
# DSH 官方前端的 bundle 是按较新的 Chromium 编译的，而**语法**（不是 API）
# 补不了 —— 一旦内核版本不够，整份 chunk 在解析阶段就失败、一行都不执行。
# 表现是「HTML 与 CSS 都正常，`#root` 永远是空的」，用户只看到白屏，
# 宿主侧连一个错误回调都收不到。
#
# 真机实测（用户反馈，vivo Y52s / Android 10，WebView 内核 **83**）：
#   index  : class static block ×2（需 94）、逻辑赋值 ??= ×25（需 85）
#   vendor : private method ×3（需 84）、逻辑赋值 ×7（需 85）
#   langs  : 逻辑赋值 ×2
#   CSS    : inset ×18（需 87）
#
# 其中 class static block 是**纯语法**，`webview-polyfill.js` 那种运行时补丁
# 完全无能为力 —— 必须在构建期降级编译。
#
# ── 做法 ──────────────────────────────────────────────────────────────
#
# 用 esbuild 把 `engine.bin` 里的前端 dist 降到 `--target=chrome74`，
# **只把内容真的变了的文件**打进 zip（实测只有 4 个 js + 1 个 css）。
# App 侧 `EngineManager.ensureWebViewCompatAssets()` 把它解到 dist 上覆盖。
#
# ⚠️ **换了 engine.bin 必须重跑本脚本**，并把产物一起提交。
#    文件名带内容 hash（`index-5SrrfWpU.js`），引擎升级后必然对不上。
#
# ⚠️ esbuild 只是**构建期工具**，不进 APK、设备上也不需要它。
#
# 用法：
#   .\tools\build-webview-compat.ps1
#   .\tools\build-webview-compat.ps1 -Target chrome83

[CmdletBinding()]
param(
    # 降级目标。chrome74 = 项目已知最低实测内核（83）之下再留一档余量。
    # 调高会让产物变大但更接近源码；调低可能触发 esbuild 无法降级的语法。
    [string]$Target = "chrome74",

    # 兼容资产版本号。改了降级逻辑/目标就 +1 —— App 侧靠它判断要不要重新覆盖。
    [string]$CompatVersion = "1",

    [string]$EngineBin,
    [string]$OutZip
)

$ErrorActionPreference = "Stop"

# ⚠️ 默认路径必须在 param 块**之外**算：`$PSScriptRoot` 在 param 默认值里还是空的
#    （Windows PowerShell 5.1 下同样如此），放进去会直接报「Path 为空字符串」。
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $EngineBin) { $EngineBin = Join-Path $repoRoot "app\src\main\assets\engine.bin" }
if (-not $OutZip) { $OutZip = Join-Path $repoRoot "app\src\main\assets\webview-compat.zip" }

# ── 1. 准备 esbuild（临时目录，不进仓库）────────────────────────────────
$work = Join-Path $env:TEMP "dsh-webview-compat"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null

$esbuild = Join-Path $work "node_modules\.bin\esbuild.cmd"
if (-not (Test-Path $esbuild)) {
    Write-Host "安装 esbuild（构建期工具，不进 APK）…"
    # 走代理：本机直连 npm 会超时（与 gradle.properties 里同一个代理）
    $env:HTTPS_PROXY = "http://127.0.0.1:7897"
    $env:HTTP_PROXY = "http://127.0.0.1:7897"
    & "npm.cmd" install esbuild@0.25.0 --prefix $work --no-audit --no-fund --loglevel=error
    if ($LASTEXITCODE -ne 0) { throw "esbuild 安装失败" }
}

# ── 2. 从 engine.bin 解出前端 dist ──────────────────────────────────────
$extract = Join-Path $work "extract"
New-Item -ItemType Directory -Path $extract -Force | Out-Null
$tgz = Join-Path $work "engine.tar.gz"
Copy-Item $EngineBin $tgz -Force

Write-Host "解出前端 dist…"
Push-Location $extract
& tar -xzf $tgz "node_modules/@deepseek-ai/dsh-web-frontend/dist"
Pop-Location

$dist = Join-Path $extract "node_modules\@deepseek-ai\dsh-web-frontend\dist"
if (-not (Test-Path $dist)) { throw "engine.bin 里没找到前端 dist" }

# ── 3. 逐个降级，只留真有语法缺口的 ────────────────────────────────────
#
# 判据是「同一份输入，--target=esnext 与 --target=$Target 的输出是否相同」：
# esbuild 只在**确实需要降级**时才会改写代码，所以输出相同 ⇔ 该文件没有缺口。
#
# ⚠️ **不能拿「输入 hash ≠ 输出 hash」当判据**：esbuild 即使不做任何降级也会
#    重新格式化（去缩进、统一引号），输出必然不同，那会把 61 个文件全收进来
#    （实测资产从 0.8 MB 涨到 64 个条目）。
#
# ⚠️ **也不能拿正则扫 `??=` / `static {` 当判据**：正则字面量里就可能有这些字符。
#    实测 `langs/make-CHLpvVh8.js` 里那处 `??=` 位于 `([+:?]??=)?` 之内，是
#    正则的文本而不是运算符 —— 用正则判会把 59 个 langs 全误收进来。
#    交给 esbuild 自己判，它认语法、不认字符串。
$out = Join-Path $work "out"
New-Item -ItemType Directory -Path $out | Out-Null
$probe = Join-Path $work "probe"
New-Item -ItemType Directory -Path $probe -Force | Out-Null

$changed = @()
$sources = Get-ChildItem $dist -Recurse -File -Include *.js, *.css
Write-Host "扫描 $($sources.Count) 个 js/css（目标 $Target）…"

foreach ($f in $sources) {
    $rel = $f.FullName.Substring($dist.Length + 1)
    $dst = Join-Path $out $rel
    New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null

    # 基准：不降级，只做同样的格式化
    $baseline = Join-Path $probe "baseline"
    & $esbuild $f.FullName --target=esnext "--outfile=$baseline" --log-level=error 2>&1 |
        ForEach-Object { if ("$_".Trim()) { Write-Warning "esbuild: $_" } }
    if ($LASTEXITCODE -ne 0) { throw "esbuild 基准转换失败: $rel" }

    & $esbuild $f.FullName "--target=$Target" "--outfile=$dst" --log-level=error 2>&1 |
        ForEach-Object { if ("$_".Trim()) { Write-Warning "esbuild: $_" } }
    if ($LASTEXITCODE -ne 0) { throw "esbuild 降级失败: $rel" }

    if ((Get-FileHash $baseline).Hash -ne (Get-FileHash $dst).Hash) {
        $changed += $rel
    } else {
        Remove-Item $dst -Force
    }
}

if ($changed.Count -eq 0) {
    Write-Warning "没有任何文件需要降级 —— 前端可能已按低版本编译，或 $Target 给高了"
}

Write-Host "需要降级的文件（$($changed.Count) 个）:"
$changed | ForEach-Object { Write-Host "  $_" }

# ── 4. 打 zip（条目为相对路径，无顶层目录 —— 与其它资产同一约定）──────
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $OutZip) { Remove-Item $OutZip -Force }
$zip = [System.IO.Compression.ZipFile]::Open($OutZip, 'Create')
try {
    foreach ($rel in $changed) {
        $src = Join-Path $out $rel
        # zip 内部一律用 / 分隔
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $src, ($rel -replace '\\', '/')) | Out-Null
    }
    # 版本戳：App 侧读它决定要不要重新覆盖
    $stampTmp = Join-Path $work "stamp.txt"
    Set-Content -Path $stampTmp -Value "$Target-$CompatVersion" -NoNewline -Encoding ascii
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $stampTmp, ".compat-stamp") | Out-Null
} finally {
    $zip.Dispose()
}

$size = (Get-Item $OutZip).Length
Write-Host ""
Write-Host "已生成: $OutZip"
Write-Host ("  条目 {0} 个，{1:N0} 字节（{2:N2} MB）" -f ($changed.Count + 1), $size, ($size / 1MB))
Write-Host ""
Write-Host "⚠️ 记得同步 EngineManager.WEBVIEW_COMPAT_VERSION = `"$Target-$CompatVersion`""
