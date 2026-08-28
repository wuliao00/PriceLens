# ============================================================
#  PriceLens-Android 本地构建脚本
#  作者：wuliao00（由 Hermes 编排生成）
#  用途：在本机从 GitHub 拉源码 → 升级版本号 → 编译 release → 签名 → 输出 APK 到桌面
#
#  使用方法：
#    1. 双击运行（PowerShell 5.1 兼容）
#    2. 或在终端：powershell -ExecutionPolicy Bypass -File build-apk.ps1
#
#  环境要求：
#    - JDK 17 或 21（默认查找 C:\Program Files\Java\jdk-21*）
#    - Android SDK（默认查找 %LOCALAPPDATA%\Android\Sdk）
#    - 直装 Gradle（默认查找 C:\gradle-9.7.0\gradle-9.7.0\bin\gradle.bat）
#    - Debug keystore（默认查找 %USERPROFILE%\.android\debug.keystore）
#
#  退出码：
#    0 = 成功；非 0 = 失败（请查看最后 30 行日志）
# ============================================================

$ErrorActionPreference = 'Stop'

# ----- 路径配置（可按本机实际改）-----
$GH_REPO         = 'https://github.com/wuliao00/PriceLens.git'
$WORK_DIR        = 'C:\PriceLens-Android-build'   # 纯英文路径（中文路径会让 AGP 报错）
$OUTPUT_DIR      = 'C:\Users\Administrator\Desktop'
$GRADLE_BIN      = 'C:\gradle-9.7.0\gradle-9.7.0\bin\gradle.bat'
$SDK_BASE        = $env:LOCALAPPDATA + '\Android\Sdk'
$DEBUG_KEYSTORE  = $env:USERPROFILE + '\.android\debug.keystore'
$DEBUG_KEY_ALIAS = 'androiddebugkey'
$DEBUG_KEY_PASS  = 'android'

# ----- 升级版本号 -----
$NEW_VERSION_NAME = '2.4.4'
$NEW_VERSION_CODE = 12

# ============================================================
#  以下逻辑通常无需改动
# ============================================================

function Find-Jdk {
    Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk-(17|21)' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
}

function Write-Step($msg) { Write-Host "`n===> $msg" -ForegroundColor Cyan }

# 1. 找 JDK
Write-Step "查找 JDK"
$jdk = Find-Jdk
if (-not $jdk) { Write-Error "未找到 JDK（需 jdk-17 或 jdk-21）"; exit 1 }
$env:JAVA_HOME = $jdk.FullName
Write-Host "JAVA_HOME = $env:JAVA_HOME"

# 2. 设置 SDK
if (-not (Test-Path $SDK_BASE)) {
    Write-Error "Android SDK 不存在：$SDK_BASE"; exit 1
}
$env:ANDROID_HOME = $SDK_BASE
Remove-Item Env:ANDROID_SDK_ROOT -ErrorAction SilentlyContinue
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

# 3. 检查 Gradle
if (-not (Test-Path $GRADLE_BIN)) { Write-Error "Gradle 不存在：$GRADLE_BIN"; exit 1 }

# 4. 检查 debug keystore
if (-not (Test-Path $DEBUG_KEYSTORE)) { Write-Error "debug.keystore 不存在：$DEBUG_KEYSTORE"; exit 1 }

# 5. 克隆或更新代码
Write-Step "拉取最新代码到 $WORK_DIR"
if (Test-Path $WORK_DIR) {
    Set-Location $WORK_DIR
    git pull --rebase origin main
} else {
    git clone $GH_REPO $WORK_DIR
    Set-Location $WORK_DIR
}
git config user.email 'wuliao00@example.com'
git config user.name  'wuliao00'

# 6. 升级版本号
Write-Step "升级版本号到 $NEW_VERSION_NAME (code $NEW_VERSION_CODE)"
$gradlePath = Join-Path $WORK_DIR 'app\build.gradle.kts'
$content = Get-Content $gradlePath -Raw
$content = $content -replace 'versionCode\s*=\s*\d+',    "versionCode = $NEW_VERSION_CODE"
$content = $content -replace 'versionName\s*=\s*"[^"]*"', "versionName = `"$NEW_VERSION_NAME`""
[System.IO.File]::WriteAllText($gradlePath, $content, [System.Text.UTF8Encoding]::new($false))

# 7. 编译 release（输出中文路径会崩，所以工作区必须在纯英文路径）
Write-Step "Gradle assembleRelease（首次约 6 分钟，增量约 1 分钟）"
& $GRADLE_BIN assembleRelease 2>&1 | Tee-Object -FilePath "$WORK_DIR\build.log" | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle 构建失败，请查看 $WORK_DIR\build.log 的最后 50 行"
    Get-Content "$WORK_DIR\build.log" -Tail 50
    exit $LASTEXITCODE
}

# 8. 签名
Write-Step "用 debug.keystore 签名 APK"
$unsigned = "$WORK_DIR\app\build\outputs\apk\release\app-release-unsigned.apk"
if (-not (Test-Path $unsigned)) { Write-Error "未找到未签名 APK：$unsigned"; exit 1 }
$apksigner = Get-ChildItem "$SDK_BASE\build-tools\*\apksigner.bat" | Sort-Object { $_.Directory.Name } -Descending | Select-Object -First 1
if (-not $apksigner) { Write-Error "未找到 apksigner.bat"; exit 1 }
$outName   = "PriceLens-$NEW_VERSION_NAME.apk"
$outPath   = Join-Path $OUTPUT_DIR $outName
# 替换同名旧版本
Remove-Item (Join-Path $OUTPUT_DIR "PriceLens-$NEW_VERSION_NAME.apk") -Force -ErrorAction SilentlyContinue
& $apksigner.FullName sign `
    --ks $DEBUG_KEYSTORE `
    --ks-pass "pass:$DEBUG_KEY_PASS" `
    --key-pass "pass:$DEBUG_KEY_PASS" `
    --ks-key-alias $DEBUG_KEY_ALIAS `
    --out $outPath `
    $unsigned
if ($LASTEXITCODE -ne 0) { Write-Error "签名失败"; exit $LASTEXITCODE }
# 清理可能的 .idsig 副产物
Remove-Item "$outPath.idsig" -Force -ErrorAction SilentlyContinue

# 9. 验证
Write-Step "验证签名"
& $apksigner.FullName verify --print-certs $outPath | Select-Object -First 2

Write-Step "✅ 完成"
Write-Host "APK 已输出到：$outPath" -ForegroundColor Green
Get-Item $outPath | Select-Object Name, Length, LastWriteTime | Format-List
