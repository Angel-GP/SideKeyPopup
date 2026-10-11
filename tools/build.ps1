# =============================================================================
#  SideKeyPopup - one-command build script
#
#  Reuses an existing Android toolchain when one is available, otherwise it
#  downloads JDK 17 + Gradle 8.2 + Android SDK into .toolchain\.
#
#  Lookup order for the toolchain:
#    1. -ToolchainRoot <path>
#    2. <project>\.toolchain
#    3. <parent>\FloatingImage\.toolchain      (already prepared on this machine)
#    4. download a fresh one into <project>\.toolchain
#
#  Usage:  powershell -ExecutionPolicy Bypass -File tools\build.ps1
#          powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Clean
#          powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Offline
#          powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Test
#  Output: dist\SideKeyPopup-1.0.apk
#
#  NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 files
#  without a BOM as ANSI (GBK on zh-CN systems) and non-ASCII text breaks it.
# =============================================================================

[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$Offline,
    [switch]$Test,
    [string]$ToolchainRoot = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'

$VersionName = '1.0'
$ApkName     = "SideKeyPopup-$VersionName.apk"

function Write-Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Info($m) { Write-Host "    $m" }

function Test-Toolchain([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $false }
    return (Test-Path (Join-Path $path 'sdk\platforms\android-34\android.jar'))
}

# --------------------------------------------------------------- 0. toolchain
Write-Step 'locate toolchain'

$tc = $ToolchainRoot
if (-not (Test-Toolchain $tc)) {
    $local = Join-Path $root '.toolchain'
    if (Test-Toolchain $local) {
        $tc = $local
    } else {
        $sibling = Join-Path (Split-Path -Parent $root) 'FloatingImage\.toolchain'
        if (Test-Toolchain $sibling) {
            $tc = $sibling
            Write-Info "reusing existing toolchain: $tc"
        } else {
            $tc = $local
        }
    }
}
if ([string]::IsNullOrWhiteSpace($tc)) { $tc = Join-Path $root '.toolchain' }

$dl   = Join-Path $tc 'downloads'
$sdk  = Join-Path $tc 'sdk'
$bt   = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$gradleHome = Join-Path $tc 'gradle'
$gradleUserHome = Join-Path $tc 'gradle-home'

function Get-RemoteFile {
    param([string[]]$Url, [string]$Dest, [long]$MinSize = 1048576)
    if ((Test-Path $Dest) -and ((Get-Item $Dest).Length -ge $MinSize)) {
        Write-Info "cached: $(Split-Path -Leaf $Dest)"
        return
    }
    $tmp = "$Dest.part"
    foreach ($u in $Url) {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        Write-Info "downloading: $u"
        & curl.exe -L --fail --retry 2 --retry-delay 3 --connect-timeout 20 --max-time 1800 `
            --silent --show-error -o $tmp $u
        if ($LASTEXITCODE -eq 0 -and (Test-Path $tmp) -and ((Get-Item $tmp).Length -ge $MinSize)) {
            Move-Item -Force $tmp $Dest
            return
        }
        Write-Info "  failed, trying next mirror"
    }
    throw "download failed: $($Url -join ' | ')"
}

function Expand-Zip {
    param([string]$Zip, [string]$Dest)
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $Zip -C $Dest
        if ($LASTEXITCODE -ne 0) { throw "unzip failed: $Zip" }
    } else {
        Expand-Archive -Path $Zip -DestinationPath $Dest -Force
    }
}

New-Item -ItemType Directory -Force -Path $tc, $dl, $dist, $gradleUserHome | Out-Null

# --------------------------------------------------------------- 1. JDK 17
Write-Step 'prepare JDK 17'
$jdkDir = Join-Path $tc 'jdk'
$javac = Get-ChildItem -Path $jdkDir -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javac) {
    $jdkZip = Join-Path $dl 'openjdk-17-windows-x64.zip'
    Get-RemoteFile -Dest $jdkZip -MinSize 100MB -Url @(
        'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip'
    )
    Write-Info 'unzipping JDK ...'
    Expand-Zip -Zip $jdkZip -Dest $jdkDir
    $javac = Get-ChildItem -Path $jdkDir -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $javac) { throw 'JDK setup failed (javac.exe not found)' }
$jdkHome = $javac.Directory.Parent.FullName
$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$env:PATH"
Write-Info "JAVA_HOME = $jdkHome"

# --------------------------------------------------------------- 2. Gradle
Write-Step 'prepare Gradle'
$gradleExe = Get-ChildItem -Path $gradleHome -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $gradleExe) {
    $gradleZip = Join-Path $dl 'gradle-8.2-bin.zip'
    Get-RemoteFile -Dest $gradleZip -MinSize 50MB -Url @(
        'https://mirrors.huaweicloud.com/gradle/gradle-8.2-bin.zip',
        'https://mirrors.cloud.tencent.com/gradle/gradle-8.2-bin.zip',
        'https://services.gradle.org/distributions/gradle-8.2-bin.zip'
    )
    Write-Info 'unzipping Gradle ...'
    Expand-Zip -Zip $gradleZip -Dest $gradleHome
    $gradleExe = Get-ChildItem -Path $gradleHome -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $gradleExe) { throw 'Gradle setup failed (gradle.bat not found)' }
Write-Info "gradle = $($gradleExe.FullName)"

# --------------------------------------------------------------- 3. Android SDK
Write-Step 'prepare Android SDK'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = $gradleUserHome

$sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
    $ctZip = Join-Path $dl 'commandlinetools-win.zip'
    Get-RemoteFile -Dest $ctZip -MinSize 50MB -Url @(
        'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    )
    Write-Info 'unzipping cmdline-tools ...'
    $tmpCt = Join-Path $tc 'cmdtools-tmp'
    Remove-Item $tmpCt -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Zip -Zip $ctZip -Dest $tmpCt
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    $target = Join-Path $sdk 'cmdline-tools\latest'
    Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -Force (Join-Path $tmpCt 'cmdline-tools') $target
    Remove-Item $tmpCt -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $androidJar) -or -not (Test-Path (Join-Path $bt 'aapt2.exe'))) {
    Write-Info 'accepting SDK licenses ...'
    $yes = ((1..300) | ForEach-Object { 'y' }) -join "`r`n"
    $yes | & $sdkManager --sdk_root="$sdk" --licenses *> $null
    Write-Info 'installing platforms;android-34 and build-tools;34.0.0 ...'
    & $sdkManager --sdk_root="$sdk" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools' | ForEach-Object { Write-Info $_ }
    if ($LASTEXITCODE -ne 0) { throw 'sdkmanager install failed' }
}
foreach ($need in @($androidJar, (Join-Path $bt 'aapt2.exe'), (Join-Path $bt 'zipalign.exe'),
                    (Join-Path $bt 'apksigner.bat'))) {
    if (-not (Test-Path $need)) { throw "missing SDK component: $need" }
}
Write-Info "SDK = $sdk"

# --------------------------------------------------------------- 4. keystore
Write-Step 'prepare debug keystore'
$keystore = Join-Path $root 'keystore\debug.keystore'
if (-not (Test-Path $keystore)) {
    Write-Info 'generating debug keystore ...'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $keystore) | Out-Null
    & (Join-Path $jdkHome 'bin\keytool.exe') -genkeypair -v `
        -keystore $keystore `
        -storepass android -keypass android `
        -alias androiddebugkey `
        -dname 'CN=Android Debug,O=Android,C=US' `
        -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
Write-Info "keystore = $keystore"

# --------------------------------------------------------------- 5. gradle build
if ($Clean) {
    Write-Step 'clean'
    Remove-Item (Join-Path $root 'app\build') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $root 'build') -Recurse -Force -ErrorAction SilentlyContinue
}

if ($Test) {
    Write-Step 'gradle testDebugUnitTest'
    $gradleArgs = @('--no-daemon', '--console=plain', 'testDebugUnitTest')
} else {
    Write-Step 'gradle assembleDebug'
    $gradleArgs = @('--no-daemon', '--console=plain', 'assembleDebug')
}
if ($Offline) { $gradleArgs += '--offline' }
Push-Location $root
try {
    & $gradleExe.FullName @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
} finally {
    Pop-Location
}

if ($Test) {
    Write-Host "`nTESTS OK" -ForegroundColor Green
    exit 0
}

$built = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $built)) { throw "apk not found: $built" }

