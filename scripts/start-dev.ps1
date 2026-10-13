<#
.SYNOPSIS
Builds, installs, and opens the isolated DEV app on an Android emulator.
.DESCRIPTION
Requires Windows PowerShell 5.1 or PowerShell 7, JDK 21, Node.js 22 or later,
repository npm dependencies, and an Android SDK with emulator, platform-tools,
platform 37, build-tools 37.0.0, and an installed API 31 or newer AVD.
Reuses verified demo-thesaurus Firebase Auth/Firestore and the selected running
AVD, or starts them. New emulators cold boot without loading/saving snapshots or
showing the boot animation; their disk/app data is preserved. Services remain
running after success or failure. Firebase
data is in memory: export to a NEW directory before stopping, then import that
directory on the next start. Logs and per-invocation ownership records are under
build/dev-launcher. A recorded PID is informational, never authority to stop a
process. No launcher action clears app data, wipes an AVD, or stops a service.
.PARAMETER AvdName
Installed AVD to use. Required when more than one AVD is installed.
.PARAMETER SdkPath
Installed Android SDK. Defaults to ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in
the repository local.properties, then the standard LOCALAPPDATA/Android/Sdk.
.PARAMETER FirebaseImportPath
Existing Firebase export directory for a newly started suite. A running suite
is reused without importing or changing its data.
.PARAMETER SoftwareRendering
Starts a new emulator with SwiftShader software rendering. Does not change a
running emulator or the saved AVD configuration.
.PARAMETER TimeoutSeconds
Readiness deadline for each service (default 300 seconds). The build has a
separate 1800 second limit; individual ADB and HTTP calls have shorter limits.
.EXAMPLE
.\scripts\start-dev.ps1 -AvdName thesaurus_api31
.EXAMPLE
.\scripts\start-dev.ps1 -AvdName thesaurus_api31 -SdkPath C:\Android\Sdk -SoftwareRendering -FirebaseImportPath .\app\build\my-dev-export
#>
[CmdletBinding()]
param(
    [string]$AvdName,
    [string]$SdkPath,
    [string]$FirebaseImportPath,
    [switch]$SoftwareRendering,
    [ValidateRange(30, 1800)][int]$TimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'dev-launcher/DevLauncher.psm1') -Force
try {
    Invoke-DevLauncher -RepoRoot (Split-Path $PSScriptRoot -Parent) -AvdName $AvdName -SdkPath $SdkPath -FirebaseImportPath $FirebaseImportPath -SoftwareRendering:$SoftwareRendering -TimeoutSeconds $TimeoutSeconds
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
