#requires -Version 7.0
<#
.SYNOPSIS
  Offline/synthetic tests for the M17 indexing activation gate.

.NOTES
  Does not execute start-testbed.ps1, Docker, PostgreSQL, Kafka, AI/Ollama,
  Google, or any secret resolver. The launcher is parsed as PowerShell AST.
#>

param([string]$TestScriptRoot = $PSScriptRoot)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command New-IsolatedEnvironment -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_lib.ps1')
}
if (-not (Get-Command Assert-M17IndexingParameters -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_indexing-activation.ps1')
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

function Assert-Throws([scriptblock]$Action, [string]$Description) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Description
}

function New-PassingSnapshot {
    [PSCustomObject]@{
        dbIdentityOk = $true
        databaseSystemIdentifier = '7432109876543210000'
        runtimeBackendSessions = 0L
        sharedDocumentMatches = 1L
        sharedDocumentActive = $true
        sharedVersionReady = $true
        sharedIndexStatus = 'PENDING'
        publisherConnectionReady = $true
        sharedActiveShareCount = 1L
        sharedScopeOk = $true
        # Matches the currently observed approved contract: ALL_AUTHENTICATED
        # is the default audience and requires zero named recipients (never
        # inferred from recipient count - CORE_SPEC's audience/action model).
        sharedAudience = 'ALL_AUTHENTICATED'
        sharedAudienceRecipientsOk = $true
        sharedActionsOk = $true
        sharedRecipientCount = 0L
        internalLocalPolicyOk = $true
        privateDocumentMatches = 1L
        privateDocumentActive = $true
        privateActiveShareCount = 0L
        privateEmbeddingCount = 0L
        otherFetchEligibleCount = 0L
        unknownRelevantOutboxCount = 0L
        otherFetchEligibleOutboxCount = 0L
        publishingOutboxCount = 0L
        fixtureRelevantLiveCount = 1L
        contentFreeLiveCount = 7L
        totalEmbeddingCount = 0L
        outboxHighWatermark = 292L
    }
}

function Copy-Snapshot($Snapshot) {
    return ($Snapshot | ConvertTo-Json | ConvertFrom-Json)
}

Write-Step '1. Baseline remains explicitly OFF and needs no activation settings'
$baselineOk = $true
try {
    Assert-M17IndexingParameters -Enabled $false -PrepareNewTopics $false
} catch { $baselineOk = $false }
$baseline = Get-M17IndexingBackendOverrides -Enabled $false
Assert-True $baselineOk 'plain mode accepts no AI/Kafka readiness parameters'
Assert-True ($baseline['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'false') 'plain mode explicitly disables the outbox publisher'
Assert-True ($baseline['SDV_RAG_INDEX_CONSUMER_ENABLED'] -eq 'false') 'plain mode explicitly disables the index consumer'
Assert-True ($baseline['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') 'plain mode explicitly disables Assistant'
Assert-True (-not $baseline.ContainsKey('SPRING_KAFKA_BOOTSTRAP_SERVERS')) 'plain mode does not require or inject Kafka readiness'
Assert-Throws { Assert-M17IndexingParameters -Enabled $false -Topic 'sdv.testbed.m17.indexing.v1' -PrepareNewTopics $false } `
    'activation-only settings cannot be smuggled into plain mode'

Write-Step '2. Enabled allowlist survives isolation exactly; inherited application settings do not'
$valid = @{
    Enabled = $true
    ActivationMode = 'FirstRun'
    KafkaBootstrapServers = '127.0.0.1:9092'
    Topic = 'sdv.testbed.m17.indexing.v1'
    GroupId = 'sdv-testbed-m17-indexing-v1'
    AiServiceUrl = 'http://127.0.0.1:8000'
    IndexHmacState = 'NewEmptyIndex'
    PrepareNewTopics = $true
}
$validOk = $true
try { Assert-M17IndexingParameters @valid } catch { $validOk = $false }
Assert-True $validOk 'the exact controlled first-run settings are accepted'
$enabled = Get-M17IndexingBackendOverrides -Enabled $true `
    -KafkaBootstrapServers $valid.KafkaBootstrapServers -Topic $valid.Topic `
    -GroupId $valid.GroupId -AiServiceUrl $valid.AiServiceUrl
$expectedEnabled = @{
    SDV_OUTBOX_PUBLISHER_ENABLED = 'true'
    SDV_RAG_INDEX_CONSUMER_ENABLED = 'true'
    SPRING_KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
    SDV_OUTBOX_PUBLISHER_TOPIC = 'sdv.testbed.m17.indexing.v1'
    SDV_RAG_INDEX_CONSUMER_TOPIC = 'sdv.testbed.m17.indexing.v1'
    SDV_RAG_INDEX_CONSUMER_GROUP_ID = 'sdv-testbed-m17-indexing-v1'
    AI_SERVICE_URL = 'http://127.0.0.1:8000'
    SDV_RAG_ASSISTANT_ENABLED = 'false'
}
Assert-True ($enabled.Count -eq $expectedEnabled.Count) 'enabled mode emits only the exact eight indexing allowlist entries'
foreach ($key in $expectedEnabled.Keys) {
    Assert-True ($enabled[$key] -eq $expectedEnabled[$key]) "enabled override $key has the checked value"
}
Assert-True ($enabled['SDV_OUTBOX_PUBLISHER_TOPIC'] -eq $enabled['SDV_RAG_INDEX_CONSUMER_TOPIC']) `
    'publisher and consumer topics cannot diverge'

$env:SDV_ATTACK_SENTINEL = 'SHOULD-NOT-INHERIT'
$env:SPRING_FLYWAY_URL = 'jdbc:postgresql://remote.example:5432/prod'
$env:SPRING_APPLICATION_JSON = '{"unsafe":true}'
$env:JAVA_TOOL_OPTIONS = '-Dunsafe=true'
try {
    $isolated = New-IsolatedEnvironment -Overrides $enabled
    Assert-True (-not $isolated.ContainsKey('SDV_ATTACK_SENTINEL')) 'arbitrary inherited SDV settings remain stripped'
    Assert-True (-not $isolated.ContainsKey('SPRING_FLYWAY_URL')) 'inherited Flyway target remains stripped'
    Assert-True (-not $isolated.ContainsKey('SPRING_APPLICATION_JSON')) 'inherited Spring JSON remains stripped'
    Assert-True (-not $isolated.ContainsKey('JAVA_TOOL_OPTIONS')) 'inherited JVM options remain stripped'
    Assert-True ($isolated['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') 'Assistant stays OFF after environment isolation'
} finally {
    Remove-Item Env:\SDV_ATTACK_SENTINEL, Env:\SPRING_FLYWAY_URL, Env:\SPRING_APPLICATION_JSON, Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

Write-Step '3. Remote/development/mismatched/injection-shaped settings fail closed'
$invalidCases = @(
    @{ Name = 'remote Kafka'; Change = @{ KafkaBootstrapServers = 'broker.example:9092' } },
    @{ Name = 'development topic'; Change = @{ Topic = 'sdv.source.events' } },
    @{ Name = 'DLT as main topic'; Change = @{ Topic = 'sdv.testbed.m17.indexing.DLT' } },
    @{ Name = 'development group'; Change = @{ GroupId = 'sdv-rag-index-orchestrator' } },
    @{ Name = 'remote AI'; Change = @{ AiServiceUrl = 'http://example.com:8000' } },
    @{ Name = 'topic injection'; Change = @{ Topic = 'sdv.testbed.m17.x;whoami' } },
    @{ Name = 'group injection'; Change = @{ GroupId = 'sdv-testbed-m17-x $(whoami)' } },
    @{ Name = 'resume creates topic'; Change = @{ ActivationMode = 'Resume'; PrepareNewTopics = $true } }
)
foreach ($case in $invalidCases) {
    $parameters = @{} + $valid
    foreach ($key in $case.Change.Keys) { $parameters[$key] = $case.Change[$key] }
    Assert-Throws { Assert-M17IndexingParameters @parameters } "$($case.Name) is rejected"
}
Assert-Throws { Assert-TestbedDatabaseTarget -DatasourceUrl 'jdbc:postgresql://localhost:5432/sdv' -FlywayUser 'sdv_user' -ExpectedPort 15432 } `
    'the development database port 5432 is rejected by the existing target guard'

Write-Step '4. Synthetic content-free readiness scope and bounded failures'
$passing = New-PassingSnapshot
$passOk = $true
try { Assert-M17IndexingReadinessSnapshot -Snapshot $passing -ActivationMode FirstRun -IndexHmacState NewEmptyIndex } catch { $passOk = $false }
Assert-True $passOk 'ALL_AUTHENTICATED with zero recipients and VIEW passes (the current approved default, not a missing-recipient failure)'

$allAuthenticatedWithDownload = Copy-Snapshot $passing
# sharedActionsOk is the SQL layer's own pre-computed verdict for the exact
# stored allowed_actions set (VIEW required, DOWNLOAD optional) - this offline
# suite does not run real SQL/Postgres (see file header), so it exercises the
# boolean contract this snapshot field must satisfy, not the SQL text itself.
# VIEW-only and VIEW+DOWNLOAD both collapse to sharedActionsOk=true here by
# construction; the SQL's own VIEW/DOWNLOAD/unknown/duplicate handling was
# reviewed by hand (see docs/runbooks/M11_INDEXING_ACTIVATION.md) and is not
# independently re-provable without a real database, which this task does not
# start.
$allAuthenticatedWithDownload.sharedActionsOk = $true
$withDownloadOk = $true
try { Assert-M17IndexingReadinessSnapshot -Snapshot $allAuthenticatedWithDownload -ActivationMode FirstRun -IndexHmacState NewEmptyIndex } catch { $withDownloadOk = $false }
Assert-True $withDownloadOk 'ALL_AUTHENTICATED with zero recipients and VIEW+DOWNLOAD (DOWNLOAD is optional, additive) passes'

$namedValid = Copy-Snapshot $passing
$namedValid.sharedAudience = 'NAMED_USERS'
$namedValid.sharedAudienceRecipientsOk = $true
$namedValid.sharedRecipientCount = 1L
$namedValidOk = $true
try { Assert-M17IndexingReadinessSnapshot -Snapshot $namedValid -ActivationMode FirstRun -IndexHmacState NewEmptyIndex } catch { $namedValidOk = $false }
Assert-True $namedValidOk 'the existing NAMED_USERS path (at least one valid recipient) keeps passing'

$failureMutations = @(
    @{ Name = 'wrong database identity'; Field = 'dbIdentityOk'; Value = $false },
    @{ Name = 'malformed database system identity'; Field = 'databaseSystemIdentifier'; Value = 'unknown' },
    @{ Name = 'another backend session'; Field = 'runtimeBackendSessions'; Value = 1L },
    @{ Name = 'missing fixture'; Field = 'sharedDocumentMatches'; Value = 0L },
    @{ Name = 'ambiguous fixture'; Field = 'sharedDocumentMatches'; Value = 2L },
    @{ Name = 'invalid share scope'; Field = 'sharedScopeOk'; Value = $false },
    @{ Name = 'unrecognized audience value'; Field = 'sharedAudience'; Value = 'EVERYONE' },
    @{ Name = 'NAMED_USERS audience with zero recipients (audience/recipient mismatch)'; Field = 'sharedAudienceRecipientsOk'; Value = $false },
    @{ Name = 'download-only, unknown, or duplicate action grant'; Field = 'sharedActionsOk'; Value = $false },
    @{ Name = 'missing source version'; Field = 'sharedVersionReady'; Value = $false },
    @{ Name = 'inactive private fixture'; Field = 'privateDocumentActive'; Value = $false },
    @{ Name = 'private share'; Field = 'privateActiveShareCount'; Value = 1L },
    @{ Name = 'private embedding'; Field = 'privateEmbeddingCount'; Value = 1L },
    @{ Name = 'another eligible shared document'; Field = 'otherFetchEligibleCount'; Value = 1L },
    @{ Name = 'unknown relevant backlog'; Field = 'unknownRelevantOutboxCount'; Value = 1L },
    @{ Name = 'another eligible backlog target'; Field = 'otherFetchEligibleOutboxCount'; Value = 1L },
    @{ Name = 'in-flight publisher claim'; Field = 'publishingOutboxCount'; Value = 1L },
    @{ Name = 'missing fixture work'; Field = 'fixtureRelevantLiveCount'; Value = 0L },
    @{ Name = 'failed document status'; Field = 'sharedIndexStatus'; Value = 'FAILED' },
    @{ Name = 'malformed numeric result'; Field = 'sharedDocumentMatches'; Value = 'query-error' },
    @{ Name = 'unknown HMAC continuity with existing embeddings'; Field = 'totalEmbeddingCount'; Value = 1L }
)
$namedInvalidMutations = @(
    @{ Name = 'NAMED_USERS audience with zero recipients on the named-path snapshot'; Field = 'sharedAudienceRecipientsOk'; Value = $false }
)
foreach ($mutation in $namedInvalidMutations) {
    $snapshot = Copy-Snapshot $namedValid
    $snapshot.($mutation.Field) = $mutation.Value
    Assert-Throws {
        Assert-M17IndexingReadinessSnapshot -Snapshot $snapshot -ActivationMode FirstRun -IndexHmacState NewEmptyIndex
    } "$($mutation.Name) blocks activation"
}
foreach ($mutation in $failureMutations) {
    $snapshot = Copy-Snapshot $passing
    $snapshot.($mutation.Field) = $mutation.Value
    Assert-Throws {
        Assert-M17IndexingReadinessSnapshot -Snapshot $snapshot -ActivationMode FirstRun -IndexHmacState NewEmptyIndex
    } "$($mutation.Name) blocks activation"
}

$wouldLaunch = $false
try {
    $blocked = Copy-Snapshot $passing
    $blocked.unknownRelevantOutboxCount = 1L
    Assert-M17IndexingReadinessSnapshot -Snapshot $blocked -ActivationMode FirstRun -IndexHmacState NewEmptyIndex
    $wouldLaunch = $true
} catch { }
Assert-True (-not $wouldLaunch) 'a readiness failure prevents the would-be enabled backend launch path'

Write-Step '5. First-run/resume provenance is exact and cannot silently drift'
$provenance = [PSCustomObject]@{
    schemaVersion = 1
    kafkaBootstrapServers = '127.0.0.1:9092'
    topic = 'sdv.testbed.m17.indexing.v1'
    dltTopic = 'sdv.testbed.m17.indexing.v1.DLT'
    groupId = 'sdv-testbed-m17-indexing-v1'
    outboxHighWatermark = 292L
    databaseSystemIdentifier = '7432109876543210000'
}
$provenanceOk = $true
try {
    Assert-M17ResumeProvenance -Provenance $provenance -KafkaBootstrapServers '127.0.0.1:9092' `
        -Topic 'sdv.testbed.m17.indexing.v1' -GroupId 'sdv-testbed-m17-indexing-v1'
} catch { $provenanceOk = $false }
Assert-True $provenanceOk 'resume accepts the exact established topic/group/watermark provenance'
$drifted = [PSCustomObject]@{
    schemaVersion = 1
    kafkaBootstrapServers = '127.0.0.1:9092'
    topic = 'sdv.testbed.m17.other'
    dltTopic = 'sdv.testbed.m17.indexing.v1.DLT'
    groupId = 'sdv-testbed-m17-indexing-v1'
    outboxHighWatermark = 292L
    databaseSystemIdentifier = '7432109876543210000'
}
$driftAction = {
    Assert-M17ResumeProvenance -Provenance $drifted -KafkaBootstrapServers '127.0.0.1:9092' `
        -Topic 'sdv.testbed.m17.indexing.v1' -GroupId 'sdv-testbed-m17-indexing-v1'
}.GetNewClosure()
Assert-Throws $driftAction 'unknown or mismatched topic provenance blocks resume'
$databaseDriftAction = {
    Assert-M17ResumeProvenance -Provenance $provenance -KafkaBootstrapServers '127.0.0.1:9092' `
        -Topic 'sdv.testbed.m17.indexing.v1' -GroupId 'sdv-testbed-m17-indexing-v1' `
        -DatabaseSystemIdentifier '9999999999999999999'
}.GetNewClosure()
Assert-Throws $databaseDriftAction 'a recreated/different testbed database blocks topic-history resume'

Write-Step '6. Failure output is allowlisted and excludes raw sentinel output/arguments'
$sentinel = 'SENTINEL-' + [Guid]::NewGuid().ToString('N')
$failureText = ''
try {
    Invoke-M17BoundedNativeCapture -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-NonInteractive', '-Command', "[Console]::Error.WriteLine('$sentinel'); exit 7"
    ) -TimeoutSeconds 5 -FailureLabel 'Synthetic native check' | Out-Null
} catch {
    $failureText = $_.Exception.Message
}
Assert-True (-not $failureText.Contains($sentinel)) 'raw native stderr and injection-shaped arguments are not copied into failure output'
Assert-True ($failureText -eq 'Synthetic native check failed; activation is blocked.') 'native failures return only the fixed allowlisted failure text'
Assert-True (-not (($enabled | Out-String).Contains('SHOULD-NOT-INHERIT'))) 'override reporting cannot contain a complete inherited environment'

Write-Step '7. Launcher syntax/AST and ordering (parse only; top level never executes)'
$launcherPath = Join-Path $TestScriptRoot 'start-testbed.ps1'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($launcherPath, [ref]$tokens, [ref]$parseErrors)
Assert-True ($parseErrors.Count -eq 0) 'start-testbed.ps1 parses without PowerShell syntax errors'
$commands = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
$gate = $commands | Where-Object { $_.GetCommandName() -eq 'Invoke-M17IndexingActivationPreflight' } | Select-Object -First 1
$backendStarts = @($commands | Where-Object {
    $_.GetCommandName() -eq 'Start-TrackedProcess' -and $_.Extent.Text -match "-Name\s+'backend'"
})
Assert-True ($null -ne $gate) 'launcher contains the explicit indexing preflight gate'
Assert-True ($backendStarts.Count -eq 1) 'launcher has one tracked backend start site'
Assert-True ($null -ne $gate -and $backendStarts.Count -eq 1 -and $gate.Extent.StartOffset -lt $backendStarts[0].Extent.StartOffset) `
    'the indexing gate occurs before the enabled backend can be spawned'
$launcherText = Get-Content -Raw -Encoding UTF8 -LiteralPath $launcherPath
foreach ($requiredKey in $expectedEnabled.Keys) {
    Assert-True ($launcherText.Contains('Get-M17IndexingBackendOverrides') -and
            (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $TestScriptRoot '_indexing-activation.ps1')).Contains($requiredKey)) `
        "launcher/helper source contains required allowlist key $requiredKey"
}
Assert-True ($launcherText -match 'existing baseline/testbed process is already running') `
    'an already-running baseline reports restart-required instead of indexing-enabled'
$applicationConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath `
    (Join-Path (Split-Path (Split-Path $TestScriptRoot -Parent) -Parent) 'backend/src/main/resources/application.yml')
Assert-True ($applicationConfig -match '(?m)^\s+auto-offset-reset:\s+earliest\s*$') `
    'the isolated backend configuration keeps the consumer reset policy at earliest'

Write-Step '8. Kafka offset parsers reject ambiguous or unsafe native output'
$topic = 'sdv.testbed.m17.indexing.v1'
$group = 'sdv-testbed-m17-indexing-v1'
$topicRows = @(ConvertFrom-M17KafkaTopicOffsetOutput -Output "$topic`:0:2`n$topic`:1:4" -ExpectedTopic $topic)
Assert-True ($topicRows.Count -eq 2 -and $topicRows[0].offset -eq 2L -and $topicRows[1].partition -eq 1) `
    'topic offset parser accepts exact unique partition rows'
Assert-Throws {
    ConvertFrom-M17KafkaTopicOffsetOutput -Output "$topic`:0:2`n$topic`:0:3" -ExpectedTopic $topic
} 'duplicate topic partitions are rejected'
Assert-Throws {
    ConvertFrom-M17KafkaTopicOffsetOutput -Output "sdv.testbed.m17.other:0:2" -ExpectedTopic $topic
} 'wrong topics cannot qualify as resume work'
$offsetSentinel = 'OFFSET-SENTINEL-' + [Guid]::NewGuid().ToString('N')
$offsetFailure = ''
try {
    ConvertFrom-M17KafkaTopicOffsetOutput -Output $offsetSentinel -ExpectedTopic $topic | Out-Null
} catch { $offsetFailure = $_.Exception.Message }
Assert-True (-not $offsetFailure.Contains($offsetSentinel)) 'malformed topic output is not copied into parser errors'

$committedText = @"
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
$group $topic 0 5 7 2 - - -
$group $topic 1 - 5 - - - -
"@
$committedRows = ConvertFrom-M17KafkaCommittedOffsetOutput -Output $committedText `
    -ExpectedTopic $topic -ExpectedGroup $group
Assert-True (-not $committedRows.noCommittedOffsets -and $committedRows.rows.Count -eq 2 -and
        $null -eq $committedRows.rows[1].currentOffset) `
    'committed parser preserves an explicit missing commit instead of inventing zero'
$noCommitRows = ConvertFrom-M17KafkaCommittedOffsetOutput `
    -Output "Consumer group '$group' has no committed offsets." -ExpectedTopic $topic -ExpectedGroup $group
Assert-True ($noCommitRows.noCommittedOffsets -and @($noCommitRows.rows).Count -eq 0) `
    'committed parser recognizes an exact content-free no-offset state'
Assert-Throws {
    ConvertFrom-M17KafkaCommittedOffsetOutput -Output ($committedText + "`n$group $topic 0 6 7 1 - - -") `
        -ExpectedTopic $topic -ExpectedGroup $group
} 'duplicate committed partitions are rejected'
Assert-Throws {
    ConvertFrom-M17KafkaCommittedOffsetOutput `
        -Output ("Consumer group '$group' has no committed offsets.`n$group $topic 0 1 2 1 - - -") `
        -ExpectedTopic $topic -ExpectedGroup $group
} 'no-offset and committed-row output cannot be combined'
Assert-Throws {
    ConvertFrom-M17KafkaCommittedOffsetOutput -Output "wrong-group $topic 0 1 2 1 - - -" `
        -ExpectedTopic $topic -ExpectedGroup $group
} 'wrong committed group is rejected'
Assert-Throws {
    ConvertFrom-M17KafkaCommittedOffsetOutput -Output "$group sdv.testbed.m17.other 0 1 2 1 - - -" `
        -ExpectedTopic $topic -ExpectedGroup $group
} 'wrong committed topic is rejected'
$committedSentinel = 'COMMITTED-SENTINEL-' + [Guid]::NewGuid().ToString('N')
$committedFailure = ''
try {
    ConvertFrom-M17KafkaCommittedOffsetOutput -Output $committedSentinel `
        -ExpectedTopic $topic -ExpectedGroup $group | Out-Null
} catch { $committedFailure = $_.Exception.Message }
Assert-True (-not $committedFailure.Contains($committedSentinel)) `
    'malformed committed output is not copied into parser errors'

Write-Step '9. Partition-aware resume evidence uses retained ranges and earliest only when uncommitted'
$script:realResumeEvidence = ${function:Get-M17KafkaResumeWorkEvidence}
$script:mockGroups = @($group)
$script:mockEarliestRows = @(
    [PSCustomObject]@{ topic = $topic; partition = 0; offset = 2L },
    [PSCustomObject]@{ topic = $topic; partition = 1; offset = 4L }
)
$script:mockLatestRows = @(
    [PSCustomObject]@{ topic = $topic; partition = 0; offset = 7L },
    [PSCustomObject]@{ topic = $topic; partition = 1; offset = 5L }
)
$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @(
        [PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 5L; logEndOffset = 7L },
        [PSCustomObject]@{ topic = $topic; group = $group; partition = 1; currentOffset = 4L; logEndOffset = 5L }
    )
}
$script:mockOffsetFailure = $null
$script:committedOffsetCalls = 0
function Get-M17KafkaGroups { return @($script:mockGroups) }
function Get-M17KafkaTopicOffsets {
    param([string]$Topic, [string]$Point)
    if ($null -ne $script:mockOffsetFailure) { throw $script:mockOffsetFailure }
    if ($Point -eq 'earliest') { return @($script:mockEarliestRows) }
    return @($script:mockLatestRows)
}
function Get-M17KafkaCommittedOffsets {
    param([string]$Topic, [string]$GroupId)
    $script:committedOffsetCalls++
    return $script:mockCommitted
}

$resumeEvidence = & $script:realResumeEvidence -Topic $topic -GroupId $group
Assert-True ($resumeEvidence.hasResumableWork -and $resumeEvidence.remainingCount -eq 3L) `
    'committed positions below retained main-topic ends establish bounded resume work'

$script:mockGroups = @()
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 5L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 8L })
$script:committedOffsetCalls = 0
$noGroupEvidence = & $script:realResumeEvidence -Topic $topic -GroupId $group
Assert-True ($noGroupEvidence.hasResumableWork -and $noGroupEvidence.remainingCount -eq 3L -and
        $noGroupEvidence.offsetSource -eq 'earliest-no-group' -and $script:committedOffsetCalls -eq 0) `
    'a group not yet created uses retained earliest offsets without inventing a committed zero'

$script:mockGroups = @($group)
$script:mockCommitted = [PSCustomObject]@{ noCommittedOffsets = $true; rows = @() }
$noCommitEvidence = & $script:realResumeEvidence -Topic $topic -GroupId $group
Assert-True ($noCommitEvidence.hasResumableWork -and $noCommitEvidence.remainingCount -eq 3L -and
        $noCommitEvidence.offsetSource -eq 'earliest-no-commit') `
    'an established group with explicitly no committed offsets uses configured earliest behavior'

$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @([PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 8L; logEndOffset = 8L })
}
$consumedEvidence = & $script:realResumeEvidence -Topic $topic -GroupId $group
Assert-True (-not $consumedEvidence.hasResumableWork -and $consumedEvidence.remainingCount -eq 0L) `
    'a fully consumed main topic does not qualify as resume work'

$script:mockGroups = @()
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 5L })
$emptyEvidence = & $script:realResumeEvidence -Topic $topic -GroupId $group
Assert-True (-not $emptyEvidence.hasResumableWork) 'an empty retained main topic remains blocked even if DLT may contain records'

$script:mockEarliestRows = @(
    [PSCustomObject]@{ topic = $topic; partition = 0; offset = 0L },
    [PSCustomObject]@{ topic = $topic; partition = 1; offset = 0L }
)
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 1L })
Assert-Throws { & $script:realResumeEvidence -Topic $topic -GroupId $group } `
    'missing latest partition rows fail closed'
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 3L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 2L })
Assert-Throws { & $script:realResumeEvidence -Topic $topic -GroupId $group } `
    'impossible retained offset ranges fail closed'
$script:mockGroups = @($group)
$script:mockEarliestRows = @(
    [PSCustomObject]@{ topic = $topic; partition = 0; offset = 0L },
    [PSCustomObject]@{ topic = $topic; partition = 1; offset = 0L }
)
$script:mockLatestRows = @(
    [PSCustomObject]@{ topic = $topic; partition = 0; offset = 2L },
    [PSCustomObject]@{ topic = $topic; partition = 1; offset = 2L }
)
$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @([PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 1L; logEndOffset = 2L })
}
Assert-Throws { & $script:realResumeEvidence -Topic $topic -GroupId $group } `
    'missing committed partition rows fail closed'
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 2L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 7L })
$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @([PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 8L; logEndOffset = 7L })
}
Assert-Throws { & $script:realResumeEvidence -Topic $topic -GroupId $group } `
    'committed positions outside retained ranges fail closed'
$brokerSentinel = 'BROKER-SENTINEL-' + [Guid]::NewGuid().ToString('N')
$script:mockOffsetFailure = 'Kafka topic offset check failed; activation is blocked.'
$brokerFailure = ''
try { & $script:realResumeEvidence -Topic $topic -GroupId $group | Out-Null } catch { $brokerFailure = $_.Exception.Message }
Assert-True ($brokerFailure -eq 'Kafka topic offset check failed; activation is blocked.' -and
        -not $brokerFailure.Contains($brokerSentinel)) 'broker failures stay fail-closed and content-free'
$script:mockOffsetFailure = $null

Write-Step '10. Full mocked preflight combines DB scope with Kafka resume availability'
$script:mockResumeTopic = $topic
$script:mockResumeGroup = $group
$script:mockResumeProvenance = $provenance
$script:mockResumeSnapshot = Copy-Snapshot (New-PassingSnapshot)
$script:mockTopics = @($topic, "$topic.DLT")
$script:mockActiveMembers = 0L
$script:mockAiFailure = $null
$script:mockDatabaseFailure = $null
$script:resumeEvidenceCallCount = 0
$script:topicCreateCount = 0
$script:provenanceWritten = $false
function Read-M17TopicProvenance {
    param([Parameter(Mandatory)][string]$Path)
    return $script:mockResumeProvenance
}
function Invoke-M17ContentFreeDatabaseSnapshot {
    param($Paths, [string]$ActivationMode, [long]$OutboxHighWatermark)
    if ($null -ne $script:mockDatabaseFailure) { throw $script:mockDatabaseFailure }
    return $script:mockResumeSnapshot
}
function Assert-M17AiHealth {
    param([string]$AiServiceUrl)
    if ($null -ne $script:mockAiFailure) { throw $script:mockAiFailure }
}
function Get-M17KafkaTopics { return @($script:mockTopics) }
function Get-M17KafkaGroupMemberCount { param([string]$GroupId) return $script:mockActiveMembers }
function Get-M17KafkaResumeWorkEvidence {
    param([string]$Topic, [string]$GroupId)
    $script:resumeEvidenceCallCount++
    return (& $script:realResumeEvidence -Topic $Topic -GroupId $GroupId)
}
function New-M17KafkaTopic {
    param([string]$Topic)
    $script:topicCreateCount++
    $script:mockTopics += $Topic
}
function Get-M17KafkaTopicOffsetTotal { param([string]$Topic, [string]$Point) return 0L }
function Write-M17TopicProvenance {
    param($Path, $KafkaBootstrapServers, $Topic, $GroupId, [long]$OutboxHighWatermark, $DatabaseSystemIdentifier)
    $script:provenanceWritten = $true
}

function Invoke-MockedResumePreflight {
    Invoke-M17IndexingActivationPreflight -Paths ([PSCustomObject]@{ StateDir = 'C:\synthetic-state' }) `
        -ActivationMode Resume -KafkaBootstrapServers '127.0.0.1:9092' -Topic $script:mockResumeTopic `
        -GroupId $script:mockResumeGroup -AiServiceUrl 'http://127.0.0.1:8000' `
        -IndexHmacState ConfirmedSameKey | Out-Null
}

