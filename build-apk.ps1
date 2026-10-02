# ============================================================
#  PriceLens-Android 本地出包脚本（v2.7.0 起：正式 release 签名，不再用 debug keystore）
#
#  为什么弃用 debug 签名：debug.keystore 是公开常识口令，拿它签的"正式包"等于
#  任何人若能改 GitHub Release 资产就能给所有设备推合法升级；而且换正式密钥后，
#  老 debug 签名包的用户无法覆盖安装 —— 发布说明必须写清"卸载重装一次"。
#
#  前置条件：
#    1. 本机 local.properties（被 .gitignore 排除）配好四项：
#       PRICLENS_STORE_FILE / PRICLENS_STORE_PASSWORD / PRICLENS_KEY_ALIAS / PRICLENS_KEY_PASSWORD
#    2. 版本号唯一真源 = app/build.gradle.kts（先提交版本变更，再跑本脚本；本脚本不改源码）
#
#  用法：powershell -ExecutionPolicy Bypass -File build-apk.ps1
#  退出码：0 = 成功；非 0 = 失败
# ============================================================
$ErrorActionPreference = 'Stop'
$WORK_DIR   = 'C:\PriceLens-Android-build'
$OUTPUT_DIR = 'C:\Users\Administrator\Desktop'
$REPO       = 'https://github.com/wuliao00/PriceLens.git'

# ----- 1. 环境与签名前置检查 -----
function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { return (Get-Item $env:JAVA_HOME) }
    Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk-(17|21)' } | Sort-Object Name -Descending | Select-Object -First 1
}
function Write-Step($msg) { Write-Host "`n===> $msg" -ForegroundColor Cyan }

Write-Step '检查 JDK'
$jdk = Find-Jdk
if (-not $jdk) { Write-Error '未找到 JDK（需 17/21）'; exit 1 }
$env:JAVA_HOME = $jdk.FullName
Write-Host 'JAVA_HOME = ' $env:JAVA_HOME

Write-Step '检查签名配置（缺任何一项都拒绝出包——绝不用 debug 签名冒充正式包）'
if (-not (Test-Path 'local.properties')) { Write-Error '仓库根缺 local.properties；按 docs/DEVELOPMENT.md 配置 PRICLENS_STORE_FILE 四项'; exit 1 }
$props = @{}
Get-Content 'local.properties' | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $props[$Matches[1]] = $Matches[2] } }
foreach ($k in 'PRICLENS_STORE_FILE','PRICLENS_STORE_PASSWORD','PRICLENS_KEY_ALIAS','PRICLENS_KEY_PASSWORD') {
    if (-not $props[$k]) { Write-Error "local.properties 缺 $k"; exit 1 }
}
if (-not (Test-Path $props['PRICLENS_STORE_FILE'])) { Write-Error "keystore 不存在: $($props['PRICLENS_STORE_FILE'])"; exit 1 }

Write-Step '读版本号（唯一真源 app/build.gradle.kts，本脚本不改源码）'
$gk = Get-Content (Join-Path $PSScriptRoot 'app\build.gradle.kts') -Raw
$NEW_VERSION_NAME = [regex]::Match($gk, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$NEW_VERSION_CODE = [regex]::Match($gk, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $NEW_VERSION_NAME -or -not $NEW_VERSION_CODE) { Write-Error '版本号解析失败——build.gradle.kts 写法变了要同步改本脚本'; exit 1 }
Write-Host "versionName=$NEW_VERSION_NAME versionCode=$NEW_VERSION_CODE"

# 直接在本仓库目录构建（路径纯英文，且保证出的是工作区最新代码；
# 先提交并推送版本号变更，再跑本脚本）。若克隆全新环境，先手动 git clone 再进目录运行。
Write-Step '在本仓库构建'

Write-Step 'assembleRelease（gradle 依 local.properties 自动正式签名；首次约 6 分钟）'
.\gradlew.bat :app:assembleRelease --console=plain 2>&1 | Tee-Object -FilePath "$PSScriptRoot\build-apk.log" | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { Write-Error '构建失败，看 build-apk.log'; exit $LASTEXITCODE }

Write-Step '分发产物到桌面'
if (-not $NEW_VERSION_NAME) { Write-Error '版本号变量丢失'; exit 1 }
$signed = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $signed)) { Write-Error "未找到已签名 APK：$signed（gradle 未配签名时产出的是 unsigned，脚本已在前置检查拦截）"; exit 1 }
$outName = "PriceLens-$NEW_VERSION_NAME.apk"
Remove-Item (Join-Path $OUTPUT_DIR $outName) -Force -ErrorAction SilentlyContinue
Copy-Item $signed (Join-Path $OUTPUT_DIR $outName) -Force
Remove-Item "$outName.idsig" -Force -ErrorAction SilentlyContinue

Write-Step '验证签名与摘要（sha256 回填 update.json / README）'
$bt = Get-ChildItem "$env:ANDROID_HOME\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Sort-Object { $_.Directory.Name } -Descending | Select-Object -First 1
if (-not $bt) { $bt = Get-ChildItem 'C:\Android\Sdk\build-tools\*\apksigner.bat' | Sort-Object { $_.Directory.Name } -Descending | Select-Object -First 1 }
if ($bt) { & $bt.FullName verify --print-certs (Join-Path $OUTPUT_DIR $outName) | Select-Object -First 4 }
Get-FileHash (Join-Path $OUTPUT_DIR $outName) -Algorithm SHA256 | Format-List Hash, Path
Get-Item (Join-Path $OUTPUT_DIR $outName) | Select-Object Name, Length, LastWriteTime | Format-List

Write-Step '完成'
Write-Host "APK：$OUTPUT_DIR\$outName" -ForegroundColor Green
