#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$BaseCommit,
    [ValidateSet('00','01','02','03','04','05','06','07','08','09','10')][string]$Gate,
    [switch]$PlanOnly,
    [switch]$IsolatedExecutionApproved
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Harness.psm1') -Force -DisableNameChecking
$runId = [guid]::NewGuid().ToString('N')
$started = [datetime]::UtcNow
$results = [Collections.Generic.List[object]]::new()
$commands = [Collections.Generic.List[object]]::new()
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
# The one approved report area - not caller-configurable, inside the repo but
# excluded from the source inventory/fingerprint and from Git by .gitignore
# (docs/harness/GATE_MATRIX.md). Resolve-SafeFile both rejects any
# structurally unsafe/protected-category path and walks every existing
# ancestor for a reparse point (symlink/junction), so a swapped ".harness"
# directory is caught the same way a swapped checked-out file would be -
# this is not new path-safety logic, it reuses the same primitive every
# other Resolve-SafeFile call in this file uses.
$approvedReportRoot = Resolve-SafeFile $repo '.harness/reports'
[void][IO.Directory]::CreateDirectory($approvedReportRoot)
# Per-run subdirectory, named only by this run's own fresh GUID - never an
# operator-supplied name, never reused, never recursively deleted. A
# collision (this exact run id already having a directory) is refused
# outright rather than cleaned up.
$runReportDir = Resolve-SafeFile $repo (".harness/reports/$runId")
if (Test-Path -LiteralPath $runReportDir) { throw 'BLOCKED: a report directory already exists for this run id.' }
[void][IO.Directory]::CreateDirectory($runReportDir)
$receiptPath = Join-Path $runReportDir 'result.json'
$policy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'policy.json') -Raw | ConvertFrom-Json
$initialDigest = $null
$finalDigest = $null
$head = $null
$required = @()
$backendRows = $null
$backendFailure = $null
$frontendSummary = $null
$status = 'BLOCKED'
$failureCode = $null

