#requires -Version 7.0
<#
.SYNOPSIS
  Non-secret, fail-closed helper for the explicit M17 testbed automatic
  incremental sync scheduler activation gate.

.NOTES
  This file has no top-level service calls and makes no network call of its
  own (unlike _indexing-activation.ps1/_assistant-activation.ps1's readiness
  preflights) - the automatic scheduler itself is a purely internal Spring
  @Scheduled bean with no external dependency to probe before backend launch.

  Automatic incremental sync stays OFF unless -EnableAutoSync is passed
  explicitly to start-testbed.ps1 - baseline runs and every other existing
  activation gate (indexing/Assistant) are unaffected. Turning this on does
  NOT turn on the Outbox Publisher, Index Consumer, or Assistant - those
  remain each their own explicit switch (-EnableIndexing/-EnableAssistant);
  this scheduler only ever reaches the existing sync_runs/source_documents/
  source_permissions/outbox_events tables through the unchanged
  IncrementalSyncService.syncChanges entry point.
#>

Set-StrictMode -Version Latest

function Get-M17AutoSyncBackendOverrides {
    <#
    .SYNOPSIS
      The exact, small backend environment override set for this gate - a
      single boolean switch, no other SDV_SYNC_AUTO_INCREMENTAL_* key is
      touched here (poll interval/batch size/concurrency/backoff cap keep
      their existing application.yml defaults - this task reuses that
      configuration, it does not add new testbed-only tunables).
    #>
    param(
        [Parameter(Mandatory)][bool]$Enabled
    )
    if (-not $Enabled) {
        return @{ SDV_SYNC_AUTO_INCREMENTAL_ENABLED = 'false' }
    }
    return @{ SDV_SYNC_AUTO_INCREMENTAL_ENABLED = 'true' }
}
