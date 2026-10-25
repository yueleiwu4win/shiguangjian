$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localJava = Join-Path $projectRoot '.tools\java'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $localJava)) {
    $jdk = Get-ChildItem -LiteralPath $localJava -Directory | Select-Object -First 1
    $env:JAVA_HOME = $jdk.FullName
}
$localSdk = Join-Path $projectRoot '.tools\android-sdk'
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath $localSdk)) { $env:ANDROID_HOME = $localSdk }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