function Invoke-Recorded([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Directory) {
    $at = [datetime]::UtcNow
    try { $r = Invoke-Captured $Exe $Arguments $Directory }
    catch { throw "BLOCKED: executable unavailable for $Name." }
    $commands.Add([pscustomobject]@{ name=$Name; executable=[IO.Path]::GetFileName($Exe);
        arguments=$Arguments; startedAt=$at.ToString('o'); endedAt=[datetime]::UtcNow.ToString('o'); exitCode=$r.Code })
    if ($r.Code -ne 0) { throw "FAIL: $Name exited $($r.Code). Raw output intentionally not retained." }
    return $r.Text
}

function Invoke-BackendOnce {
    if ($script:backendFailure) { throw $script:backendFailure }
    if ($null -ne $script:backendRows) { return }
    $at = [datetime]::UtcNow
    try {
        $wrapper = Resolve-SafeFile $repo 'backend/gradlew.bat'
        [void](Invoke-Recorded 'backend-full' $wrapper @('test','--no-daemon','--rerun-tasks','--no-build-cache','--offline') (Join-Path $repo 'backend'))
        $script:backendRows = @(Read-JUnitSummary (Join-Path $repo 'backend/build/test-results/test') $at)
        Assert-JUnitSummary $script:backendRows @()
    } catch {
        $script:backendFailure = $_.Exception.Message
        throw
    }
}

function Invoke-Gate([string]$Id, [string[]]$Changes) {
    switch ($Id) {
        '00' {
            $a = Get-Content -LiteralPath (Resolve-SafeFile $repo 'AGENTS.md') -Raw
            $c = Get-Content -LiteralPath (Resolve-SafeFile $repo 'CLAUDE.md') -Raw
            Assert-GuideText $a
            Assert-GuideText $c
            if ($a.Replace("`r`n","`n").TrimEnd() -cne $c.Replace("`r`n","`n").TrimEnd()) { throw 'FAIL: agent guides have diverged.' }
            foreach ($p in @('docs/harness/GATE_MATRIX.md','scripts/harness/verify-change.ps1')) {
                if (-not (Test-Path -LiteralPath (Resolve-SafeFile $repo $p))) { throw 'BLOCKED: harness installation is incomplete.' }
            }
        }
        '01' {
            [void](Invoke-Recorded 'whitespace' 'git' (@('--no-optional-locks','--no-pager','-C',$repo,'diff','--no-ext-diff','--no-textconv','--check',$BaseCommit) + (Get-GitExclusions)) $repo)
            $formatPaths = [Collections.Generic.List[string]]::new()
            foreach ($p in $Changes) {
                $full = Resolve-SafeFile $repo $p
                if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
                if ($p -match '\.(ps1|psm1)$') {
                    $tokens=$null; $errors=$null
                    [void][Management.Automation.Language.Parser]::ParseFile($full,[ref]$tokens,[ref]$errors)
                    if (@($errors).Count) { throw 'FAIL: PowerShell syntax error.' }
                }
                if ($p -match '\.(tsx?|jsx?|json|css|md|ya?ml)$') { $formatPaths.Add($full) }
            }
            if ($formatPaths.Count) {
                $prettier = Resolve-SafeFile $repo 'frontend/package.json'
                $pkg = Get-Content -LiteralPath $prettier -Raw | ConvertFrom-Json
                if (-not $pkg.devDependencies.PSObject.Properties['prettier']) { throw 'BLOCKED: install approved pinned Prettier/config before enabling formatting gate.' }
                $cli = Join-Path $repo 'frontend/node_modules/prettier/bin/prettier.cjs'
                if (-not (Test-Path -LiteralPath $cli)) { throw 'BLOCKED: local Prettier is unavailable; no automatic npx download.' }
                for ($i=0; $i -lt $formatPaths.Count; $i+=40) {
                    $last=[Math]::Min($i+39,$formatPaths.Count-1)
                    [void](Invoke-Recorded 'prettier-check' 'node' (@($cli,'--check') + @($formatPaths[$i..$last])) $repo)
                }
            }
        }
        '02' {
            [void](Invoke-Recorded 'eslint' 'npm.cmd' @('run','lint','--','--max-warnings=0') (Join-Path $repo 'frontend'))
            [void](Invoke-Recorded 'typescript-build' 'npm.cmd' @('run','build') (Join-Path $repo 'frontend'))
        }
        '03' {
            $javaRoot = Join-Path $repo 'backend/src/main/java'
            $java = Invoke-GitRead $repo @('ls-files','-z','--cached','--others','--exclude-standard','--','backend/src/main/java')
            $count=0
            foreach ($p in @($java -split "`0" | Where-Object { $_ -match '\.java$' } | Sort-Object -Unique)) {
                $f = Resolve-SafeFile $repo $p
                if (-not (Test-Path -LiteralPath $f)) { continue }
                $count++
                if (@(Get-JavaImportViolations $p (Get-Content -LiteralPath $f -Raw)).Count) {
                    throw 'FAIL: forbidden controller/domain source import; this gate checks explicit imports only.'
                }
            }
            if (-not $count) { throw 'BLOCKED: no Java sources checked.' }
        }
        '04' {
            $dir='backend/src/main/resources/db/migration'
            $base = Invoke-GitRead $repo @('ls-tree','-r','-z','--name-only',$BaseCommit,'--',$dir)
            $current = Invoke-GitRead $repo @('ls-files','-z','--cached','--others','--exclude-standard','--',$dir)
            $existing = @($current -split "`0" | Where-Object { $_ } | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath (Resolve-SafeFile $repo $_) })
            $baseline = @($base -split "`0" | Where-Object { $_ })
            if (-not $baseline.Count) { throw 'BLOCKED: baseline contains no migrations.' }
            Assert-MigrationSet $baseline $Changes $existing
        }
        { $_ -in @('05','06','09') } {
            Invoke-BackendOnce
            $requiredClasses = @($policy.backendSuites.PSObject.Properties[$Id].Value)
            Assert-JUnitSummary $script:backendRows $requiredClasses
            if ($Id -eq '09' -and @($Changes | Where-Object { $_ -match '^ai-service/|rag/|ai/' }).Count) {
                $python = Join-Path $repo 'ai-service/.venv/Scripts/python.exe'
                if (-not (Test-Path -LiteralPath $python)) { throw 'BLOCKED: approved local AI test environment is unavailable.' }
                [void](Invoke-Recorded 'python-tests' $python @('-m','pytest','tests','-q') (Join-Path $repo 'ai-service'))
            }
        }
        '07' {
            # The installed Vitest's `--reporter=json` no longer prints JSON to
            # stdout (only a "JSON report written to ..." line) - it writes to a
            # file, so this reads that file explicitly rather than guessing at
            # stdout content. Path is inside this run's own $runReportDir, so a
            # concurrent or earlier run's result can never be read as this run's own.
            $vitestReportFile = Join-Path $runReportDir 'vitest.json'
            if (Test-Path -LiteralPath $vitestReportFile) { throw 'BLOCKED: a Vitest result file already exists for this run id; refusing to reuse a past result.' }
            [void](Invoke-Recorded 'vitest' 'npm.cmd' @('test','--','--reporter=json',"--outputFile=$vitestReportFile") (Join-Path $repo 'frontend'))
            if (-not (Test-Path -LiteralPath $vitestReportFile -PathType Leaf)) { throw 'BLOCKED: Vitest produced no JSON result file.' }
            try { $v = Get-Content -LiteralPath $vitestReportFile -Raw | ConvertFrom-Json }
            catch { throw 'BLOCKED: Vitest JSON result could not be parsed.' }
            if (-not $v.success -or $v.numTotalTests -le 0 -or $v.numFailedTests -gt 0 -or $v.numPendingTests -gt 0 -or $v.numTodoTests -gt 0) {
                throw 'FAIL: Vitest reported failed, skipped, todo or zero tests.'
            }
            $script:frontendSummary=@{ tests=$v.numTotalTests; failures=$v.numFailedTests; skipped=$v.numPendingTests; todo=$v.numTodoTests }
        }
        '08' {
            $pkg = Get-Content -LiteralPath (Resolve-SafeFile $repo 'frontend/package.json') -Raw | ConvertFrom-Json
            if (-not $pkg.scripts.PSObject.Properties['test:e2e:harness']) { throw 'BLOCKED: real-browser adapter test:e2e:harness is not implemented.' }
            $raw = Invoke-Recorded 'browser-acceptance' 'npm.cmd' @('run','--silent','test:e2e:harness','--','--run-id',$runId,'--source-digest',$initialDigest) (Join-Path $repo 'frontend')
            $adapter = $raw | ConvertFrom-Json
            Assert-AdapterReceipt $adapter $runId $initialDigest @($policy.browserCases)
        }
        '10' { } # Final fingerprint and summary are performed by this trusted runner, not by the model.
    }
}

