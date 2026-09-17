#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Single source of truth for "never read/hash this, no matter how it is named" -
# shared by Get-SafeRelativePath (hard BLOCK on direct use) and by the bulk
# enumerators (Get-ChangedSourcePaths/Get-SourceDigest), which must instead
# silently exclude a matching path before ever calling Resolve-SafeFile on it.
# A path matching this is excluded by category (secrets dirs, .env-family,
# the OAuth secret file, docs/workmd, VCS/dependency internals) regardless of
# its own name or contents - matching ".env.example"/"secrets/README.md" does
# not make either one readable; it only decides whether this predicate fires.
$script:ProtectedPathPattern = '(?i)(^|/)(secrets|\.git|node_modules|\.venv)(/|$)|(^|/)\.env($|\.)|google-oauth\.local\.env|^docs/workmd/'

function Assert-StructurallySafePath([string]$Path) {
    $p = $Path.Replace('\', '/')
    if ([IO.Path]::IsPathRooted($p) -or $p -match '(^|/)\.\.(/|$)|[\x00-\x1f]') {
        throw 'BLOCKED: path is outside the permitted source inventory.'
    }
    return $p
}

function Test-ProtectedRelativePath([string]$NormalizedPath) {
    return [bool]($NormalizedPath -match $script:ProtectedPathPattern)
}

function Get-SafeRelativePath([string]$Path) {
    $p = Assert-StructurallySafePath $Path
    if (Test-ProtectedRelativePath $p) {
        throw 'BLOCKED: path is outside the permitted source inventory.'
    }
    return $p
}

function Resolve-SafeFile([string]$Root, [string]$Relative) {
    $p = Get-SafeRelativePath $Relative
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath([IO.Path]::Combine($resolvedRoot, $p))
    if (-not $full.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'BLOCKED: path escaped the repository.'
    }
    $cursor = $full
    while ($cursor.Length -gt $resolvedRoot.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'BLOCKED: reparse points are not allowed in checked paths.'
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}

function Invoke-Captured([string]$Exe, [string[]]$Arguments, [string]$WorkingDirectory) {
    # No shell command strings; subprocess text is kept in memory, not printed or saved.
    $resolved = @(Get-Command -Name $Exe -CommandType Application -ErrorAction Stop)[0]
    $oldPreference = $ErrorActionPreference
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = $null
        $lines = @(& $resolved.Source @Arguments 2>&1)
        $code = $global:LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
        Pop-Location
    }
    if ($null -eq $code) { throw 'BLOCKED: executable did not return a process exit code.' }
    # Native stdout arrives as [string]; native stderr merged via 2>&1 arrives as [ErrorRecord].
    # Keep them apart so a stderr warning can never be spliced into NUL-delimited stdout records.
    $stdout = @($lines | Where-Object { $_ -is [string] })
    $stderr = @($lines | Where-Object { $_ -is [Management.Automation.ErrorRecord] } | ForEach-Object { $_.ToString() })
    return [pscustomobject]@{ Code = [int]$code; Text = ($stdout -join "`n"); ErrorText = ($stderr -join "`n") }
}

function Invoke-GitRead([string]$Root, [string[]]$Arguments) {
    if (-not $Arguments.Count -or $Arguments[0] -notin @('show','diff','ls-files','ls-tree','rev-parse') -or
        @($Arguments | Where-Object { $_ -match '^--(output|ext-diff|textconv)(=|$)' }).Count) {
        throw 'BLOCKED: only fixed read-only Git inspection is allowed.'
    }
    # No config overrides or ownership-check bypass. Failure must remain BLOCKED.
    $result = Invoke-Captured 'git' (@('--no-optional-locks','--no-pager','-C', $Root) + $Arguments) $Root
    if ($result.Code -ne 0) { throw 'BLOCKED: read-only Git query failed; operator must check repository access/base SHA.' }
    return $result.Text
}

function Get-GitExclusions {
    return @('--', '.', ':(exclude)infra/testbed/secrets/**', ':(exclude)**/google-oauth.local.env',
        ':(exclude)docs/workmd/**', ':(exclude)**/.env', ':(exclude)**/.env.*')
}

function Get-ChangedSourcePaths([string]$Root, [string]$BaseCommit) {
    if ($BaseCommit -notmatch '^[0-9a-fA-F]{40}$') { throw 'BLOCKED: use an operator-approved full 40-character base commit SHA.' }
    [void](Invoke-GitRead $Root @('show', '--no-patch', '--format=%H', $BaseCommit))
    $diff = Invoke-GitRead $Root (@('diff', '--name-only', '-z', '--no-ext-diff', '--no-textconv', '--no-renames', $BaseCommit) + (Get-GitExclusions))
    $untracked = Invoke-GitRead $Root (@('ls-files', '-z', '--others', '--exclude-standard') + (Get-GitExclusions))
    return @((($diff + "`0" + $untracked) -split "`0") | Where-Object { $_ } |
        ForEach-Object { Assert-StructurallySafePath $_ } |
        Where-Object { -not (Test-ProtectedRelativePath $_) } |
        Sort-Object -Unique)
}

function Get-RequiredGates([string[]]$Paths) {
    $gates = [Collections.Generic.HashSet[string]]::new()
    foreach ($id in @('00','01','04','10')) { [void]$gates.Add($id) }
    foreach ($p in $Paths) {
        if ($p -match '^frontend/') {
            foreach ($id in @('02','07','08')) { [void]$gates.Add($id) }
            if ($p -match '(?i)auth|api/|rag|sources|UserAccess|SharedMaterials|package|config') {
                foreach ($id in @('03','05','06','09')) { [void]$gates.Add($id) }
            }
        } elseif ($p -match '^backend/') {
            foreach ($id in @('03','05','06','09')) { [void]$gates.Add($id) }
            if ($p -match '/api/|common/security|identity/|build\.gradle') {
                foreach ($id in @('02','07','08')) { [void]$gates.Add($id) }
            }
        } elseif ($p -match '^ai-service/') {
            foreach ($id in @('03','05','06','09')) { [void]$gates.Add($id) }
        } elseif ($p -match '^(docs/(plan|runbooks)/|README\.md$|LICENSE$)') {
            # Documentation-only execution notes do not trigger all product tests.
        } else {
            # Specs, guardrails, scripts, build/CI config and unknown paths widen, never narrow.
            foreach ($id in @('02','03','05','06','07','08','09')) { [void]$gates.Add($id) }
        }
    }
    return @($gates | Sort-Object)
}

function Get-SourceDigest([string]$Root) {
    $inventory = Invoke-GitRead $Root (@('ls-files', '-z', '--cached', '--others', '--exclude-standard') + (Get-GitExclusions))
    $records = [Collections.Generic.List[string]]::new()
    foreach ($raw in @($inventory -split "`0" | Where-Object { $_ } | Sort-Object -Unique)) {
        $p = Assert-StructurallySafePath $raw
        # Excluded by category before any content is read or hashed - never
        # made an exception "safe by name" (e.g. .env.example, secrets/README.md
        # still never reach Get-FileHash below, exactly like a real secret would).
        if (Test-ProtectedRelativePath $p) { continue }
        $full = Resolve-SafeFile $Root $p
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            $records.Add($p + ':' + (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash)
        } else { $records.Add($p + ':MISSING') }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($records -join "`n")
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Assert-GuideText([string]$Text) {
    $lines = @($Text.TrimEnd("`r", "`n") -split '\r?\n')
    if ($lines.Count -gt 15 -or $lines.Count -eq 0 -or $Text -notmatch 'GATE_MATRIX\.md' -or $Text -notmatch 'verify-change\.ps1') {
        throw 'FAIL: guide must be at most 15 lines and reference the matrix and verification entry point.'
    }
}

function Assert-MigrationSet([string[]]$Baseline, [string[]]$Changed, [string[]]$Current) {
    foreach ($old in $Baseline) {
        if ($Changed -ccontains $old -or $Current -cnotcontains $old) {
            throw 'FAIL: a baseline migration was modified, deleted, or renamed.'
        }
    }
    $seen = [Collections.Generic.HashSet[string]]::new()
    [decimal]$baselineMax = 0
    foreach ($old in $Baseline) {
        if ($old -notmatch '/V(\d+)__[^/]+\.sql$') { throw 'BLOCKED: unrecognized baseline migration naming.' }
        $baselineMax = [Math]::Max($baselineMax, [decimal]$Matches[1])
    }
    foreach ($p in $Current) {
        if ($p -notmatch '/V(\d+)__[^/]+\.sql$') { throw 'FAIL: migration naming must be V<number>__description.sql.' }
        $v = ([decimal]$Matches[1]).ToString([Globalization.CultureInfo]::InvariantCulture)
        if (-not $seen.Add($v)) { throw 'FAIL: duplicate migration version.' }
        if ($Baseline -cnotcontains $p -and [decimal]$v -le $baselineMax) { throw 'FAIL: new migration must follow baseline versions.' }
    }
}

function Get-JavaImportViolations([string]$Relative, [string]$Source) {
    # Narrow source-import check, not a claim of complete architectural enforcement.
    $clean = [regex]::Replace($Source, '(?s)/\*.*?\*/|(?m)//[^\r\n]*', '')
    $bad = [Collections.Generic.List[string]]::new()
    foreach ($m in [regex]::Matches($clean, '(?m)^\s*import\s+(?:static\s+)?([^;]+);')) {
        $import = $m.Groups[1].Value
        if ($Relative -match '/api/.*Controller\.java$' -and $import -match '\.persistence\.repository\.') {
            $bad.Add('CONTROLLER_REPOSITORY_IMPORT')
        }
        if ($Relative -match '/domain/|/port/' -and $import -match '^(jakarta\.persistence\.|org\.springframework\.web\.|com\.google\.)') {
            $bad.Add('DOMAIN_EXTERNAL_IMPORT')
        }
    }
    return @($bad)
}

function Read-JUnitSummary([string]$Directory, [datetime]$NotBefore) {
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($file in @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File -ErrorAction Stop)) {
        if ($file.LastWriteTimeUtc -lt $NotBefore.ToUniversalTime().AddSeconds(-2)) { continue }
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            while ($reader.Read()) {
                if ($reader.NodeType -eq [Xml.XmlNodeType]::Element -and $reader.Name -eq 'testsuite') {
                    $rows.Add([pscustomobject]@{ Name=$reader.GetAttribute('name'); Tests=[int]$reader.GetAttribute('tests');
                        Failures=[int]$reader.GetAttribute('failures'); Errors=[int]$reader.GetAttribute('errors');
                        Skipped=[int]$reader.GetAttribute('skipped') })
                    break
                }
            }
        } finally { $reader.Dispose() }
    }
    if ($rows.Count -eq 0) { throw 'BLOCKED: no fresh JUnit suites from this invocation.' }
    return @($rows)
}

function Assert-JUnitSummary([object[]]$Rows, [string[]]$RequiredClasses) {
    if ($Rows.Count -eq 0) { throw 'BLOCKED: empty test report.' }
    foreach ($row in $Rows) {
        if ($row.Tests -le 0 -or $row.Failures -gt 0 -or $row.Errors -gt 0 -or $row.Skipped -gt 0) {
            throw 'FAIL: failed, skipped or empty backend suite.'
        }
    }
    foreach ($required in $RequiredClasses) {
        if ($Rows.Name -cnotcontains $required) { throw 'BLOCKED: required backend suite was not executed.' }
    }
}

function Assert-AdapterReceipt([object]$Receipt, [string]$RunId, [string]$Digest, [string[]]$RequiredCases) {
    if ($Receipt.runId -cne $RunId -or $Receipt.sourceDigest -cne $Digest) { throw 'FAIL: stale adapter receipt.' }
    if (@($Receipt.cases).Count -eq 0) { throw 'BLOCKED: adapter returned zero cases.' }
    foreach ($case in $Receipt.cases) {
        if ($case.status -cne 'PASS') { throw 'FAIL: browser case failed, skipped, or was not run.' }
    }
    foreach ($name in $RequiredCases) {
        if (@($Receipt.cases | Where-Object { $_.id -ceq $name -and $_.status -ceq 'PASS' }).Count -ne 1) {
            throw 'BLOCKED: required browser case is missing or duplicated.'
        }
    }
}

Export-ModuleMember -Function *
