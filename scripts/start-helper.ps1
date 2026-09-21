param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
$ErrorActionPreference = 'Stop'
$Adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$Jar = if ($env:HELPER_JAR) { $env:HELPER_JAR } else { Join-Path $PSScriptRoot 'helper.jar' }
$DeviceScript = Join-Path $PSScriptRoot 'start-device.sh'
if (!(Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Set HELPER_JAR to the built helper.jar, or use the release bundle.' }
if (!(Test-Path -LiteralPath $DeviceScript -PathType Leaf)) { throw 'Missing start-device.sh.' }
$Token = [Guid]::NewGuid().ToString('N')
$RemoteJar = "/data/local/tmp/bigfatfish-upload-$Token.jar"
$RemoteScript = "/data/local/tmp/bigfatfish-upload-$Token.sh"
function Invoke-Adb {
    param([string[]]$Command)
    & $Adb @AdbArgs @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB failed with exit code $LASTEXITCODE" }
}
try {
    Invoke-Adb -Command @('push', $Jar, $RemoteJar)
    Invoke-Adb -Command @('push', $DeviceScript, $RemoteScript)
    Invoke-Adb -Command @('shell', 'sh', $RemoteScript, $RemoteJar)
} finally {
    & $Adb @AdbArgs shell rm -f $RemoteJar $RemoteScript 2>$null | Out-Null
}