$script:mockGroups = @($group)
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 2L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 7L })
$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @([PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 5L; logEndOffset = 7L })
}
foreach ($status in @('PENDING', 'STALE')) {
    $script:mockResumeSnapshot = Copy-Snapshot (New-PassingSnapshot)
    $script:mockResumeSnapshot.sharedIndexStatus = $status
    $script:mockResumeSnapshot.fixtureRelevantLiveCount = 0L
    $script:mockResumeSnapshot.totalEmbeddingCount = 3L
    $passed = $true
    try { Invoke-MockedResumePreflight } catch { $passed = $false }
    Assert-True $passed `
        "full mocked preflight permits $status after publisher completion when main-topic work remains"
}

$script:mockGroups = @()
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 4L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 6L })
$script:mockResumeSnapshot.sharedIndexStatus = 'PENDING'
$noGroupPreflight = $true
try { Invoke-MockedResumePreflight } catch { $noGroupPreflight = $false }
Assert-True $noGroupPreflight 'full mocked preflight accepts retained work for a not-yet-created earliest group'

$script:mockGroups = @($group)
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 0L })
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 0L })
$script:mockCommitted = [PSCustomObject]@{ noCommittedOffsets = $true; rows = @() }
Assert-Throws { Invoke-MockedResumePreflight } 'no outbox work plus an empty main topic blocks full preflight'
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 3L })
$script:mockCommitted = [PSCustomObject]@{
    noCommittedOffsets = $false
    rows = @([PSCustomObject]@{ topic = $topic; group = $group; partition = 0; currentOffset = 3L; logEndOffset = 3L })
}
Assert-Throws { Invoke-MockedResumePreflight } 'no outbox work plus a fully consumed main topic blocks full preflight'

$script:resumeEvidenceCallCount = 0
$script:mockResumeSnapshot.fixtureRelevantLiveCount = 1L
$pendingOutboxPassed = $true
try { Invoke-MockedResumePreflight } catch { $pendingOutboxPassed = $false }
Assert-True ($pendingOutboxPassed -and $script:resumeEvidenceCallCount -eq 0) `
    'a valid pending outbox path resumes without requiring existing topic backlog'
$script:resumeEvidenceCallCount = 0
$script:mockResumeSnapshot.sharedIndexStatus = 'INDEXED'
$script:mockResumeSnapshot.fixtureRelevantLiveCount = 0L
$indexedResumePassed = $true
try { Invoke-MockedResumePreflight } catch { $indexedResumePassed = $false }
Assert-True ($indexedResumePassed -and $script:resumeEvidenceCallCount -eq 0) `
    'an already INDEXED fixture retains its established resume path'

$script:mockResumeProvenance = $null
Assert-Throws { Invoke-MockedResumePreflight } 'unknown provenance blocks even when retained Kafka work exists'
$script:mockResumeProvenance = $provenance
$script:mockResumeSnapshot = Copy-Snapshot (New-PassingSnapshot)
$script:mockResumeSnapshot.fixtureRelevantLiveCount = 0L
$script:mockResumeSnapshot.totalEmbeddingCount = 3L
$script:mockLatestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 3L })
$script:mockEarliestRows = @([PSCustomObject]@{ topic = $topic; partition = 0; offset = 0L })
$script:mockCommitted = [PSCustomObject]@{ noCommittedOffsets = $true; rows = @() }
foreach ($mutation in @(
    @{ Name = 'different database'; Field = 'databaseSystemIdentifier'; Value = '9999999999999999999' },
    @{ Name = 'other eligible document'; Field = 'otherFetchEligibleCount'; Value = 1L },
    @{ Name = 'unknown relevant backlog'; Field = 'unknownRelevantOutboxCount'; Value = 1L },
    @{ Name = 'private share'; Field = 'privateActiveShareCount'; Value = 1L }
)) {
    $candidate = Copy-Snapshot $script:mockResumeSnapshot
    $candidate.($mutation.Field) = $mutation.Value
    $script:mockResumeSnapshot = $candidate
    Assert-Throws { Invoke-MockedResumePreflight } "$($mutation.Name) blocks even when Kafka has retained work"
    $script:mockResumeSnapshot = Copy-Snapshot (New-PassingSnapshot)
    $script:mockResumeSnapshot.fixtureRelevantLiveCount = 0L
    $script:mockResumeSnapshot.totalEmbeddingCount = 3L
}
$script:mockActiveMembers = 1L
Assert-Throws { Invoke-MockedResumePreflight } 'an active conflicting consumer blocks even when Kafka has retained work'
$script:mockActiveMembers = 0L
$script:mockAiFailure = 'The loopback AI health endpoint did not return a bounded successful response; activation is blocked.'
Assert-Throws { Invoke-MockedResumePreflight } 'AI readiness failure blocks before completion of the combined gate'
$script:mockAiFailure = $null
$script:mockDatabaseFailure = 'Content-free testbed database readiness query failed; activation is blocked.'
Assert-Throws { Invoke-MockedResumePreflight } 'a failed database query blocks the full preflight'
$script:mockDatabaseFailure = $null
$malformedSnapshot = Copy-Snapshot $script:mockResumeSnapshot
$malformedSnapshot.sharedDocumentMatches = 'not-a-count'
$script:mockResumeSnapshot = $malformedSnapshot
Assert-Throws { Invoke-MockedResumePreflight } 'malformed database query output blocks the full preflight'
$script:mockResumeSnapshot = Copy-Snapshot (New-PassingSnapshot)
$script:mockResumeSnapshot.fixtureRelevantLiveCount = 0L
$script:mockResumeSnapshot.totalEmbeddingCount = 3L
$script:mockOffsetFailure = 'Kafka topic offset check timed out; activation is blocked.'
Assert-Throws { Invoke-MockedResumePreflight } 'a broker offset timeout blocks the full preflight'
$script:mockOffsetFailure = $null
Assert-Throws {
    Invoke-M17IndexingActivationPreflight -Paths ([PSCustomObject]@{ StateDir = 'C:\synthetic-state' }) `
        -ActivationMode Resume -KafkaBootstrapServers '127.0.0.1:9092' -Topic $topic -GroupId $group `
        -AiServiceUrl 'http://127.0.0.1:8000' -IndexHmacState NewEmptyIndex | Out-Null
} 'HMAC continuity failure still blocks even when Kafka has retained work'

$wouldLaunchAfterCombinedGate = $false
try {
    $script:mockActiveMembers = 1L
    Invoke-MockedResumePreflight
    $wouldLaunchAfterCombinedGate = $true
} catch { }
Assert-True (-not $wouldLaunchAfterCombinedGate) 'combined-gate failure cannot reach the would-be backend start path'
$script:mockActiveMembers = 0L

$script:mockResumeProvenance = $null
$script:mockResumeSnapshot = New-PassingSnapshot
$script:mockTopics = @()
$script:mockGroups = @()
$script:topicCreateCount = 0
$script:provenanceWritten = $false
$firstRunPassed = $true
try {
    Invoke-M17IndexingActivationPreflight -Paths ([PSCustomObject]@{ StateDir = 'C:\synthetic-state' }) `
        -ActivationMode FirstRun -KafkaBootstrapServers '127.0.0.1:9092' -Topic $topic -GroupId $group `
        -AiServiceUrl 'http://127.0.0.1:8000' -IndexHmacState NewEmptyIndex -PrepareNewTopics | Out-Null
} catch { $firstRunPassed = $false }
Assert-True ($firstRunPassed -and $script:topicCreateCount -eq 2 -and $script:provenanceWritten) `
    'FirstRun still requires and verifies explicit empty topic-pair preparation'

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All M17 indexing activation tests passed.' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) M17 indexing activation test(s) FAILED." -ForegroundColor Red
exit 1
