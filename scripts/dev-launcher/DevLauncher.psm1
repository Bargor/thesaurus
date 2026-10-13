Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function ConvertTo-DevProcessArgument {
    param([AllowEmptyString()][string]$Value)
    # CommandLineToArgvW quoting, including backslashes before quotes/end.
    '"' + ([regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1')) + '"'
}

function Invoke-DevCommand {
    param([string]$FilePath, [string[]]$Arguments = @(), [int]$TimeoutSeconds = 120, [string]$WorkingDirectory)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $commandArguments = ($Arguments | ForEach-Object { ConvertTo-DevProcessArgument $_ }) -join ' '
    if ($FilePath -match '\.(cmd|bat)$') {
        $info.FileName = $env:ComSpec
        $info.Arguments = '/d /s /c "' + (ConvertTo-DevProcessArgument $FilePath) + ' ' + $commandArguments + '"'
    } else { $info.Arguments = $commandArguments }
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    if ($WorkingDirectory) { $info.WorkingDirectory = $WorkingDirectory }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            # Only this command is owned here. Background services are never stopped.
            Stop-DevOwnedCommand $process
            throw "Command timed out after ${TimeoutSeconds}s: $FilePath. Background services remain running."
        }
        # A child can inherit the pipes even after its parent exits. Never wait
        # without a bound for ReadToEndAsync to finish.
        if (-not [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($stdout,$stderr), 5000)) {
            throw "Output collection timed out: $FilePath. A child process may still own its output handles."
        }
        $output = $stdout.Result + $stderr.Result
        if ($process.ExitCode -ne 0) { throw "Command failed ($($process.ExitCode)): $FilePath`n$output" }
        # ADB on Windows can produce CRCRLF; normalize every CR/LF separator.
        return @($output -split '[\r\n]+' | Where-Object { $_ -ne '' })
    } finally { $process.Dispose() }
}

function Stop-DevOwnedCommand {
    param([System.Diagnostics.Process]$Process)
    # taskkill /T is supported by Windows PowerShell 5.1 as well as PowerShell 7.
    # The PID comes exclusively from the process this invocation just started.
    $killInfo = New-Object System.Diagnostics.ProcessStartInfo
    $killInfo.FileName = Join-Path $env:SystemRoot 'System32/taskkill.exe'
    $killInfo.Arguments = "/PID $($Process.Id) /T /F"
    $killInfo.UseShellExecute = $false
    $killInfo.CreateNoWindow = $true
    $killInfo.RedirectStandardOutput = $true
    $killInfo.RedirectStandardError = $true
    $killer = [System.Diagnostics.Process]::Start($killInfo)
    try {
        $null = $killer.StandardOutput.ReadToEndAsync()
        $null = $killer.StandardError.ReadToEndAsync()
        if (-not $killer.WaitForExit(5000)) { $killer.Kill() }
        if (-not $Process.HasExited) { $Process.Kill() }
    } finally { $killer.Dispose() }
}

function Invoke-DevWait {
    param([string]$Description, [scriptblock]$Probe, [int]$TimeoutSeconds,
        [scriptblock]$Pause = { Start-Sleep -Seconds 2 }, [scriptblock]$Now = { [DateTime]::UtcNow })
    $deadline = (& $Now).AddSeconds($TimeoutSeconds)
    do {
        if (& $Probe) { return }
        if ((& $Now) -ge $deadline) { throw "Timed out waiting for $Description after ${TimeoutSeconds}s. Services remain running; inspect build/dev-launcher logs." }
        & $Pause
    } while ($true)
}

function Select-DevAvd {
    param([string[]]$AvdNames, [string]$RequestedName)
    $names = @($AvdNames | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() } | Select-Object -Unique)
    if ($RequestedName) {
        if ($names -notcontains $RequestedName) { throw "AVD '$RequestedName' does not exist. Available: $($names -join ', ')." }
        return $RequestedName
    }
    if ($names.Count -ne 1) { throw "Specify -AvdName: expected one installed AVD, found $($names.Count) ($($names -join ', '))." }
    return $names[0]
}

