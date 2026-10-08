# 采集真机截图（宣传片素材）。一次性脚本，不入库。
# 用法: .\capture.ps1 -Name "01-home"          仅截图
#       .\capture.ps1 -Name "02-x" -Tap "65,172"  先点再截图
param(
  [Parameter(Mandatory=$true)][string]$Name,
  [string]$Tap = "",
  [string]$Swipe = "",
  [int]$Wait = 2,
  [switch]$KeyHome
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dir = "$PSScriptRoot\assets"
New-Item -ItemType Directory -Path $dir -Force | Out-Null

# 经 uid 2000 特权通道执行设备操作（app 身份做不到 input）
function Invoke-Priv([string]$cmd) {
  $inner = 'run-as com.dsh.android sh -c "DSH_HOME=/data/user/0/com.dsh.android/files/home/.dsh /data/user/0/com.dsh.android/files/termux/usr/bin/node /data/user/0/com.dsh.android/files/dsh-android-shell.mjs \"' + $cmd + '\""'
  & $adb shell $inner 2>&1 | Out-Null
}

if ($Tap) {
  $xy = $Tap -split ','
  Invoke-Priv "input tap $($xy[0]) $($xy[1])"
}
if ($Swipe) {
  Invoke-Priv "input swipe $Swipe"
}
if ($KeyHome) {
  Invoke-Priv "input keyevent 3"
}

Start-Sleep -Seconds $Wait
& $adb exec-out screencap -p > "$dir\$Name.png"
$size = (Get-Item "$dir\$Name.png").Length
Write-Host "$Name.png  $size bytes"
