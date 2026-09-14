#requires -Version 7.0
<#
.SYNOPSIS
  M16A testbed safety correction - focused, non-destructive checks for
  _lib.ps1 using synthetic sentinel values and harmless throwaway
  processes/listeners only. Run this BEFORE the real testbed lifecycle
  smoke test. Never touches Docker, a real database, or any tracked
  testbed process.

.NOTES
  Run: pwsh -File scripts\testbed\_lib.tests.ps1
  Exits nonzero if any check fails.
#>

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_lib.ps1')

$script:failures = 0

function Assert-True([bool]$Condition, [string]$Description) {
    if ($Condition) {
        Write-Host "  PASS: $Description" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: $Description" -ForegroundColor Red
        $script:failures++
    }
}

$paths = Get-TestbedPaths
$testPidsDir = Join-Path $paths.TestbedDir '.pids-selftest'
$testLogDir = Join-Path $paths.TestbedDir '.logs-selftest'
New-Item -ItemType Directory -Force -Path $testPidsDir, $testLogDir | Out-Null

try {
    # ------------------------------------------------------------------
    Write-Step '1. Command-line secret hygiene (sentinel value, never printed)'
    $sentinel = 'SENTINEL-' + [Guid]::NewGuid().ToString('N')
    $env0 = New-IsolatedEnvironment -Overrides @{ TESTBED_SELFTEST_SECRET = $sentinel }
    $process = Start-TrackedProcess -Name 'selftest-argv' -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 4') `
        -WorkingDirectory $paths.RepoRoot -Environment $env0 -PidsDir $testPidsDir `
        -LogFile (Join-Path $testLogDir 'argv.log')
    Start-Sleep -Milliseconds 500
    $cmdLines = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id) OR ProcessId=$($process.Id)" |
        ForEach-Object { $_.CommandLine }
    $leaked = $cmdLines | Where-Object { $_ -and $_.Contains($sentinel) }
    Assert-True ($null -eq $leaked -or $leaked.Count -eq 0) 'sentinel secret value does not appear in any process command line (own or child)'
    Stop-TrackedProcessSafely -Name 'selftest-argv' -PidsDir $testPidsDir | Out-Null

    # ------------------------------------------------------------------
    Write-Step '2. Environment isolation (harmless synthetic conflicting variable, no real DB touched)'
    $env:SPRING_FLYWAY_URL = 'jdbc:postgresql://evil-host-that-does-not-exist:5432/evil'
    try {
        $isolated = New-IsolatedEnvironment -Overrides @{ SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:15432/sdv' }
        Assert-True (-not $isolated.ContainsKey('SPRING_FLYWAY_URL')) 'an inherited SPRING_FLYWAY_URL is stripped from the isolated environment'
        Assert-True ($isolated['SPRING_DATASOURCE_URL'] -eq 'jdbc:postgresql://localhost:15432/sdv') 'explicit override is present as given'
    } finally {
        Remove-Item Env:\SPRING_FLYWAY_URL -ErrorAction SilentlyContinue
    }

    # ------------------------------------------------------------------
    Write-Step '3. Fail-closed database target validation'
    $threw = $false
    try {
        Assert-TestbedDatabaseTarget -DatasourceUrl 'jdbc:postgresql://example.com:5432/production' -FlywayUser 'sdv_user' -ExpectedPort 15432
    } catch { $threw = $true }
    Assert-True $threw 'an unexpected database target is rejected (fail closed), never attempted'
    $ok = $true
    try {
        Assert-TestbedDatabaseTarget -DatasourceUrl 'jdbc:postgresql://localhost:15432/sdv' -FlywayUser 'sdv_user' -ExpectedPort 15432
    } catch { $ok = $false }
    Assert-True $ok 'the actual testbed target passes validation'

    # ------------------------------------------------------------------
    Write-Step '4. Loopback-listener detection (synthetic listeners, not the real app)'
    $loopbackListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $anyListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, 0)
    try {
        $loopbackListener.Start()
        $anyListener.Start()
        $loopbackPort = ($loopbackListener.LocalEndpoint -as [System.Net.IPEndPoint]).Port
        $anyPort = ($anyListener.LocalEndpoint -as [System.Net.IPEndPoint]).Port
        Start-Sleep -Milliseconds 300
        Assert-True (Test-LoopbackListener -Port $loopbackPort -Name 'synthetic-loopback') 'a 127.0.0.1-bound listener is accepted as loopback-only'
        Assert-True (-not (Test-LoopbackListener -Port $anyPort -Name 'synthetic-any')) 'a 0.0.0.0-bound listener is correctly rejected as NOT loopback-only'
    } finally {
        $loopbackListener.Stop(); $anyListener.Stop()
    }

    # ------------------------------------------------------------------
    Write-Step '5. Stale/reused PID tracking never authorizes killing an unrelated process'
    $unrelated = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 20') -WindowStyle Hidden -PassThru
    try {
        Start-Sleep -Milliseconds 300
        # 의도적으로 틀린 시작 시각을 기록한 가짜 추적 파일 - "PID는 맞지만 진짜 우리가
        # 띄운 그 프로세스는 아니다"를 재현한다.
        [PSCustomObject]@{
            Name = 'selftest-stale'; Pid = $unrelated.Id
            StartTimeTicks = 1; Path = $unrelated.Path
        } | ConvertTo-Json | Set-Content -Path (Join-Path $testPidsDir 'selftest-stale.json')

        $result = Stop-TrackedProcessSafely -Name 'selftest-stale' -PidsDir $testPidsDir
        Start-Sleep -Milliseconds 300
        $stillAlive = Get-Process -Id $unrelated.Id -ErrorAction SilentlyContinue
        Assert-True ($null -ne $stillAlive) 'an unrelated process with a stale/mismatched tracked identity is left completely alone (still running)'
    } finally {
        Stop-Process -Id $unrelated.Id -Force -ErrorAction SilentlyContinue
    }

    # ------------------------------------------------------------------
    Write-Step '6. Owned parent+child process tree is fully stopped'
    $env1 = New-IsolatedEnvironment
    $treeProcess = Start-TrackedProcess -Name 'selftest-tree' -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', 'ping -n 30 127.0.0.1 >nul') `
        -WorkingDirectory $paths.RepoRoot -Environment $env1 -PidsDir $testPidsDir `
        -LogFile (Join-Path $testLogDir 'tree.log')
    Start-Sleep -Milliseconds 800
    $stopped = Stop-TrackedProcessSafely -Name 'selftest-tree' -PidsDir $testPidsDir
    Start-Sleep -Milliseconds 500
    $topGone = $null -eq (Get-Process -Id $treeProcess.Id -ErrorAction SilentlyContinue)
    Assert-True $stopped 'Stop-TrackedProcessSafely reports success for an owned process tree'
    Assert-True $topGone 'the tracked top-level (cmd.exe wrapper) process is actually gone afterward'

    # ------------------------------------------------------------------
    Write-Step '7. Repeated stop is safe (idempotent)'
    $secondStop = Stop-TrackedProcessSafely -Name 'selftest-tree' -PidsDir $testPidsDir
    Assert-True $secondStop 'stopping an already-stopped tracked name again succeeds without error'

} finally {
    Remove-Item -Recurse -Force $testPidsDir, $testLogDir -ErrorAction SilentlyContinue
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All launcher self-tests passed.' -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failures) launcher self-test(s) FAILED." -ForegroundColor Red
    exit 1
}
