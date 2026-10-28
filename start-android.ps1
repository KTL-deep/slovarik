param(
    [string]$Avd = 'Slovarik_API_35',
    [ValidateSet('software', 'auto', 'host')]
    [string]$Gpu = 'software',
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path $adb) -or -not (Test-Path $emulator)) {
    throw "Android SDK/emulator not found under $sdk. Set ANDROID_HOME."
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Java') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK not found. Set JAVA_HOME to JDK 21.' }
    $env:JAVA_HOME = $jdk.FullName
}
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& $adb start-server
if ($LASTEXITCODE -ne 0) { throw 'Could not start adb.' }
$serial = $null
foreach ($line in (& $adb devices)) {
    if ($line -match '^(emulator-\d+)\s+device$') {
        $candidate = $Matches[1]
        $name = & $adb -s $candidate emu avd name 2>$null
        if ($name -contains $Avd) { $serial = $candidate; break }
    }
}
if (-not $serial) {
    $available = & $emulator -list-avds
    if ($available -notcontains $Avd) { throw "AVD '$Avd' not found. Available: $available" }
    # A dedicated port avoids accidentally installing on a connected phone.
    $serial = 'emulator-5580'
    if ((& $adb devices) -match '^emulator-5580\s') { throw 'Emulator port 5580 is occupied by another AVD.' }
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $Avd, '-port', '5580', '-gpu', $Gpu, '-no-boot-anim', '-no-snapshot-load')
}
Write-Host "Waiting for $Avd ($serial) to boot..."
$deadline = (Get-Date).AddMinutes(5)
do {
    # Windows PowerShell can turn adb's expected "device not found" stderr
    # during startup into a terminating error when ErrorActionPreference=Stop.
    $booted = try { & $adb -s $serial shell getprop sys.boot_completed 2>$null } catch { '' }
    if ("$booted".Trim() -eq '1') { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)
if ("$booted".Trim() -ne '1') { throw 'Emulator boot timed out. Check its window and virtualization support.' }
if (-not $NoBuild) {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
}
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'Debug APK missing. Run without -NoBuild first.' }
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
# Launch the installed APK using its package metadata rather than a hard-coded ID.
$aapt = Get-ChildItem "$sdk\build-tools\*\aapt.exe" | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt) { throw 'aapt.exe missing from Android build-tools.' }
$badging = & $aapt.FullName dump badging $apk
$packageLine = $badging | Where-Object { $_ -match "^package: name='([^']+)'" } | Select-Object -First 1
if ($packageLine -notmatch "^package: name='([^']+)'") { throw 'Could not determine APK package.' }
$package = $Matches[1]
& $adb -s $serial shell monkey -p $package -c android.intent.category.LAUNCHER 1
if ($LASTEXITCODE -ne 0) { throw 'Could not launch application.' }
Write-Host 'Slovarik is running. Close the emulator window when finished.'
