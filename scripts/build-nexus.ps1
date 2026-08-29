$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Tools = Join-Path $Root '.tools'
$SdkRoot = Join-Path $Tools 'android-sdk'
$JavaExe = Get-ChildItem -LiteralPath (Join-Path $Tools 'jdk-17') -Filter java.exe -Recurse -File |
    Where-Object { $_.FullName -match '[\\/]bin[\\/]java\.exe$' } |
    Select-Object -First 1
if ($null -eq $JavaExe) {
    throw 'Portable JDK 17 was not found. Run bootstrap-android.sh first.'
}

$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $JavaExe.FullName)
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"

$SdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $SdkManager)) {
    throw "sdkmanager.bat was not found: $SdkManager"
}

Write-Output '[bootstrap] Accepting Android SDK licenses...'
1..200 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$SdkRoot" --licenses | Out-Host
if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses failed with exit code $LASTEXITCODE" }

Write-Output '[bootstrap] Installing Android SDK packages...'
& $SdkManager "--sdk_root=$SdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager package install failed with exit code $LASTEXITCODE" }

Write-Output '[bootstrap] Running Nexus unit tests and debug build...'
& (Join-Path $Root 'nexus\gradlew.bat') --no-daemon testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

Write-Output '[bootstrap] Build completed.'
Get-ChildItem -LiteralPath (Join-Path $Root 'nexus\app\build\outputs\apk\debug') -Filter *.apk -File |
    Select-Object -ExpandProperty FullName