function Select-DevEmulator {
    param([string[]]$Devices, [string]$AvdName, [scriptblock]$ResolveAvdName)
    $matchedSerials = @()
    foreach ($line in $Devices) {
        if ($line -match '^(emulator-\d+)\s+device(?:\s|$)') {
            $serial = $Matches[1]
            if (([string](& $ResolveAvdName $serial)).Trim() -eq $AvdName) { $matchedSerials += $serial }
        }
    }
    if ($matchedSerials.Count -gt 1) { throw "More than one running emulator uses AVD '$AvdName'. Close the extra instance." }
    if ($matchedSerials.Count -eq 1) { return $matchedSerials[0] }
    return $null
}

function Assert-DevFirebaseConfig {
    param($Config)
    foreach ($pair in @(@('auth',9099), @('firestore',8080))) {
        $entry = $Config.emulators.($pair[0])
        if ($entry.port -ne $pair[1]) { throw "firebase.json must use $($pair[0]) port $($pair[1]) for DEV." }
        if ($entry.PSObject.Properties['host'] -and $entry.host -notin @('localhost','127.0.0.1','::1')) { throw 'Firebase emulator hosts must be loopback.' }
    }
    if ($Config.emulators.PSObject.Properties['hub']) {
        $hub = $Config.emulators.hub
        if (($hub.PSObject.Properties['port'] -and $hub.port -ne 4400) -or ($hub.PSObject.Properties['host'] -and $hub.host -notin @('localhost','127.0.0.1','::1'))) { throw 'Firebase hub must use loopback port 4400.' }
    }
}

function Test-DevFirebaseIdentity {
    param($Locator, $Emulators, [object[]]$Processes, [hashtable]$PortOwners)
    if (-not $Locator -or -not $Emulators -or -not $Locator.version) { return $false }
    if (@($Locator.origins).Count -eq 0) { return $false }
    foreach ($origin in $Locator.origins) {
        if ($origin -notmatch '^http://(127\.0\.0\.1|localhost|\[::1\]):4400$') { return $false }
    }
    foreach ($name in @('auth','firestore')) {
        $entry = $Emulators.PSObject.Properties[$name]
        if (-not $entry) { return $false }
        $expected = if ($name -eq 'auth') { 9099 } else { 8080 }
        if ($entry.Value.port -ne $expected -or $entry.Value.host -notin @('127.0.0.1','localhost','::1')) { return $false }
    }
    $owners = @($Processes | Where-Object {
        $_.CommandLine -match 'firebase-tools[\\/].*firebase\.js' -and
        $_.CommandLine -match 'emulators:start' -and
        $_.CommandLine -match '(?:^|\s)["'']?--project["'']?(?:\s+|=)["'']?demo-thesaurus["'']?(?:\s|$)'
    })
    foreach ($owner in $owners) {
        $valid = $true
        foreach ($service in @('hub','auth','firestore')) {
            $current = $PortOwners[$service]
            $seen = @{}
            while ($current -and $current -ne $owner.ProcessId -and -not $seen.ContainsKey([string]$current)) {
                $seen[[string]$current] = $true
                $parent = @($Processes | Where-Object { $_.ProcessId -eq $current })
                $current = if ($parent.Count -eq 1) { $parent[0].ParentProcessId } else { 0 }
            }
            if ($current -ne $owner.ProcessId) { $valid = $false; break }
        }
        if ($valid) { return $true }
    }
    return $false
}

function Get-DevPortOwners {
    $result = @{}
    foreach ($pair in @(@('hub',4400), @('auth',9099), @('firestore',8080), @('ui',4000), @('logging',4500))) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $pair[1] -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
        if ($listeners.Count -gt 1) { throw "Multiple processes listen on port $($pair[1]); cannot safely reuse services." }
        if ($listeners.Count -eq 1) { $result[$pair[0]] = [int]$listeners[0] }
    }
    return $result
}

