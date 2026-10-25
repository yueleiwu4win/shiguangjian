param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'verifyOfflineManifest'))
. "$PSScriptRoot\scripts\env.ps1"
Push-Location $PSScriptRoot
try {
    if (-not (Test-Path -LiteralPath 'local.properties') -and $env:ANDROID_HOME) {
        ('sdk.dir=' + $env:ANDROID_HOME.Replace('\', '/')) | Set-Content -LiteralPath 'local.properties' -Encoding utf8
    }
    $gradleLocal = Join-Path $PSScriptRoot '.tools\gradle-8.11.1\bin\gradle.bat'
    if (Test-Path -LiteralPath $gradleLocal) { & $gradleLocal @Tasks --console=plain } else { & "$PSScriptRoot\gradlew.bat" @Tasks --console=plain }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
