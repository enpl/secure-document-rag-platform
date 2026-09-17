#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Harness.psm1') -Force -DisableNameChecking
$script:passed=0
function Check([string]$Name,[scriptblock]$Body) {
    & $Body
    $script:passed++
    Write-Output "PASS $Name"
}
function Require([bool]$Condition) { if (-not $Condition) { throw 'Assertion failed.' } }
function Reject([scriptblock]$Body) {
    $caught=$false
    try { & $Body | Out-Null } catch { $caught=$true }
    Require $caught
}
Check 'path traversal denied' { Reject { Get-SafeRelativePath '../secrets.txt' } }
Check 'absolute path denied' { Reject { Get-SafeRelativePath 'C:/outside.txt' } }
Check 'secret directory denied' { Reject { Get-SafeRelativePath 'infra/testbed/secrets/fixture.txt' } }
Check 'oauth secret denied' { Reject { Get-SafeRelativePath 'config/google-oauth.local.env' } }
Check 'workmd denied' { Reject { Get-SafeRelativePath 'docs/workmd/fixture.md' } }
Check 'git metadata denied' { Reject { Get-SafeRelativePath '.git/config' } }
Check 'root-level .env.example still denied via Get-SafeRelativePath' { Reject { Get-SafeRelativePath '.env.example' } }
Check 'nested non-testbed secrets doc still denied via Get-SafeRelativePath' { Reject { Get-SafeRelativePath 'infra/secrets/README.md' } }
Check 'protected-category paths are structurally fine, only category-blocked' {
    Require ((Assert-StructurallySafePath '.env.example') -eq '.env.example')
    Require ((Assert-StructurallySafePath 'infra/secrets/README.md') -eq 'infra/secrets/README.md')
    Require (Test-ProtectedRelativePath '.env.example')
    Require (Test-ProtectedRelativePath 'infra/secrets/README.md')
    Require (-not (Test-ProtectedRelativePath 'frontend/src/App.tsx'))
}
Check 'structural attacks are still hard-blocked independent of the protected-category check' {
    Reject { Assert-StructurallySafePath '../outside.txt' }
    Reject { Assert-StructurallySafePath 'C:/outside.txt' }
}
Check 'synthetic inventory: protected paths excluded before hashing, allowed sources kept in full' {
    $synthetic = @('.env.example', 'infra/secrets/README.md', 'infra/testbed/secrets/google-oauth.local.env',
        'docs/workmd/plan.md', 'node_modules/pkg/index.js', '.git/config', '.venv/bin/python',
        'backend/src/main/java/com/sdv/Foo.java', 'frontend/src/App.tsx', 'README.md')
    $kept = @($synthetic | ForEach-Object { Assert-StructurallySafePath $_ } |
        Where-Object { -not (Test-ProtectedRelativePath $_) } | Sort-Object)
    $expected = @('README.md', 'backend/src/main/java/com/sdv/Foo.java', 'frontend/src/App.tsx') | Sort-Object
    Require (($kept -join '|') -eq ($expected -join '|'))
}
Check 'Get-SourceDigest succeeds against the real repo tree despite pre-existing protected files' {
    $root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $digest = Get-SourceDigest $root
    Require ($digest -match '^[0-9a-f]{64}$')
}
Check 'source path allowed' { Require ((Get-SafeRelativePath 'frontend\src\App.tsx') -eq 'frontend/src/App.tsx') }
Check 'frontend includes browser' { Require ((Get-RequiredGates @('frontend/src/App.tsx')) -contains '08') }
Check 'API includes frontend contracts' { Require ((Get-RequiredGates @('backend/src/main/java/com/sdv/identity/api/User.java')) -contains '07') }
Check 'unknown path widens all gates' { Require ((Get-RequiredGates @('new-area/rule.txt')).Count -eq 11) }
Check 'docs runs baseline controls' { Require ((Get-RequiredGates @('docs/plan/status.md')).Count -eq 4) }
Check 'migration update rejected' { Reject { Assert-MigrationSet @('m/V001__a.sql') @('m/V001__a.sql') @('m/V001__a.sql') } }
Check 'migration deletion rejected' { Reject { Assert-MigrationSet @('m/V001__a.sql') @() @() } }
Check 'migration duplicate numeric versions rejected' { Reject { Assert-MigrationSet @('m/V001__a.sql') @() @('m/V001__a.sql','m/V1__b.sql') } }
Check 'lower new migration rejected' { Reject { Assert-MigrationSet @('m/V003__a.sql') @() @('m/V003__a.sql','m/V002__b.sql') } }
Check 'higher new migration accepted' { Assert-MigrationSet @('m/V001__a.sql') @('m/V002__b.sql') @('m/V001__a.sql','m/V002__b.sql') }
Check 'controller repository import rejected' {
    Require (@(Get-JavaImportViolations 'x/api/AController.java' 'import com.sdv.foo.infrastructure.persistence.repository.ARepository;').Count -eq 1)
}
Check 'domain JPA import rejected' { Require (@(Get-JavaImportViolations 'x/domain/A.java' 'import jakarta.persistence.Entity;').Count -eq 1) }
Check 'application import accepted' { Require (@(Get-JavaImportViolations 'x/api/AController.java' 'import com.sdv.foo.application.Service;').Count -eq 0) }
Check 'guide too long rejected' { Reject { Assert-GuideText ((1..16 | ForEach-Object { 'GATE_MATRIX.md verify-change.ps1' }) -join "`n") } }
Check 'guide reference missing rejected' { Reject { Assert-GuideText 'short but incomplete' } }
Check 'both proposed guides are 15 lines and identical' {
    $root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $a=Get-Content (Join-Path $root 'AGENTS.md') -Raw
    $c=Get-Content (Join-Path $root 'CLAUDE.md') -Raw
    Assert-GuideText $a; Assert-GuideText $c; Require ($a -ceq $c)
}
$good=[pscustomobject]@{ Name='Example'; Tests=1; Failures=0; Errors=0; Skipped=0 }
Check 'valid suite accepted' { Assert-JUnitSummary @($good) @('Example') }
Check 'missing mandatory suite rejected' { Reject { Assert-JUnitSummary @($good) @('Missing') } }
Check 'zero suites rejected' { Reject { Assert-JUnitSummary @() @() } }
Check 'skipped suite rejected' { Reject { Assert-JUnitSummary @([pscustomobject]@{Name='Example';Tests=1;Failures=0;Errors=0;Skipped=1}) @('Example') } }
$valid=[pscustomobject]@{runId='run';sourceDigest='digest';cases=@([pscustomobject]@{id='mobile';status='PASS'})}
Check 'browser receipt accepted' { Assert-AdapterReceipt $valid 'run' 'digest' @('mobile') }
Check 'stale browser receipt rejected' { Reject { Assert-AdapterReceipt $valid 'other-run' 'digest' @('mobile') } }
Check 'missing browser case rejected' { Reject { Assert-AdapterReceipt $valid 'run' 'digest' @('desktop') } }
Check 'native exit code preserved and output not streamed' {
    $r=Invoke-Captured 'node' @('-e','process.stdout.write("synthetic");process.exit(7)') $PSScriptRoot
    Require ($r.Code -eq 7 -and $r.Text -eq 'synthetic')
}
Check 'missing executable cannot reuse a prior success' {
    Reject { Invoke-Captured 'sdv-harness-intentionally-missing-executable' @() $PSScriptRoot }
}
Check 'stderr warning never splices into NUL-delimited stdout' {
    # \uXXXX and \n below are literal ASCII backslash-sequences for node's own JS parser to evaluate
    # at run time (a Korean filename plus a NUL separator). Embedding the real bytes directly in this
    # file risks a real NUL truncating the Windows process command line before node ever sees it.
    $script = "process.stderr.write('warning: LF will be replaced by CRLF in \uac00\ub098\ub2e4.txt.\n');" +
        "process.stdout.write('frontend/src/\uc571.tsx\u0000backend/src/main/java/com/sdv/A.java\u0000')"
    $r = Invoke-Captured 'node' @('-e', $script) $PSScriptRoot
    $paths = @($r.Text -split "`0" | Where-Object { $_ })
    Require ($paths.Count -eq 2)
    Require ($paths[0].Length -eq 'frontend/src/X.tsx'.Length)
    Require ((Get-SafeRelativePath $paths[0]) -eq $paths[0])
    Require ($paths[1] -eq 'backend/src/main/java/com/sdv/A.java')
    Require ($r.ErrorText -match 'warning:')
}
Check 'stdout without any stderr warning still parses cleanly' {
    $r = Invoke-Captured 'node' @('-e', "process.stdout.write('a.txt\u0000b.txt\u0000')") $PSScriptRoot
    $paths = @($r.Text -split "`0" | Where-Object { $_ })
    Require ($paths.Count -eq 2 -and $paths[0] -eq 'a.txt' -and $paths[1] -eq 'b.txt' -and [string]::IsNullOrEmpty($r.ErrorText))
}
Check 'abnormal exit code is preserved alongside a stderr warning' {
    $r = Invoke-Captured 'node' @('-e', "process.stderr.write('warn\n');process.exit(3)") $PSScriptRoot
    Require ($r.Code -eq 3)
}
Check 'Git mutation is rejected before execution' { Reject { Invoke-GitRead $PSScriptRoot @('add','.') } }
Check 'Git output file side effect is rejected' { Reject { Invoke-GitRead $PSScriptRoot @('diff','--output=forbidden.txt') } }
Check 'all PowerShell files parse' {
    foreach ($f in Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -in @('.ps1','.psm1')) {
        $tokens=$null;$errors=$null
        [void][Management.Automation.Language.Parser]::ParseFile($f.FullName,[ref]$tokens,[ref]$errors)
        Require (@($errors).Count -eq 0)
    }
}
Write-Output "Harness self-tests: $script:passed passed. Product tests and physical access controls NOT RUN."