$outApk = Join-Path $dist $ApkName
Copy-Item -Force $built $outApk

# --------------------------------------------------------------- 6. verify
Write-Step 'verify apk'
& (Join-Path $bt 'apksigner.bat') verify --verbose $outApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

Write-Step 'apk info'
& (Join-Path $bt 'aapt2.exe') dump badging $outApk |
    Select-String -Pattern "^package|^application-label|^sdkVersion|^targetSdkVersion|^launchable-activity|^uses-permission" |
    ForEach-Object { Write-Info $_.Line }

Write-Step 'accessibility service declaration'
& (Join-Path $bt 'aapt2.exe') dump xmltree --file AndroidManifest.xml $outApk |
    Select-String -Pattern "SideKeyAccessibilityService|BIND_ACCESSIBILITY_SERVICE|ShizukuProvider|canRequestFilterKeyEvents" |
    ForEach-Object { Write-Info $_.Line.Trim() }

Write-Step 'accessibility config resource'
& (Join-Path $jdkHome 'bin\jar.exe') tf $outApk |
    Select-String -Pattern "res/xml/accessibility_service_config" |
    ForEach-Object { Write-Info $_.Line }

Write-Host "`nBUILD OK: $outApk" -ForegroundColor Green
Write-Host ("size: {0:N1} MB" -f ((Get-Item $outApk).Length / 1MB)) -ForegroundColor Green
