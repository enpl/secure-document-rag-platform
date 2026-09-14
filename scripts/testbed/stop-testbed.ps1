#requires -Version 7.0
<#
.SYNOPSIS
  M16A local testbed - stops only this testbed's verified backend/frontend
  processes and its isolated Docker Compose project. Never touches the
  canonical dev stack, never deletes data (see docs/runbooks/
  M16A_LOCAL_TESTBED.md for the separate, explicit reset procedure).
#>

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_lib.ps1')

$paths = Get-TestbedPaths
$overallOk = $true

Write-Step 'Stopping tracked host processes (verified identity, never a bare PID)'
if (-not (Stop-TrackedProcessSafely -Name 'backend' -PidsDir $paths.PidsDir)) { $overallOk = $false }
if (-not (Stop-TrackedProcessSafely -Name 'frontend' -PidsDir $paths.PidsDir)) { $overallOk = $false }

Write-Step "Stopping Compose project '$($paths.ProjectName)' (containers only - volumes/data preserved)"
if (Test-Path $paths.ComposeFile) {
    Push-Location $paths.TestbedDir
    try {
        # `down` without `-v`: removes containers/network, keeps named volumes -
        # Postgres data and the Keycloak realm/users survive for the next start.
        docker compose -p $paths.ProjectName -f $paths.ComposeFile down
        if ($LASTEXITCODE -ne 0) {
            Write-Err2 "docker compose down exited with code $LASTEXITCODE - containers may not be fully stopped. Check: docker compose -p $($paths.ProjectName) -f $($paths.ComposeFile) ps"
            $overallOk = $false
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host '    (compose file not found - nothing to stop)'
}

Write-Host ''
if ($overallOk) {
    Write-Host 'Testbed stopped. Data/users/credentials were preserved (ordinary stop).' -ForegroundColor Green
} else {
    Write-Host 'Testbed stop completed with warnings above - some resource may not have been fully released.' -ForegroundColor Yellow
    Write-Host 'Tracking for anything not verifiably stopped was left in place so a re-run can retry it safely.' -ForegroundColor Yellow
}
Write-Host 'To fully reset (delete all testbed data/volumes/generated secrets), see the' -ForegroundColor Yellow
Write-Host '"Full reset (destructive, manual only)" section of docs/runbooks/M16A_LOCAL_TESTBED.md.' -ForegroundColor Yellow
Write-Host ''

if (-not $overallOk) { exit 1 }
