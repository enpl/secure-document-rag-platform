#requires -Version 7.0
<#
.SYNOPSIS
  M16A local testbed - shared helpers for start/stop/status-testbed.ps1.
  Dot-sourced only; not meant to be run directly.

.NOTES
  Security-relevant conventions enforced throughout this file:
  - Never build a command-line string that embeds a secret value - secrets go
    into a child process's environment block (invisible to `Get-CimInstance
    Win32_Process | Select CommandLine` / Task Manager's command-line column),
    never into -Command/-ArgumentList text.
  - Never write a secret value to a log file, console, or the tracked
    process-identity file.
  - Every native (non-PowerShell) command's exit code is checked explicitly -
    try/catch alone does not reliably catch a nonzero exit code from an
    external .exe on every PowerShell 7 configuration.
#>

Set-StrictMode -Version Latest

# ------------------------------------------------------------------
# Paths
# ------------------------------------------------------------------

function Get-TestbedPaths {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $testbedDir = Join-Path $repoRoot 'infra\testbed'
    [PSCustomObject]@{
        RepoRoot     = $repoRoot
        TestbedDir   = $testbedDir
        SecretsDir   = Join-Path $testbedDir 'secrets'
        PidsDir      = Join-Path $testbedDir '.pids'
        LogsDir      = Join-Path $testbedDir '.logs'
        ComposeFile  = Join-Path $testbedDir 'docker-compose.testbed.yml'
        ProjectName  = 'sdv-testbed'
        BackendDir   = Join-Path $repoRoot 'backend'
        FrontendDir  = Join-Path $repoRoot 'frontend'
    }
}

# ------------------------------------------------------------------
# Console output (never pass a secret value to these)
# ------------------------------------------------------------------

function Write-Step($message) { Write-Host "`n==> $message" -ForegroundColor Cyan }
function Write-Ok($message) { Write-Host "    OK: $message" -ForegroundColor Green }
function Write-Warn2($message) { Write-Host "    WARN: $message" -ForegroundColor Yellow }
function Write-Err2($message) { Write-Host "    ERROR: $message" -ForegroundColor Red }

function Invoke-Fail($message) {
    Write-Host "`nFAILED: $message" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------
# Secret generation
# ------------------------------------------------------------------

function New-RandomSecret([int]$Length = 24) {
    $bytes = [byte[]]::new($Length)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $b64 = [Convert]::ToBase64String($bytes) -replace '[/+=]', ''
    return $b64.Substring(0, [Math]::Min($Length, $b64.Length))
}

function New-Aes256KeyBase64 {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

# ------------------------------------------------------------------
# Native command exit-code checking (never rely on try/catch alone)
# ------------------------------------------------------------------

function Invoke-NativeChecked {
    <#
    .SYNOPSIS
      Runs a native command and fails loudly on a nonzero exit code -
      independent of $ErrorActionPreference/$PSNativeCommandUseErrorActionPreference
      behavior, which varies across PowerShell configurations.
    #>
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [string]$FailureMessage = "Command failed: $FilePath"
    )
    & $FilePath @ArgumentList
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        Invoke-Fail "$FailureMessage (exit code $exitCode)"
    }
}

# ------------------------------------------------------------------
# Environment isolation - deny-listed prefixes that must never leak from
# the invoking shell into a testbed application process. Explicitly setting
# e.g. SPRING_DATASOURCE_URL does not neutralize an inherited
# SPRING_FLYWAY_URL/SOURCE_TOKEN_ENCRYPTION_KEY_FILE/SPRING_APPLICATION_JSON -
# each must be stripped (or explicitly overridden) individually.
# ------------------------------------------------------------------

$script:EnvDenylistPrefixes = @(
    'SPRING_', 'GOOGLE_', 'KEYCLOAK_', 'SOURCE_TOKEN_ENCRYPTION_KEY',
    'SDV_', 'SERVER_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
    'GRADLE_OPTS', 'VITE_'
)

function New-IsolatedEnvironment {
    <#
    .SYNOPSIS
      Returns a hashtable representing a full child-process environment:
      the current process's own environment, with every deny-listed
      (application-configuration-capable) variable removed, then the
      caller's explicit $Overrides applied on top. OS/tool variables
      (PATH, JAVA_HOME, TEMP, SystemRoot, ...) are preserved untouched.
    #>
    param([hashtable]$Overrides = @{})

    $result = @{}
    foreach ($entry in [System.Environment]::GetEnvironmentVariables().GetEnumerator()) {
        $key = $entry.Key
        $denied = $false
        foreach ($prefix in $script:EnvDenylistPrefixes) {
            if ($key.ToUpperInvariant().StartsWith($prefix)) { $denied = $true; break }
        }
        if (-not $denied) { $result[$key] = $entry.Value }
    }
    foreach ($key in $Overrides.Keys) { $result[$key] = $Overrides[$key] }
    return $result
}

