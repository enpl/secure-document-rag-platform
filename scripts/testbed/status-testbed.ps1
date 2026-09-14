#requires -Version 7.0
<#
.SYNOPSIS
  M16A local testbed - reports actual, verified status (never "READY" just
  because a process was launched - each check hits the real endpoint and
  asserts loopback-only binding).
#>

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_lib.ps1')

$paths = Get-TestbedPaths
$FrontendPort = 15173
$BackendPort = 18080
$KeycloakPort = 18180
$KeycloakRealm = 'sdv-testbed'

function Show-ProcessStatus([string]$Name, [int]$Port) {
    $process = Test-TrackedProcessAlive -Name $Name -PidsDir $paths.PidsDir
    if ($process) {
        Write-Host "$Name process: RUNNING (PID $($process.Id), started $($process.StartTime))" -ForegroundColor Green
    } else {
        $identity = Get-TrackedProcessIdentity -Name $Name -PidsDir $paths.PidsDir
        if ($identity) {
            Write-Host "$Name process: NOT RUNNING (stale tracked PID $($identity.Pid) no longer matches)" -ForegroundColor Yellow
        } else {
            Write-Host "$Name process: not tracked (not started by this script)"
        }
    }
    if ($process) { Test-LoopbackListener -Port $Port -Name $Name | Out-Null }
}

Write-Host '=== Docker Compose ===' -ForegroundColor Cyan
if (Test-Path $paths.ComposeFile) {
    Push-Location $paths.TestbedDir
    try { docker compose -p $paths.ProjectName -f $paths.ComposeFile ps } finally { Pop-Location }
} else {
    Write-Host '(compose file not found)'
}

Write-Host "`n=== Host processes (verified identity, not bare PID) ===" -ForegroundColor Cyan
Show-ProcessStatus -Name 'backend' -Port $BackendPort
Show-ProcessStatus -Name 'frontend' -Port $FrontendPort

Write-Host "`n=== Live checks ===" -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod -Uri "http://localhost:$BackendPort/actuator/health" -TimeoutSec 3
    Write-Host "Backend  http://localhost:$BackendPort : $($health.status)" -ForegroundColor $(if ($health.status -eq 'UP') { 'Green' } else { 'Yellow' })
} catch {
    Write-Host "Backend  http://localhost:$BackendPort : NOT RESPONDING" -ForegroundColor Red
}

try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$FrontendPort/" -TimeoutSec 3
    Write-Host "Frontend http://localhost:$FrontendPort : HTTP $($response.StatusCode)" -ForegroundColor Green
} catch {
    Write-Host "Frontend http://localhost:$FrontendPort : NOT RESPONDING" -ForegroundColor Red
}

try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$KeycloakPort/realms/$KeycloakRealm" -TimeoutSec 3
    Write-Host "Keycloak http://localhost:$KeycloakPort/realms/$KeycloakRealm : HTTP $($response.StatusCode)" -ForegroundColor Green
} catch {
    Write-Host "Keycloak http://localhost:$KeycloakPort/realms/$KeycloakRealm : NOT RESPONDING" -ForegroundColor Red
}

Write-Host "`nLogs: $(Join-Path $paths.LogsDir 'backend.log') / $(Join-Path $paths.LogsDir 'frontend.log')"
