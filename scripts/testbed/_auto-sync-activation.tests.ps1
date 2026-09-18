#requires -Version 7.0
<#
.SYNOPSIS
  Offline/synthetic tests for the M17 automatic incremental sync scheduler
  activation gate.

.NOTES
  Does not execute start-testbed.ps1, Postgres, or any secret resolver -
  _auto-sync-activation.ps1 makes no network call of its own (it is a single
  boolean override, unlike the indexing/Assistant gates' readiness
  preflights), so this file only needs the override-shape and real-launcher-
  wiring proofs (same AST technique _indexing-activation.tests.ps1/
  _assistant-activation.tests.ps1 already establish).
#>

param([string]$TestScriptRoot = $PSScriptRoot)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command New-IsolatedEnvironment -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_lib.ps1')
}
if (-not (Get-Command Get-M17AutoSyncBackendOverrides -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_auto-sync-activation.ps1')
}

$script:failures = 0

function Assert-True([bool]$Condition, [string]$Description) {
    if ($Condition) {
        Write-Host "  PASS: $Description" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: $Description" -ForegroundColor Red
        $script:failures++
    }
}

Write-Step '1. Baseline (disabled) emits exactly one override: explicitly disabled'
$baselineOverrides = Get-M17AutoSyncBackendOverrides -Enabled $false
Assert-True ($baselineOverrides.Count -eq 1 -and $baselineOverrides['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'false') `
    'plain mode emits exactly one override: automatic incremental sync explicitly disabled'

Write-Step '2. Explicit ON emits exactly one override: explicitly enabled'
$enabledOverrides = Get-M17AutoSyncBackendOverrides -Enabled $true
Assert-True ($enabledOverrides.Count -eq 1 -and $enabledOverrides['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'true') `
    'enabled mode emits exactly one override: automatic incremental sync explicitly enabled'

Write-Step '3. Parent-environment pollution cannot activate it in the isolated child environment'
$originalEnabled = $env:SDV_SYNC_AUTO_INCREMENTAL_ENABLED
try {
    $env:SDV_SYNC_AUTO_INCREMENTAL_ENABLED = 'true'
    # 실제 launcher와 정확히 같은 순서: 부모 프로세스 환경(이미 오염됨)에서 시작해
    # New-IsolatedEnvironment가 SDV_* 전부를 벗겨낸 뒤, 이 Baseline 실행이 만드는
    # 유일한 Override({ enabled=false })만 그 위에 얹는다.
    $childEnv = New-IsolatedEnvironment -Overrides (Get-M17AutoSyncBackendOverrides -Enabled $false)
    Assert-True ($childEnv['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'false') `
        'a parent-shell SDV_SYNC_AUTO_INCREMENTAL_ENABLED=true cannot activate the scheduler in the isolated child environment'
} finally {
    $env:SDV_SYNC_AUTO_INCREMENTAL_ENABLED = $originalEnabled
}

Write-Step '4. Launcher AST/ordering (parse only, real start-testbed.ps1 - not a re-simulation): the auto-sync override is actually wired into the same isolation call, independent of indexing/Assistant'
$launcherPath = Join-Path $TestScriptRoot 'start-testbed.ps1'
$launcherTokens = $null
$launcherParseErrors = $null
$launcherAst = [System.Management.Automation.Language.Parser]::ParseFile($launcherPath, [ref]$launcherTokens,
    [ref]$launcherParseErrors)
Assert-True ($launcherParseErrors.Count -eq 0) 'start-testbed.ps1 parses without PowerShell syntax errors'
$launcherCommands = @($launcherAst.FindAll(
        { param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
$autoSyncOverridesCalls = @($launcherCommands | Where-Object { $_.GetCommandName() -eq 'Get-M17AutoSyncBackendOverrides' })
$isolateCall = $launcherCommands | Where-Object { $_.GetCommandName() -eq 'New-IsolatedEnvironment' } |
    Select-Object -First 1
$backendStartCall = $launcherCommands | Where-Object {
    $_.GetCommandName() -eq 'Start-TrackedProcess' -and $_.Extent.Text -match "-Name\s+'backend'"
} | Select-Object -First 1
Assert-True ($autoSyncOverridesCalls.Count -eq 1) 'launcher calls Get-M17AutoSyncBackendOverrides exactly once'
Assert-True ($null -ne $isolateCall -and $null -ne $backendStartCall) `
    'launcher calls New-IsolatedEnvironment and starts exactly one tracked backend process'
Assert-True ($autoSyncOverridesCalls.Count -eq 1 -and $null -ne $isolateCall -and
        $autoSyncOverridesCalls[0].Extent.StartOffset -lt $isolateCall.Extent.StartOffset) `
    'the auto-sync override call happens before New-IsolatedEnvironment assembles the child environment'
Assert-True ($null -ne $isolateCall -and $null -ne $backendStartCall -and
        $isolateCall.Extent.StartOffset -lt $backendStartCall.Extent.StartOffset) `
    'the isolated environment is assembled before the backend process is actually spawned'
$launcherText = Get-Content -Raw -Encoding UTF8 -LiteralPath $launcherPath
Assert-True ($launcherText.Contains('[switch]$EnableAutoSync')) `
    'the launcher declares an explicit, independent -EnableAutoSync switch (default off, no implicit activation)'
Assert-True ($launcherText.Contains('SDV_SYNC_AUTO_INCREMENTAL_ENABLED') -eq $false) `
    'the launcher never hardcodes the auto-sync env var itself - it always goes through Get-M17AutoSyncBackendOverrides'

Write-Step '5. Real helper, called the same way the launcher AST above just confirmed: independent of indexing/Assistant overrides'
function Get-M17MergedOverridesForAutoSyncTest {
    param([bool]$EnableIndexing, [bool]$EnableAssistant, [bool]$EnableAutoSync)
    $result = @{}
    $indexing = Get-M17IndexingBackendOverrides -Enabled $EnableIndexing
    foreach ($key in $indexing.Keys) { $result[$key] = $indexing[$key] }
    $assistant = Get-M17AssistantBackendOverrides -Enabled $EnableAssistant
    foreach ($key in $assistant.Keys) { $result[$key] = $assistant[$key] }
    $autoSync = Get-M17AutoSyncBackendOverrides -Enabled $EnableAutoSync
    foreach ($key in $autoSync.Keys) { $result[$key] = $autoSync[$key] }
    return $result
}
if (-not (Get-Command Get-M17IndexingBackendOverrides -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_indexing-activation.ps1')
}
if (-not (Get-Command Get-M17AssistantBackendOverrides -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_assistant-activation.ps1')
}

$autoSyncOnly = Get-M17MergedOverridesForAutoSyncTest -EnableIndexing $false -EnableAssistant $false -EnableAutoSync $true
Assert-True ($autoSyncOnly['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'true' -and
        $autoSyncOnly['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'false' -and
        $autoSyncOnly['SDV_RAG_INDEX_CONSUMER_ENABLED'] -eq 'false' -and
        $autoSyncOnly['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') `
    'auto-sync-only: the scheduler is enabled while publisher/consumer/Assistant remain explicitly OFF (no chained activation)'

$allEnabled = Get-M17MergedOverridesForAutoSyncTest -EnableIndexing $true -EnableAssistant $true -EnableAutoSync $true
Assert-True ($allEnabled['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'true' -and
        $allEnabled['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'true' -and
        $allEnabled['SDV_RAG_ASSISTANT_ENABLED'] -eq 'true') `
    'all three gates can be enabled together without interfering with each other''s overrides'

$noneEnabled = Get-M17MergedOverridesForAutoSyncTest -EnableIndexing $false -EnableAssistant $false -EnableAutoSync $false
Assert-True ($noneEnabled['SDV_SYNC_AUTO_INCREMENTAL_ENABLED'] -eq 'false') `
    'baseline: automatic incremental sync stays explicitly disabled alongside every other disabled gate'

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All M17 automatic incremental sync activation tests passed.' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) M17 automatic incremental sync activation test(s) FAILED." -ForegroundColor Red
exit 1