function Get-DevHttpJson {
    param([string]$Uri)
    Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec 5 -UseBasicParsing
}

function Test-DevFirebaseReady {
    param([string]$LocatorPath)
    try {
        if (-not (Test-Path -LiteralPath $LocatorPath)) { return $false }
        $locator = Get-Content -LiteralPath $LocatorPath -Raw | ConvertFrom-Json
        $hub = Get-DevHttpJson 'http://127.0.0.1:4400/'
        if ($hub.version -ne $locator.version -or (@($hub.origins) -join ',') -ne (@($locator.origins) -join ',')) { return $false }
        $emulators = Get-DevHttpJson 'http://127.0.0.1:4400/emulators'
        if (-not (Test-DevFirebaseIdentity -Locator $locator -Emulators $emulators -Processes @(Get-CimInstance Win32_Process) -PortOwners (Get-DevPortOwners))) { return $false }
        $null = Get-DevHttpJson 'http://127.0.0.1:9099/emulator/v1/projects/demo-thesaurus/config'
        $null = Get-DevHttpJson 'http://127.0.0.1:8080/'
        return $true
    } catch { return $false }
}

function Start-DevBackground {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogPrefix, [switch]$Visible)
    $parameters = @{
        FilePath = $FilePath
        ArgumentList = (($Arguments | ForEach-Object { ConvertTo-DevProcessArgument $_ }) -join ' ')
        WorkingDirectory = $WorkingDirectory
        RedirectStandardOutput = "$LogPrefix.stdout.log"
        RedirectStandardError = "$LogPrefix.stderr.log"
        PassThru = $true
        WindowStyle = if ($Visible) { 'Normal' } else { 'Hidden' }
    }
    $process = Start-Process @parameters
    return $process.Id
}

function Assert-DevOwnedServiceRunning {
    param([hashtable]$OwnedProcesses, [string]$LogDirectory)
    foreach ($service in $OwnedProcesses.Keys) {
        if (-not (Get-Process -Id $OwnedProcesses[$service] -ErrorAction SilentlyContinue)) { throw "Newly started $service process exited before readiness. Inspect logs in $LogDirectory. Existing services were not stopped." }
    }
}