# ------------------------------------------------------------------
# Tracked process lifecycle - identity is (PID, exact process start time,
# main module path), never PID alone. A stale/reused PID must never
# authorize killing an arbitrary process.
# ------------------------------------------------------------------

function Start-TrackedProcess {
    <#
    .SYNOPSIS
      Launches FilePath/ArgumentList as a hidden, detached process whose
      stdout/stderr are redirected to LogFile at the OS level (via a cmd.exe
      wrapper's `>>`/`2>&1`), not through a PowerShell-managed pipe. This
      matters: a managed pipe (ProcessStartInfo.RedirectStandardOutput read
      via Register-ObjectEvent) only keeps draining while THIS PowerShell
      process stays alive - since this launcher is meant to exit after
      starting things, that would eventually fill the child's stdout buffer
      and hang it. OS-level file redirection has no such dependency.

    .NOTES
      Secrets travel only through $Environment (set directly on
      ProcessStartInfo.EnvironmentVariables) - never through $ArgumentList,
      $FilePath, or the constructed cmd.exe command line, so they never
      appear in that command line or in this process's own arguments.
    #>
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][hashtable]$Environment,
        [Parameter(Mandatory)][string]$PidsDir,
        [Parameter(Mandatory)][string]$LogFile
    )

    New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
    if (Test-Path $LogFile) { Remove-Item $LogFile -Force }

    function Format-CmdArg([string]$value) {
        if ($value -match '[\s"]') { return '"' + ($value -replace '"', '""') + '"' }
        return $value
    }
    $quotedTarget = Format-CmdArg $FilePath
    $quotedArgs = ($ArgumentList | ForEach-Object { Format-CmdArg $_ }) -join ' '
    $quotedLog = Format-CmdArg $LogFile
    $innerCommand = "$quotedTarget $quotedArgs > $quotedLog 2>&1"

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Join-Path $env:SystemRoot 'System32\cmd.exe')
    $psi.Arguments = "/d /c `"$innerCommand`""
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden

    # 자식 프로세스 환경을 완전히 새로 구성한다 - 상속된 값이 남아있지 않도록
    # Clear() 후 명시적으로만 채운다(Secret은 여기(EnvironmentVariables)로만
    # 전달되고, 위 Command Line 어디에도 나타나지 않는다).
    $psi.EnvironmentVariables.Clear()
    foreach ($key in $Environment.Keys) {
        $psi.EnvironmentVariables[$key] = [string]$Environment[$key]
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) {
        Invoke-Fail "Failed to start process for '$Name' ($FilePath)"
    }

    # Path는 방금 실제로 시작을 요청한 실행 파일(cmd.exe) 그 자체다 - 우리가
    # 이미 알고 있는 값이므로, 막 시작한 프로세스의 MainModule을 즉시
    # 조회하지 않는다(초기화 직후 그 조회 자체가 일시적으로 실패/지연될 수
    # 있다 - 이후 Test-TrackedProcessAlive의 재확인은 충분히 시간이 지난
    # 뒤에 이뤄지므로 그 조회는 안정적이다).
    $identity = [PSCustomObject]@{
        Name           = $Name
        Pid            = $process.Id
        StartTimeTicks = $process.StartTime.Ticks
        Path           = $psi.FileName
    }
    New-Item -ItemType Directory -Force -Path $PidsDir | Out-Null
    $identity | ConvertTo-Json | Set-Content -Path (Join-Path $PidsDir "$Name.json")

    return $process
}

function Get-TrackedProcessIdentity {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$PidsDir)
    $file = Join-Path $PidsDir "$Name.json"
    if (-not (Test-Path $file)) { return $null }
    try { return Get-Content $file -Raw | ConvertFrom-Json } catch { return $null }
}

function Test-TrackedProcessAlive {
    <#
    .SYNOPSIS
      Verifies the tracked identity (PID + exact start time + module path)
      still matches a live process - never trusts a bare PID. Returns the
      live Process object, or $null if not verifiably alive (missing,
      exited, or the PID was recycled by an unrelated program).
    #>
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$PidsDir)

    $identity = Get-TrackedProcessIdentity -Name $Name -PidsDir $PidsDir
    if ($null -eq $identity) { return $null }

    $process = Get-Process -Id $identity.Pid -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    if ($process.StartTime.Ticks -ne $identity.StartTimeTicks) {
        # 같은 PID지만 시작 시각이 다르다 - OS가 이 PID를 재사용해 전혀 다른
        # 프로세스에 배정한 것이다. 이 프로세스를 건드리지 않는다.
        return $null
    }
    try {
        if ($process.MainModule.FileName -ne $identity.Path) { return $null }
    } catch {
        # 접근 거부 등으로 MainModule을 못 읽으면 안전하게 "확인 불가"로 처리한다.
        return $null
    }
    return $process
}

function Stop-TrackedProcessSafely {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$PidsDir)

    $file = Join-Path $PidsDir "$Name.json"
    $process = Test-TrackedProcessAlive -Name $Name -PidsDir $PidsDir

    if ($null -eq $process) {
        $identity = Get-TrackedProcessIdentity -Name $Name -PidsDir $PidsDir
        if ($null -eq $identity) {
            Write-Host "    (no tracked $Name process)"
        } else {
            Write-Warn2 "$Name tracking (PID $($identity.Pid)) no longer matches a live process it started - not killing anything under that PID. Removing stale tracking."
        }
        Remove-Item $file -ErrorAction SilentlyContinue
        return $true
    }

    # 전체 프로세스 트리를 한 번에 종료한다(예: gradlew/npm Wrapper가 자식을
    # 남기는 경우) - 부모를 먼저 따로 죽이지 않는다(taskkill 전에 부모를
    # 죽이면 /T가 이미 사라진 부모의 자손을 못 찾을 수 있다).
    & taskkill.exe /PID $process.Id /T /F *>$null
    $killExit = $LASTEXITCODE

    Start-Sleep -Milliseconds 500
    $stillAlive = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
    if ($stillAlive) {
        Write-Err2 "$Name (PID $($process.Id)) did not terminate (taskkill exit $killExit). Tracking preserved - re-run stop, or investigate manually."
        return $false
    }

    Write-Ok "Stopped $Name (PID $($process.Id))"
    Remove-Item $file -ErrorAction SilentlyContinue
    return $true
}

# ------------------------------------------------------------------
# Loopback-binding verification - a 200 response on localhost alone does
# not prove the listener is private; check the actual bound address.
# ------------------------------------------------------------------

function Test-LoopbackListener {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Name)

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $connections) {
        Write-Warn2 "$Name : no listener found on port $Port yet"
        return $false
    }
    $loopbackAddresses = @('127.0.0.1', '::1')
    $bad = $connections | Where-Object { $loopbackAddresses -notcontains $_.LocalAddress }
    if ($bad) {
        $addresses = ($bad | ForEach-Object { $_.LocalAddress }) -join ', '
        Write-Err2 "$Name : listener on port $Port is bound to $addresses - NOT loopback-only. Refusing to treat this as ready."
        return $false
    }
    Write-Ok "$Name : port $Port is loopback-only ($((($connections | ForEach-Object { $_.LocalAddress }) -join ', ')))"
    return $true
}

function Assert-TestbedDatabaseTarget {
    <#
    .SYNOPSIS
      Fails closed unless the resolved datasource URL/Flyway user genuinely
      point at this testbed's own dedicated Postgres (host/port/db name) -
      never tries an uncertain target "just in case it works".
    #>
    param(
        [Parameter(Mandatory)][string]$DatasourceUrl,
        [Parameter(Mandatory)][string]$FlywayUser,
        [Parameter(Mandatory)][int]$ExpectedPort
    )
    # throw(터미네이팅 예외)를 쓴다 - exit를 직접 호출하지 않는다. 그래야
    # 실제 Launcher($ErrorActionPreference='Stop')에서는 그대로 전체가
    # 중단되어 Fail Closed 하면서도, 이 함수 자체는 Self-Test에서
    # try/catch로 검증 가능하다.
    $expectedPattern = "^jdbc:postgresql://(localhost|127\.0\.0\.1):$ExpectedPort/sdv(\?.*)?$"
    if ($DatasourceUrl -notmatch $expectedPattern) {
        throw "Resolved datasource URL '$DatasourceUrl' does not match the expected testbed target (jdbc:postgresql://localhost:$ExpectedPort/sdv) - refusing to start an application that could run migrations against an unexpected database."
    }
    if ($FlywayUser -ne 'sdv_user') {
        throw "Resolved Flyway user '$FlywayUser' is not the expected testbed bootstrap identity 'sdv_user' - refusing to start."
    }
}

function Test-PortFree([int]$Port) {
    $inUse = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return -not $inUse
}
