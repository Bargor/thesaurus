param([ValidateSet('Helpers', 'Prerequisites', 'Processes', 'Orchestration')][string]$Suite = 'Helpers')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
Import-Module (Join-Path $PSScriptRoot 'DevLauncher.psm1') -Force
$script:Assertions = 0
function Assert-True($Value, [string]$Message) {
    $script:Assertions++
    if (-not $Value) { throw "Assertion failed: $Message" }
}
function Assert-Equal($Actual, $Expected, [string]$Message) {
    Assert-True (($Actual | ConvertTo-Json -Depth 20 -Compress) -ceq ($Expected | ConvertTo-Json -Depth 20 -Compress)) "$Message (actual: $Actual; expected: $Expected)"
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $caught = $false
    try { & $Action | Out-Null } catch { $caught = $true }
    Assert-True $caught $Message
}
function Remove-DevTestFixture([string]$Path) {
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
    $parent = [IO.Path]::GetDirectoryName($resolved).TrimEnd([char[]]@('\', '/'))
    $name = [IO.Path]::GetFileName($resolved)
    if (-not $parent.Equals($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $name -notmatch '^DEV (launcher|preflight|process) fixture [a-f0-9]{32}$' -or
        ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Refusing to remove unexpected test fixture: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
if ($Suite -eq 'Helpers') {
    Assert-Equal (Select-DevAvd -AvdNames @('Pixel') -RequestedName '') 'Pixel' 'sole AVD selected'
    Assert-Equal (Select-DevAvd -AvdNames @('Tablet', 'Pixel') -RequestedName 'Pixel') 'Pixel' 'explicit AVD selected'
    Assert-Throws { Select-DevAvd -AvdNames @() -RequestedName '' } 'no AVD rejected'
    Assert-Throws { Select-DevAvd -AvdNames @('Tablet', 'Pixel') -RequestedName '' } 'ambiguous AVD rejected'
    Assert-Throws { Select-DevAvd -AvdNames @('Pixel') -RequestedName 'Missing' } 'missing explicit AVD rejected'
    $resolve = { param($Serial) if ($Serial -eq 'emulator-5554') { 'Pixel' } else { 'Tablet' } }
    Assert-Equal (Select-DevEmulator -Devices @('physical123 device', 'emulator-5554 device') -AvdName 'Pixel' -ResolveAvdName $resolve) 'emulator-5554' 'physical device ignored'
    Assert-True (-not (Select-DevEmulator -Devices @('physical123 device') -AvdName 'Pixel' -ResolveAvdName $resolve)) 'physical device never selected'
    Assert-True (-not (Select-DevEmulator -Devices @('emulator-5554 offline') -AvdName 'Pixel' -ResolveAvdName $resolve)) 'offline emulator never reused'
    Assert-Throws { Select-DevEmulator -Devices @('emulator-5554 device', 'emulator-5556 device') -AvdName 'Pixel' -ResolveAvdName { 'Pixel' } } 'multiple matching emulators rejected'
    $config = Get-Content (Join-Path $PSScriptRoot '../../firebase.json') -Raw | ConvertFrom-Json
    Assert-DevFirebaseConfig -Config $config
    $script:Assertions++
    $config.emulators.auth.port = 9098
    Assert-Throws { Assert-DevFirebaseConfig -Config $config } 'wrong Firebase port rejected'
    Assert-Equal (ConvertTo-DevProcessArgument -Value '') '""' 'empty process argument quoted'
    Assert-Equal (ConvertTo-DevProcessArgument -Value 'simple') '"simple"' 'simple process argument quoted'
    Assert-Equal (ConvertTo-DevProcessArgument -Value 'two words') '"two words"' 'spaces quoted'
    $script:probes = 0
    Invoke-DevWait -Description 'becomes ready' -Probe { $script:probes++; $script:probes -ge 3 } -TimeoutSeconds 10 -Pause {}
    Assert-Equal $script:probes 3 'wait probes until service ready'
    $script:time = [DateTime]::UtcNow
    Assert-Throws { Invoke-DevWait -Description 'never ready' -Probe { $false } -TimeoutSeconds 1 -Pause {} -Now { $script:time = $script:time.AddSeconds(2); $script:time } } 'wait timeout stops orchestration'
    $locator = [pscustomobject]@{ version = '14.17.0'; origins = @('http://127.0.0.1:4400') }
    $emulators = [pscustomobject]@{ auth = [pscustomobject]@{ host = '127.0.0.1'; port = 9099 }; firestore = [pscustomobject]@{ host = '127.0.0.1'; port = 8080 } }
    $processes = @([pscustomobject]@{ ProcessId = 100; ParentProcessId = 1; CommandLine = 'node C:\repo\node_modules\firebase-tools\lib\bin\firebase.js emulators:start --project demo-thesaurus' }, [pscustomobject]@{ ProcessId = 101; ParentProcessId = 100; CommandLine = 'java firestore.jar' })
    $owners = @{ hub = 100; auth = 100; firestore = 101 }
    Assert-True (Test-DevFirebaseIdentity $locator $emulators $processes $owners) 'same project and process ancestry can be reused'
    $processes[0].CommandLine = '"node.exe" "C:\repo with spaces\node_modules\firebase-tools\lib\bin\firebase.js" "emulators:start" "--project" "demo-thesaurus"'
    Assert-True (Test-DevFirebaseIdentity $locator $emulators $processes $owners) 'quoted CLI project and paths can be reused'
    $processes[0].CommandLine = $processes[0].CommandLine.Replace('demo-thesaurus', 'demo-thesaurus-integration')
    Assert-True (-not (Test-DevFirebaseIdentity $locator $emulators $processes $owners)) 'different project rejected'
    $processes[0].CommandLine = $processes[0].CommandLine.Replace('demo-thesaurus-integration', 'demo-thesaurus')
    $owners.auth = 999
    Assert-True (-not (Test-DevFirebaseIdentity $locator $emulators $processes $owners)) 'foreign port process rejected'
    $owners.auth = 100
    $emulators.auth.port = 9098
    Assert-True (-not (Test-DevFirebaseIdentity $locator $emulators $processes $owners)) 'wrong reported emulator port rejected'
    $emulators.auth.port = 9099
    $locator.origins = @('http://127.0.0.1:4401')
    Assert-True (-not (Test-DevFirebaseIdentity $locator $emulators $processes $owners)) 'stale locator rejected'
    Assert-Throws { Get-DevPrerequisites -RepoRoot $PSScriptRoot -SdkPath (Join-Path $PSScriptRoot 'missing SDK') } 'missing SDK prerequisite fails before external work'
    Assert-Throws { Assert-DevOwnedServiceRunning -OwnedProcesses @{ firebase = 2147483000 } -LogDirectory $PSScriptRoot } 'exited owned service fails readiness promptly'
}
if ($Suite -eq 'Prerequisites') {
    foreach ($case in @('valid', 'platform37', 'missing-adb', 'missing-platform', 'old-java', 'old-node', 'missing-firebase', 'wrong-project', 'wrong-package', 'real-api-key')) {
        $root = Join-Path ([IO.Path]::GetTempPath()) ("DEV preflight fixture " + [Guid]::NewGuid().ToString('N'))
        $sdk = Join-Path $root 'SDK with spaces'
        $platform = if ($case -eq 'platform37') { 'platforms/android-37/android.jar' } else { 'platforms/android-37.0/android.jar' }
        foreach ($relative in @('platform-tools/adb.exe', 'emulator/emulator.exe', 'build-tools/37.0.0/aapt.exe', $platform)) {
            if (($case -eq 'missing-adb' -and $relative -eq 'platform-tools/adb.exe') -or ($case -eq 'missing-platform' -and $relative -eq $platform)) { continue }
            $path = Join-Path $sdk $relative
            $null = New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force
            Set-Content -LiteralPath $path -Value 'fixture'
        }
        $null = New-Item -ItemType Directory -Path (Join-Path $root 'node_modules/firebase-tools') -Force
        if ($case -ne 'missing-firebase') { @{ version = '14.17.0' } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'node_modules/firebase-tools/package.json') }
        Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../firebase.json') -Raw | Set-Content -LiteralPath (Join-Path $root 'firebase.json')
        $google = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../app/src/devDebug/google-services.json') -Raw | ConvertFrom-Json
        if ($case -eq 'wrong-project') { $google.project_info.project_id = 'thesaurus-cef84' }
        if ($case -eq 'wrong-package') { $google.client[0].client_info.android_client_info.package_name = 'pl.bargor.thesaurus' }
        if ($case -eq 'real-api-key') { $google.client[0].api_key[0].current_key = 'real-looking-key' }
        $null = New-Item -ItemType Directory -Path (Join-Path $root 'app/src/devDebug') -Force
        $google | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $root 'app/src/devDebug/google-services.json')
        $previousJava = $env:JAVA_HOME
        $env:JAVA_HOME = Join-Path $root 'JDK with spaces'
        try {
            $result = & (Get-Module DevLauncher) {
                param($Case, $Root, $Sdk)
                $script:preflightCase = $Case
                function script:Invoke-DevCommand {
                    param($FilePath, $Arguments)
                    if ($Arguments[0] -eq '-version') { if ($script:preflightCase -eq 'old-java') { return 'java version "17.0.1"' }; return 'openjdk version "21.0.1"' }
                    if ($Arguments[0] -eq '--version') { if ($script:preflightCase -eq 'old-node') { return 'v20.1.0' }; return 'v22.1.0' }
                    if ($Arguments[0] -eq '-p') { return 'C:\fixture temp\hub-demo-thesaurus.json' }
                    throw 'Unexpected preflight command'
                }
                try { $tools = Get-DevPrerequisites -RepoRoot $Root -SdkPath $Sdk; [pscustomobject]@{ Failure = $null; Tools = $tools } }
                catch { [pscustomobject]@{ Failure = $_.Exception.Message; Tools = $null } }
            } $case $root $sdk
            if ($case -in @('valid', 'platform37')) { Assert-True (-not $result.Failure) "$case real preflight succeeds: $($result.Failure)"; Assert-Equal $result.Tools.Sdk $sdk 'SDK path with spaces preserved' }
            else { Assert-True ([bool]$result.Failure) "$case real preflight rejected" }
        } finally {
            $env:JAVA_HOME = $previousJava
            Remove-DevTestFixture $root
            Import-Module (Join-Path $PSScriptRoot 'DevLauncher.psm1') -Force
        }
    }
}
if ($Suite -eq 'Processes') {
    $node = (Get-Command node -ErrorAction Stop).Source
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $values = @('path with spaces', 'quote"inside', 'C:\folder with space\', '', '& echo unsafe', 'plain')
    $output = Invoke-DevCommand -FilePath $node -Arguments (@('-e', 'process.stdout.write(JSON.stringify(process.argv.slice(1)))', '--') + $values) -TimeoutSeconds 10 -WorkingDirectory $repo
    Assert-Equal (($output -join "`n") | ConvertFrom-Json) $values 'real process preserves spaces, quotes, trailing slash, empty and shell metacharacters'
    $adbOutput = @(Invoke-DevCommand -FilePath $node -Arguments @('-e', 'process.stdout.write("Pixel\r\r\nOK\r\r\n")') -TimeoutSeconds 10 -WorkingDirectory $repo)
    Assert-Equal $adbOutput @('Pixel', 'OK') 'ADB Windows CRCRLF output normalizes before AVD selection'
    $batch = Join-Path $PSScriptRoot 'test-assets/command with spaces.cmd'
    $batchArguments = @('path with spaces', '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m', '--no-daemon')
    $batchOutput = @(Invoke-DevCommand -FilePath $batch -Arguments $batchArguments -TimeoutSeconds 10 -WorkingDirectory $repo)
    Assert-Equal $batchOutput @('first=[path with spaces]', 'second=[-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m]', 'third=[--no-daemon]') 'actual batch path and Gradle JVM options preserve argument grouping'
    Assert-Throws { Invoke-DevCommand -FilePath $batch -Arguments ($batchArguments + 'fail') -TimeoutSeconds 10 -WorkingDirectory $repo } 'batch wrapper propagates nonzero exit code'
    Assert-Throws { Invoke-DevCommand -FilePath $node -Arguments @('-e', 'process.stderr.write("expected failure");process.exit(7)') -TimeoutSeconds 10 -WorkingDirectory $repo } 'nonzero external command rejected'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Assert-Throws { Invoke-DevCommand -FilePath $node -Arguments @('-e', 'setTimeout(()=>{},30000)') -TimeoutSeconds 1 -WorkingDirectory $repo } 'hung external command times out'
    Assert-True ($timer.Elapsed.TotalSeconds -lt 8) 'timeout is bounded'
    $processRoot = Join-Path ([IO.Path]::GetTempPath()) ("DEV process fixture " + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $processRoot
    $prefix = Join-Path $processRoot 'child log with spaces'
    $childId = Start-DevBackground -FilePath $node -Arguments (@('-e', 'process.stdout.write(JSON.stringify(process.argv.slice(1)))', '--') + $values) -WorkingDirectory $processRoot -LogPrefix $prefix
    $timer.Restart()
    while ((Get-Process -Id $childId -ErrorAction SilentlyContinue) -and $timer.Elapsed.TotalSeconds -lt 10) { Start-Sleep -Milliseconds 50 }
    Assert-True ($timer.Elapsed.TotalSeconds -lt 10) 'background test child exits promptly'
    Assert-Equal ((Get-Content -LiteralPath "$prefix.stdout.log" -Raw) | ConvertFrom-Json) $values 'actual Start-Process preserves arguments and log paths with spaces'
    Assert-True (-not (Get-Content -LiteralPath "$prefix.stderr.log" -Raw)) 'background process has no errors'
    $childPidFile = Join-Path $processRoot 'owned descendant.pid'
    $spawnCode = 'const child=require("child_process").spawn(process.execPath,["-e","setTimeout(()=>{},30000)"],{stdio:"inherit"});require("fs").writeFileSync(process.argv[1],String(child.pid));setTimeout(()=>{},30000)'
    $timer.Restart()
    Assert-Throws { Invoke-DevCommand -FilePath $node -Arguments @('-e', $spawnCode, '--', $childPidFile) -TimeoutSeconds 1 -WorkingDirectory $processRoot } 'command with inherited-pipe descendant times out'
    Assert-True ($timer.Elapsed.TotalSeconds -lt 8) 'owned process tree timeout stays bounded'
    $descendantId = [int](Get-Content -LiteralPath $childPidFile -Raw)
    Assert-True (-not (Get-Process -Id $descendantId -ErrorAction SilentlyContinue)) 'timeout stops only the test-owned descendant tree'
    Remove-DevTestFixture $processRoot
}
if ($Suite -eq 'Orchestration') {
    foreach ($case in @('reuse', 'start', 'firebase-timeout', 'boot-timeout', 'offline', 'wrong-project', 'stale-locator', 'build-fails', 'wrong-apk', 'identity-changed', 'install-fails', 'missing-launch-status', 'service-exited')) {
        $root = Join-Path ([IO.Path]::GetTempPath()) ("DEV launcher fixture " + [Guid]::NewGuid().ToString('N'))
        $null = New-Item -ItemType Directory -Path (Join-Path $root 'app/build/outputs/apk/devDebug') -Force
        Set-Content -LiteralPath (Join-Path $root 'app/build/outputs/apk/devDebug/app-devDebug.apk') -Value 'mock APK'
        $locatorPath = Join-Path $root 'hub-demo-thesaurus.json'
        @{ version = '14.17.0'; origins = @('http://127.0.0.1:4400') } | ConvertTo-Json | Set-Content -LiteralPath $locatorPath
        $result = & (Get-Module DevLauncher) {
            param($Case, $Root, $LocatorPath)
            $script:case = $Case
            $script:events = New-Object 'System.Collections.Generic.List[string]'
            $script:firebaseStarted = $Case -notin @('start', 'firebase-timeout', 'service-exited')
            $script:emulatorStarted = $Case -notin @('start', 'service-exited')
            $script:bootProbes = 0
            $script:built = $false
            $script:fixtureRoot = $Root
            $script:fixtureLocator = $LocatorPath
            function script:Get-DevPrerequisites {
                @{ Sdk = 'C:\SDK with spaces'; Adb = 'mock-adb.exe'; Emulator = 'mock-emulator.exe'; Node = 'mock-node.exe'; Java = 'C:\JDK with spaces\bin\java.exe'; LocatorPath = $script:fixtureLocator; Firebase = 'C:\repo with spaces\node_modules\firebase-tools\lib\bin\firebase.js'; Aapt = 'mock-aapt.exe' }
            }
            function script:Get-DevPortOwners {
                if ($script:firebaseStarted) { @{ hub = 100; auth = 100; firestore = 101 } } else { @{} }
            }
            function script:Get-CimInstance {
                @([pscustomobject]@{ ProcessId = 100; ParentProcessId = 1; CommandLine = ('node C:\repo\node_modules\firebase-tools\lib\bin\firebase.js emulators:start --project ' + $(if ($script:case -eq 'wrong-project') { 'demo-other' } else { 'demo-thesaurus' })) }, [pscustomobject]@{ ProcessId = 101; ParentProcessId = 100; CommandLine = 'java firestore.jar' })
            }
            function script:Get-NetTCPConnection { @() }
            function script:Get-Process { param($Id) if ($Id -eq 200 -and $script:case -ne 'service-exited') { [pscustomobject]@{ Id = 200 } } }
            function script:Get-DevHttpJson {
                param($Uri)
                if (-not $script:firebaseStarted -or $script:case -eq 'firebase-timeout' -or ($script:case -eq 'identity-changed' -and $script:built)) { throw 'mock endpoint unavailable' }
                if ($Uri -eq 'http://127.0.0.1:4400/') { return [pscustomobject]@{ version = $(if ($script:case -eq 'stale-locator') { '14.99.0' } else { '14.17.0' }); origins = @('http://127.0.0.1:4400') } }
                if ($Uri -eq 'http://127.0.0.1:4400/emulators') { return [pscustomobject]@{ auth = [pscustomobject]@{ host = '127.0.0.1'; port = 9099 }; firestore = [pscustomobject]@{ host = '127.0.0.1'; port = 8080 } } }
                $script:events.Add('firebase-ready')
                return @{}
            }
            function script:Start-DevBackground {
                param($FilePath, $Arguments, $WorkingDirectory, $LogPrefix, [switch]$Visible)
                $script:events.Add("start:$FilePath $($Arguments -join '|')")
                if ($FilePath -eq 'mock-emulator.exe') { $script:emulatorStarted = $true } else { $script:firebaseStarted = $true }
                return 200
            }
            function script:Invoke-DevCommand {
                param($FilePath, $Arguments, $TimeoutSeconds, $WorkingDirectory)
                $script:events.Add("command:$FilePath $($Arguments -join '|')")
                if ($FilePath -eq 'mock-emulator.exe') { return 'Pixel with spaces' }
                if ($FilePath -like '*gradlew.bat') {
                    if ($script:case -eq 'build-fails') { throw 'mock Gradle exit 1' }
                    $script:built = $true
                    return 'BUILD SUCCESSFUL'
                }
                if ($FilePath -eq 'mock-aapt.exe') { if ($script:case -eq 'wrong-apk') { return "package: name='pl.bargor.thesaurus'`nsdkVersion:'31'" }; return "package: name='pl.bargor.thesaurus.dev'`nsdkVersion:'31'" }
                if ($Arguments[0] -eq 'devices') { if ($script:case -eq 'offline') { return @('physical123 device', 'emulator-5556 offline') }; if ($script:emulatorStarted) { return @('physical123 device', 'emulator-5554 device') }; return @('physical123 device') }
                if (($Arguments -join '|') -match 'emu\|avd\|name') { return @("Pixel with spaces`r`r", "OK`r`r") }
                if ($Arguments -contains 'sys.boot_completed') { $script:bootProbes++; if ($script:case -eq 'boot-timeout' -or ($script:case -eq 'start' -and $script:bootProbes -eq 1)) { return '0' }; return '1' }
                if (($Arguments -join '|') -match 'pm\|path\|android') { $script:events.Add('package-manager-ready'); return 'package:/system/framework/framework-res.apk' }
                if ($Arguments -contains 'ro.build.version.sdk') { return '31' }
                if ($Arguments -contains 'install') { if ($script:case -eq 'install-fails') { return 'Failure [INSTALL_FAILED_INTERNAL_ERROR]' }; return 'Success' }
                if ($Arguments -contains 'am') { if ($script:case -eq 'missing-launch-status') { return 'Starting: Intent' }; return 'Status: ok' }
                if ($Arguments -contains 'pidof') { return '1234' }
                throw "Unexpected mock command: $FilePath $Arguments"
            }
            # GetNewClosure creates a dynamic module; its exported command lookup
            # reaches the global import, so install the same I/O mock there too.
            Set-Item Function:global:Invoke-DevCommand ${function:script:Invoke-DevCommand}
            $failure = $null
            try { Invoke-DevLauncher -RepoRoot $Root -AvdName 'Pixel with spaces' -TimeoutSeconds 1 } catch { $failure = $_.Exception.Message }
            [pscustomobject]@{ Events = @($script:events.ToArray()); Failure = $failure }
        } $case $root $locatorPath
        # Remove only the exact test-owned fixture, including generated session logs.
        Remove-DevTestFixture $root
        Import-Module (Join-Path $PSScriptRoot 'DevLauncher.psm1') -Force
        $events = $result.Events -join "`n"
        if ($case -in @('reuse', 'start')) {
            Assert-True (-not $result.Failure) "$case succeeds: $($result.Failure)"
            Assert-True ($events -match ':app:assembleDevDebug' -and $events -notmatch 'assembleRelease|assembleDebug\|') "$case builds DEV only"
            Assert-True ($events.IndexOf('firebase-ready') -lt $events.IndexOf('gradlew.bat')) "$case Firebase ready before build"
            Assert-True ($events.IndexOf('package-manager-ready') -lt $events.IndexOf('gradlew.bat')) "$case boot/package manager ready before build"
            Assert-True ($events -match 'mock-adb.exe -s\|emulator-5554\|install\|-r\|.*app-devDebug.apk') "$case installs without reset to explicit emulator"
            Assert-True ($events.IndexOf('|install|') -lt $events.IndexOf('|am|start|')) "$case installs before explicit activity launch"
            Assert-True ($events -notmatch 'physical123\||uninstall|clear|wipe-data|Stop-Process|kill') "$case preserves devices and services"
            if ($case -eq 'reuse') {
                Assert-True ($events -notmatch 'start:') 'existing services reused without spawn'
                Assert-True ($events -notmatch '-no-snapshot|-no-boot-anim|-gpu') 'running emulator receives no new startup options'
            } else {
                Assert-True ($events -match 'start:mock-emulator.exe -avd\|Pixel with spaces\|-port\|5554' -and $events -match '--project\|demo-thesaurus') 'new services preserve AVD argument and fixed demo project'
                Assert-True ($events -match 'start:mock-emulator.exe [^\r\n]*-no-snapshot' -and $events -match 'start:mock-emulator.exe [^\r\n]*-no-boot-anim') 'new emulator cold boots without snapshot or boot animation'
                Assert-True ($events -notmatch '-wipe-data') 'cold boot preserves emulator disk data'
            }
        } else {
            Assert-True ([bool]$result.Failure) "$case fails safely"
            if ($case -eq 'install-fails') { Assert-True ($events -notmatch '\|am\|start\|') 'failed install never opens app' }
            elseif ($case -eq 'missing-launch-status') { Assert-True ($events -notmatch '\|pidof\|') 'missing launch status never treated as successful app launch' }
            else { Assert-True ($events -notmatch '\|install\||\|am\|start\|') "$case never installs or opens app" }
            if ($case -in @('firebase-timeout', 'boot-timeout', 'offline', 'wrong-project', 'stale-locator', 'service-exited')) { Assert-True ($events -notmatch 'gradlew.bat') "$case never builds before readiness" }
        }
    }
}
Write-Output "PASS $script:Assertions assertions ($Suite; PowerShell $($PSVersionTable.PSVersion))"
