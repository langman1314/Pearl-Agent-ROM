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
$windowsRootTrust = '-Djavax.net.ssl.trustStore=NONE -Djavax.net.ssl.trustStoreType=Windows-ROOT'
$env:GRADLE_OPTS = "$windowsRootTrust $env:GRADLE_OPTS".Trim()

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

$SourceProject = Join-Path $Root 'nexus'
$BuildRoot = if ([string]::IsNullOrWhiteSpace($env:PEARL_BUILD_ROOT)) {
    Join-Path ([System.IO.Path]::GetPathRoot($Root)) 'PearlAgentBuild'
} else {
    $env:PEARL_BUILD_ROOT
}
$BuildProject = Join-Path $BuildRoot 'nexus'
New-Item -ItemType Directory -Path $BuildProject -Force | Out-Null

Write-Output "[bootstrap] Mirroring Nexus to ASCII build path: $BuildProject"
& robocopy.exe $SourceProject $BuildProject /MIR /XD .gradle build /XF local.properties /NFL /NDL /NJH /NJS /NP | Out-Host
if ($LASTEXITCODE -gt 7) { throw "robocopy failed with exit code $LASTEXITCODE" }

Write-Output '[bootstrap] Running Nexus unit tests and debug build...'
Push-Location $BuildProject
try {
    & '.\gradlew.bat' --no-daemon testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$ArtifactDir = Join-Path $Root 'artifacts'
New-Item -ItemType Directory -Path $ArtifactDir -Force | Out-Null
$BuiltApks = Get-ChildItem -LiteralPath (Join-Path $BuildProject 'app\build\outputs\apk\debug') -Filter *.apk -File
foreach ($apk in $BuiltApks) {
    $destination = Join-Path $ArtifactDir 'nexus-1.0.1-pearl.1-debug.apk'
    Copy-Item -LiteralPath $apk.FullName -Destination $destination -Force
    Write-Output $destination
}
Write-Output '[bootstrap] Build completed.'
