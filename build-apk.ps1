[CmdletBinding()]
param(
    [string]$ToolchainScript = $env:LOCALLEDGER_TOOLCHAIN_SCRIPT
)

$ErrorActionPreference = 'Stop'
$ledgerProject = $PSScriptRoot
$ledgerWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$ledgerJavaName = if ($ledgerWindows) { 'java.exe' } else { 'java' }
$ledgerHasJava = [bool]$env:JAVA_HOME -or [bool](Get-Command $ledgerJavaName -ErrorAction SilentlyContinue)
$ledgerHasSdk = [bool]$env:ANDROID_HOME -or [bool]$env:ANDROID_SDK_ROOT -or
    (Test-Path -LiteralPath (Join-Path $ledgerProject 'local.properties'))

if ((!$ledgerHasJava -or !$ledgerHasSdk) -and $ToolchainScript) {
    if (!(Test-Path -LiteralPath $ToolchainScript -PathType Leaf)) {
        throw "Toolchain script does not exist: $ToolchainScript"
    }
    # Preserve tools and cache locations explicitly configured by the caller.
    $ledgerConfiguredEnvironment = @{}
    foreach ($ledgerName in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'TEMP', 'TMP', 'JAVA_TOOL_OPTIONS')) {
        $ledgerValue = [Environment]::GetEnvironmentVariable($ledgerName, 'Process')
        if ($ledgerValue) { $ledgerConfiguredEnvironment[$ledgerName] = $ledgerValue }
    }
    . $ToolchainScript
    foreach ($ledgerName in $ledgerConfiguredEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($ledgerName, $ledgerConfiguredEnvironment[$ledgerName], 'Process')
    }
}

if ($env:JAVA_HOME) {
    if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin/$ledgerJavaName") -PathType Leaf)) {
        throw 'JAVA_HOME must point to a JDK 17 or 21 installation.'
    }
} elseif (!(Get-Command $ledgerJavaName -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17 or 21 and set JAVA_HOME, or pass -ToolchainScript with your local setup script.'
}
if (!$env:ANDROID_HOME -and !$env:ANDROID_SDK_ROOT -and
    !(Test-Path -LiteralPath (Join-Path $ledgerProject 'local.properties'))) {
    throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT, or configure sdk.dir in local.properties. Install Android SDK Platform 35 and Build-tools 35.0.0.'
}

$ledgerWrapper = Join-Path $ledgerProject $(if ($ledgerWindows) { 'gradlew.bat' } else { 'gradlew' })
Push-Location -LiteralPath $ledgerProject
try {
    if ($ledgerWindows) {
        & $ledgerWrapper --no-daemon --console=plain assembleDebug
    } else {
        & sh $ledgerWrapper --no-daemon --console=plain assembleDebug
    }
    if ($LASTEXITCODE -ne 0) { throw "APK build failed with exit code $LASTEXITCODE" }
    $ledgerApk = Join-Path $ledgerProject 'app/build/outputs/apk/debug/app-debug.apk'
    $ledgerOutput = Join-Path $ledgerProject 'ZhouzhouLedger.apk'
    Copy-Item -LiteralPath $ledgerApk -Destination $ledgerOutput -Force
    Write-Output $ledgerOutput
} finally {
    Pop-Location
}