try {
    $top = Invoke-GitRead $repo @('rev-parse','--show-toplevel')
    if (-not ([IO.Path]::GetFullPath($top).Equals([IO.Path]::GetFullPath($repo),[StringComparison]::OrdinalIgnoreCase))) {
        throw 'BLOCKED: RepoRoot must be the repository root, not a subdirectory.'
    }
    $head = Invoke-GitRead $repo @('show','--no-patch','--format=%H','HEAD')
    $changes = @(Get-ChangedSourcePaths $repo $BaseCommit)
    $required = @(Get-RequiredGates $changes)
    if ($PlanOnly) {
        $status='PLAN_ONLY'
        foreach ($id in $required) { $results.Add([pscustomobject]@{ gate=$id; status='NOT_RUN'; reason='plan-only' }) }
    } else {
        if (-not $IsolatedExecutionApproved) { throw 'BLOCKED: operator approval of the isolated test environment is required.' }
        $initialDigest = Get-SourceDigest $repo
        $selected = if ($Gate) { @($Gate) } else { $required }
        foreach ($id in $selected) {
            try {
                Invoke-Gate $id $changes
                $results.Add([pscustomobject]@{ gate=$id; status='PASS'; reason=$null })
            } catch {
                $msg=$_.Exception.Message
                $s=if ($msg.StartsWith('FAIL:')) { 'FAIL' } else { 'BLOCKED' }
                # Only our bounded messages are persisted. Do not persist raw tool exceptions.
                $safe=if ($msg.StartsWith('FAIL:') -or $msg.StartsWith('BLOCKED:')) { $msg } else { 'BLOCKED: gate could not complete; inspect isolated runner setup.' }
                $results.Add([pscustomobject]@{ gate=$id; status=$s; reason=$safe })
            }
        }
        $finalDigest = Get-SourceDigest $repo
        if ($initialDigest -cne $finalDigest) {
            $results.Add([pscustomobject]@{ gate='10'; status='FAIL'; reason='Source changed during verification; rerun after edits stop.' })
        }
        $status=if (@($results | Where-Object status -eq 'FAIL').Count) { 'FAIL' }
            elseif (@($results | Where-Object status -ne 'PASS').Count) { 'BLOCKED' }
            elseif ($Gate) { 'DIAGNOSTIC_PASS' } else { 'PASS' }
    }
} catch {
    $failureCode=if ($_.Exception.Message.StartsWith('BLOCKED:')) { $_.Exception.Message } else { 'PREFLIGHT_BLOCKED' }
    $status='BLOCKED'
} finally {
    $record=[ordered]@{ schemaVersion=1; runId=$runId; startedAt=$started.ToString('o'); endedAt=[datetime]::UtcNow.ToString('o');
        status=$status; mergeReady=($status -eq 'PASS'); head=$head; baseCommit=$BaseCommit;
        sourceDigestBefore=$initialDigest; sourceDigestAfter=$finalDigest;
        requiredGates=$required; gateOnly=$Gate; failureCode=$failureCode; gates=@($results); commands=@($commands);
        backendSuites=$backendRows; frontendSummary=$frontendSummary;
        scope='offline checks only; not a live MVP acceptance or signed attestation' }
    [IO.File]::WriteAllText($receiptPath,($record | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    Write-Output "$status : $receiptPath"
}
if ($status -in @('PASS','DIAGNOSTIC_PASS')) { exit 0 }
if ($status -eq 'FAIL') { exit 1 }
exit 2
