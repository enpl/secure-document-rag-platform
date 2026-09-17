#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$BaseCommit,
    [switch]$IsolatedExecutionApproved
)
$runner = Join-Path $PSScriptRoot 'verify-change.ps1'
& $runner -RepoRoot $RepoRoot -BaseCommit $BaseCommit -Gate '06' -IsolatedExecutionApproved:$IsolatedExecutionApproved
exit $LASTEXITCODE