function Get-DevPrerequisites {
    param([string]$RepoRoot, [string]$SdkPath)
    $localSdk = $null
    if (Test-Path (Join-Path $RepoRoot 'local.properties')) {
        $line = Get-Content (Join-Path $RepoRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $localSdk = ($line.Substring(8) -replace '\\:', ':' -replace '\\\\', '\') }
    }
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
    if (-not $SdkPath) { $SdkPath = $localSdk }
    if (-not $SdkPath -and $env:LOCALAPPDATA) {
        $standardSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
        if (Test-Path -LiteralPath $standardSdk) { $SdkPath = $standardSdk }
    }
    if (-not $SdkPath) { throw 'Set ANDROID_HOME or supply -SdkPath pointing to an installed Android SDK.' }
    $sdk = [IO.Path]::GetFullPath($SdkPath)
    if ($localSdk -and [IO.Path]::GetFullPath($localSdk).TrimEnd('\','/') -ne $sdk.TrimEnd('\','/')) { throw 'Selected SDK conflicts with sdk.dir in local.properties. Point both to the same installed SDK before launching.' }
    foreach ($relative in @('platform-tools/adb.exe','emulator/emulator.exe','build-tools/37.0.0/aapt.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sdk $relative))) { throw "Missing Android SDK prerequisite: $relative in $sdk. Install it before launching." }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-37/android.jar')) -and -not (Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-37.0/android.jar'))) { throw "Android SDK platform 37 is missing in $sdk." }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
    if (((Invoke-DevCommand $java @('-version')) -join "`n") -notmatch 'version "21(?:\.|"|-)' ) { throw 'The launcher requires JDK 21 (JAVA_HOME or PATH).' }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $nodeVersion = ((Invoke-DevCommand $node @('--version')) -join '').Trim()
    if ($nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) { throw 'The launcher requires Node.js 22 or later on PATH.' }
    $firebasePackage = Join-Path $RepoRoot 'node_modules/firebase-tools/package.json'
    if (-not (Test-Path $firebasePackage)) { throw 'Local Firebase CLI missing. Run npm ci in the repository first.' }
    $firebaseVersion = (Get-Content $firebasePackage -Raw | ConvertFrom-Json).version
    if ([version]$firebaseVersion -lt [version]'14.17.0') { throw 'Local firebase-tools is too old. Install the repository dependencies before launching.' }
    $config = Get-Content (Join-Path $RepoRoot 'firebase.json') -Raw | ConvertFrom-Json
    Assert-DevFirebaseConfig $config
    $google = Get-Content (Join-Path $RepoRoot 'app/src/devDebug/google-services.json') -Raw | ConvertFrom-Json
    if ($google.project_info.project_id -ne 'demo-thesaurus' -or $google.client[0].client_info.android_client_info.package_name -ne 'pl.bargor.thesaurus.dev' -or $google.client[0].api_key[0].current_key -ne 'fake-api-key-for-emulator-only') { throw 'DEV Firebase configuration must use demo-thesaurus and the fake emulator API key.' }
    $locatorPath = ((Invoke-DevCommand $node @('-p', 'require("path").join(require("os").tmpdir(),"hub-demo-thesaurus.json")')) -join '').Trim()
    return @{ Sdk = $sdk; Adb = (Join-Path $sdk 'platform-tools/adb.exe'); Emulator = (Join-Path $sdk 'emulator/emulator.exe'); Node = $node; Java = $java; LocatorPath = $locatorPath; Firebase = (Join-Path $RepoRoot 'node_modules/firebase-tools/lib/bin/firebase.js'); Aapt = (Join-Path $sdk 'build-tools/37.0.0/aapt.exe') }
}

function Invoke-DevLauncher {
    param([string]$RepoRoot, [string]$AvdName, [string]$SdkPath, [string]$FirebaseImportPath, [switch]$SoftwareRendering, [int]$TimeoutSeconds = 300)
    $prerequisites = Get-DevPrerequisites $RepoRoot $SdkPath
    if ($FirebaseImportPath) {
        $FirebaseImportPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($FirebaseImportPath)
        if (-not (Test-Path -LiteralPath (Join-Path $FirebaseImportPath 'firebase-export-metadata.json'))) { throw 'FirebaseImportPath must be a Firebase export directory containing firebase-export-metadata.json.' }
    }
    $adb = $prerequisites.Adb
    $avd = Select-DevAvd -AvdNames @(Invoke-DevCommand $prerequisites.Emulator @('-list-avds')) -RequestedName $AvdName
    $resolve = { param($serial) ((Invoke-DevCommand $adb @('-s',$serial,'emu','avd','name') 15) | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -ne 'OK' } | Select-Object -First 1) }.GetNewClosure()
    $devices = @(Invoke-DevCommand $adb @('devices'))
    $serial = Select-DevEmulator -Devices $devices -AvdName $avd -ResolveAvdName $resolve
    $firebaseReady = Test-DevFirebaseReady $prerequisites.LocatorPath
    if (-not $firebaseReady -and (Get-DevPortOwners).Count -gt 0) { throw 'Firebase ports are occupied by services that cannot be verified as demo-thesaurus. Resolve the conflict or wait for the existing suite to finish starting, then rerun. No service was stopped.' }
    $logDirectory = Join-Path $RepoRoot 'build/dev-launcher'
    $null = New-Item -ItemType Directory -Path $logDirectory -Force
    $sessionId = [Guid]::NewGuid().ToString('N')
    $owned = @{}
    $session = @{ id = $sessionId; startedAt = [DateTime]::UtcNow.ToString('o'); avd = $avd; serial = $serial; ownedProcesses = $owned; logs = $logDirectory }
    $saveSession = { $session | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $logDirectory "$sessionId.json") -Encoding UTF8 }.GetNewClosure()
    Write-Host "Services remain running after the launcher exits (including on failure). Session/logs: $logDirectory/$sessionId.*"
    Write-Host 'Before manually stopping Firebase, export data to a NEW directory with the local Firebase CLI: emulators:export <new-directory> --project demo-thesaurus. Reused services are owned by their original session.'
    if (-not $serial) {
        $devices = @(Invoke-DevCommand $adb @('devices'))
        if (@($devices | Where-Object { $_ -match '^emulator-\d+\s+(offline|unauthorized)' }).Count -gt 0) { throw 'An emulator is offline or unauthorized. Wait for it or resolve its state before launching another AVD.' }
        $port = 5554
        while ($port -le 5682) {
            $candidate = "emulator-$port"
            $occupied = @($devices | Where-Object { $_ -match "^$candidate\s" }).Count -gt 0
            $listeners = @(Get-NetTCPConnection -LocalPort @($port,($port+1)) -State Listen -ErrorAction SilentlyContinue)
            if (-not $occupied -and $listeners.Count -eq 0) { break }
            $port += 2
        }
        if ($port -gt 5682) { throw 'No free emulator console/ADB port pair found.' }
        $serial = "emulator-$port"
        # Cold boot avoids restoring a stale graphics/runtime snapshot. The AVD
        # disk and installed app data are preserved; this does not wipe userdata.
        $emulatorArguments = @('-avd',$avd,'-port',"$port",'-no-snapshot','-no-boot-anim')
        if ($SoftwareRendering) { $emulatorArguments += @('-gpu','swiftshader') }
        $owned.emulator = Start-DevBackground -FilePath $prerequisites.Emulator -Arguments $emulatorArguments -WorkingDirectory $RepoRoot -LogPrefix (Join-Path $logDirectory "$sessionId.emulator") -Visible
        $session.serial = $serial
        & $saveSession
    } else { Write-Host "Reusing $avd ($serial); installed app and emulator data are preserved." }
    if (-not $firebaseReady) {
        $ports = Get-DevPortOwners
        if ($ports.Count -gt 0) { throw 'Firebase ports are occupied by services that cannot be verified as demo-thesaurus. Resolve the conflict or wait for the existing suite to finish starting, then rerun. No service was stopped.' }
        $firebaseArguments = @($prerequisites.Firebase,'emulators:start','--project','demo-thesaurus','--only','auth,firestore','--config',(Join-Path $RepoRoot 'firebase.json'))
        if ($FirebaseImportPath) { $firebaseArguments += @('--import',$FirebaseImportPath) }
        else { Write-Host 'Starting Firebase with empty, in-memory data. Export it before stopping; use -FirebaseImportPath on the next start.' }
        $owned.firebase = Start-DevBackground -FilePath $prerequisites.Node -Arguments $firebaseArguments -WorkingDirectory $RepoRoot -LogPrefix (Join-Path $logDirectory "$sessionId.firebase")
    } else { Write-Host 'Reusing verified Firebase Auth/Firestore for demo-thesaurus.' }
    # Ownership is per invocation, never inferred from a stale PID file.
    & $saveSession
    Write-Host 'Waiting for Firebase Auth/Firestore readiness and project identity...'
    Invoke-DevWait -Description 'Firebase Auth/Firestore readiness and project identity' -TimeoutSeconds $TimeoutSeconds -Probe {
        Assert-DevOwnedServiceRunning -OwnedProcesses $owned -LogDirectory $logDirectory
        Test-DevFirebaseReady $prerequisites.LocatorPath
    }.GetNewClosure()
    Write-Host "Waiting for $avd boot and package manager ($serial)..."
    Invoke-DevWait -Description "$avd boot and package manager ($serial)" -TimeoutSeconds $TimeoutSeconds -Probe {
        Assert-DevOwnedServiceRunning -OwnedProcesses $owned -LogDirectory $logDirectory
        try {
            $boot = ((Invoke-DevCommand $adb @('-s',$serial,'shell','getprop','sys.boot_completed') 10) -join '').Trim()
            $actualAvd = & $resolve $serial
            $packages = @(Invoke-DevCommand $adb @('-s',$serial,'shell','pm','path','android') 10)
            return ($boot -eq '1' -and $actualAvd -eq $avd -and ($packages -join '') -match '^package:')
        } catch { return $false }
    }.GetNewClosure()
    $api = ((Invoke-DevCommand $adb @('-s',$serial,'shell','getprop','ro.build.version.sdk')) -join '').Trim()
    if ([int]$api -lt 31) { throw "DEV requires Android API 31 or later; $serial is API $api." }
    # Make Gradle use the same SDK and JDK that passed preflight, restoring the shell afterwards.
    $previousSdk = $env:ANDROID_HOME
    $previousSdkRoot = $env:ANDROID_SDK_ROOT
    $previousJava = $env:JAVA_HOME
    try {
        $env:ANDROID_HOME = $prerequisites.Sdk
        $env:ANDROID_SDK_ROOT = $prerequisites.Sdk
        $env:JAVA_HOME = Split-Path (Split-Path $prerequisites.Java -Parent) -Parent
        Write-Host 'Building :app:assembleDevDebug...'
        Invoke-DevCommand -FilePath (Join-Path $RepoRoot 'gradlew.bat') -Arguments @(':app:assembleDevDebug','--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m') -TimeoutSeconds 1800 -WorkingDirectory $RepoRoot | ForEach-Object { Write-Host $_ }
    } finally { $env:ANDROID_HOME = $previousSdk; $env:ANDROID_SDK_ROOT = $previousSdkRoot; $env:JAVA_HOME = $previousJava }
    $apk = Join-Path $RepoRoot 'app/build/outputs/apk/devDebug/app-devDebug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw "DEV APK missing: $apk" }
    $badging = (Invoke-DevCommand $prerequisites.Aapt @('dump','badging',$apk)) -join "`n"
    if ($badging -notmatch "(?m)^package: name='pl\.bargor\.thesaurus\.dev'" -or $badging -notmatch "(?m)^sdkVersion:'31'") { throw 'Built APK must be pl.bargor.thesaurus.dev with minSdk 31.' }
    if (-not (Test-DevFirebaseReady $prerequisites.LocatorPath)) { throw 'Firebase stopped or identity changed during the build. Rerun when services are ready.' }
    if ((& $resolve $serial) -ne $avd) { throw 'Target emulator changed during build; refusing to install.' }
    $installation = (Invoke-DevCommand $adb @('-s',$serial,'install','-r',$apk) 180) -join "`n"
    if ($installation -notmatch '(?m)^Success\s*$' -or $installation -match 'Failure\[') { throw "DEV APK installation failed:`n$installation" }
    Write-Host $installation
    $component = 'pl.bargor.thesaurus.dev/pl.bargor.thesaurus.MainActivity'
    $launch = (Invoke-DevCommand $adb @('-s',$serial,'shell','am','start','-W','-n',$component) 60) -join "`n"
    if ($launch -notmatch '(?im)^Status:\s*ok\s*$' -or $launch -match '(?im)^Error:|Exception|Status:\s*(?!ok\b)\S+') { throw "DEV activity launch failed:`n$launch" }
    Invoke-DevWait -Description 'DEV app process' -TimeoutSeconds 30 -Probe { try { ((Invoke-DevCommand $adb @('-s',$serial,'shell','pidof','pl.bargor.thesaurus.dev') 10) -join '').Trim() -match '^\d+(?:\s+\d+)*$' } catch { $false } }.GetNewClosure()
    Write-Host "DEV is running on $avd ($serial), connected to demo-thesaurus Auth :9099 / Firestore :8080."
}

Export-ModuleMember -Function *-Dev*
