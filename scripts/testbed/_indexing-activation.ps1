#requires -Version 7.0
<#
.SYNOPSIS
  Non-secret, fail-closed helpers for the explicit M17 testbed indexing gate.

.NOTES
  This file has no top-level service calls. It may be dot-sourced by focused
  synthetic tests. The real checks run only when start-testbed.ps1 explicitly
  calls Invoke-IndexingActivationPreflight after the isolated testbed database
  is available and before the backend process is spawned.
#>

Set-StrictMode -Version Latest

$script:M17SharedName = 'SDV_M17_SHARED.txt'
$script:M17PrivateName = 'SDV_M17_PRIVATE.txt'
$script:M17SourceId = 2L
$script:M17KafkaContainer = 'sdv-kafka'
$script:M17KafkaContainerBootstrap = 'localhost:9092'
$script:M17IndexConsumerOffsetReset = 'earliest'
$script:M17ProvenanceFileName = 'm17-indexing-topic.json'

function Assert-M17IndexingParameters {
    param(
        [Parameter(Mandatory)][bool]$Enabled,
        [string]$ActivationMode,
        [string]$KafkaBootstrapServers,
        [string]$Topic,
        [string]$GroupId,
        [string]$AiServiceUrl,
        [string]$IndexHmacState,
        [bool]$PrepareNewTopics
    )

    $activationValuesPresent = -not [string]::IsNullOrWhiteSpace($ActivationMode) -or
        -not [string]::IsNullOrWhiteSpace($KafkaBootstrapServers) -or
        -not [string]::IsNullOrWhiteSpace($Topic) -or
        -not [string]::IsNullOrWhiteSpace($GroupId) -or
        -not [string]::IsNullOrWhiteSpace($AiServiceUrl) -or
        -not [string]::IsNullOrWhiteSpace($IndexHmacState) -or $PrepareNewTopics

    if (-not $Enabled) {
        if ($activationValuesPresent) {
            throw 'Indexing activation parameters require -EnableIndexing; baseline mode remains explicitly disabled.'
        }
        return
    }

    if ($ActivationMode -notin @('FirstRun', 'Resume')) {
        throw 'IndexingActivationMode must be exactly FirstRun or Resume.'
    }
    if ($KafkaBootstrapServers -ne '127.0.0.1:9092') {
        throw 'KafkaBootstrapServers must be exactly 127.0.0.1:9092 for this controlled testbed activation.'
    }
    if ($AiServiceUrl -ne 'http://127.0.0.1:8000') {
        throw 'AiServiceUrl must be exactly http://127.0.0.1:8000 for this controlled testbed activation.'
    }
    if ($Topic -notmatch '^sdv\.testbed\.m17\.[a-z0-9][a-z0-9._-]{0,180}$' -or $Topic.EndsWith('.DLT')) {
        throw 'IndexingTopic must use the sdv.testbed.m17.* namespace and contain only Kafka-safe data characters.'
    }
    if ($GroupId -notmatch '^sdv-testbed-m17-[a-z0-9][a-z0-9._-]{0,180}$') {
        throw 'IndexingGroupId must use the sdv-testbed-m17-* namespace and contain only Kafka-safe data characters.'
    }
    if ($IndexHmacState -notin @('NewEmptyIndex', 'ConfirmedSameKey')) {
        throw 'IndexHmacState must be NewEmptyIndex or ConfirmedSameKey; no key is generated or inspected here.'
    }
    if ($ActivationMode -eq 'Resume' -and $PrepareNewTopics) {
        throw 'PrepareNewIndexingTopics is valid only for FirstRun; resume must reuse established topic provenance.'
    }
}

function Get-M17IndexingBackendOverrides {
    param(
        [Parameter(Mandatory)][bool]$Enabled,
        [string]$KafkaBootstrapServers,
        [string]$Topic,
        [string]$GroupId,
        [string]$AiServiceUrl
    )

    $overrides = @{
        SDV_OUTBOX_PUBLISHER_ENABLED   = if ($Enabled) { 'true' } else { 'false' }
        SDV_RAG_INDEX_CONSUMER_ENABLED = if ($Enabled) { 'true' } else { 'false' }
        SDV_RAG_ASSISTANT_ENABLED      = 'false'
    }
    if ($Enabled) {
        $overrides['SPRING_KAFKA_BOOTSTRAP_SERVERS'] = $KafkaBootstrapServers
        $overrides['SDV_OUTBOX_PUBLISHER_TOPIC'] = $Topic
        $overrides['SDV_RAG_INDEX_CONSUMER_TOPIC'] = $Topic
        $overrides['SDV_RAG_INDEX_CONSUMER_GROUP_ID'] = $GroupId
        $overrides['AI_SERVICE_URL'] = $AiServiceUrl
    }
    return $overrides
}

function Get-M17IndexingReadinessBlockers {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][ValidateSet('FirstRun', 'Resume')][string]$ActivationMode,
        [Parameter(Mandatory)][ValidateSet('NewEmptyIndex', 'ConfirmedSameKey')][string]$IndexHmacState
    )

    $requiredNumeric = @(
        'runtimeBackendSessions', 'sharedDocumentMatches', 'sharedActiveShareCount', 'sharedRecipientCount',
        'privateDocumentMatches', 'privateActiveShareCount', 'privateEmbeddingCount', 'otherFetchEligibleCount',
        'unknownRelevantOutboxCount', 'otherFetchEligibleOutboxCount', 'publishingOutboxCount',
        'fixtureRelevantLiveCount', 'contentFreeLiveCount', 'totalEmbeddingCount', 'outboxHighWatermark'
    )
    foreach ($name in $requiredNumeric) {
        if ($null -eq $Snapshot.PSObject.Properties[$name] -or $Snapshot.$name -isnot [long] -and
                $Snapshot.$name -isnot [int]) {
            return @('Database readiness output was malformed; activation is blocked.')
        }
        if ([long]$Snapshot.$name -lt 0) {
            return @('Database readiness output was malformed; activation is blocked.')
        }
    }

    $blockers = [System.Collections.Generic.List[string]]::new()
    if ($Snapshot.dbIdentityOk -ne $true) { $blockers.Add('The database identity is not the isolated sdv-testbed database.') }
    if ($Snapshot.databaseSystemIdentifier -isnot [string] -or
            $Snapshot.databaseSystemIdentifier -notmatch '^\d+$') {
        $blockers.Add('The database system identity is missing or malformed.')
    }
    if ([long]$Snapshot.runtimeBackendSessions -ne 0) { $blockers.Add('Another backend is connected to the testbed database; a user-controlled restart is required.') }
    if ([long]$Snapshot.sharedDocumentMatches -ne 1) { $blockers.Add('The approved SHARED fixture is missing or ambiguous under source 2.') }
    if ($Snapshot.sharedDocumentActive -ne $true) { $blockers.Add('The approved SHARED fixture is not ACTIVE.') }
    if ($Snapshot.sharedVersionReady -ne $true) { $blockers.Add('The approved SHARED fixture has no source version to fence.') }
    if ($Snapshot.publisherConnectionReady -ne $true) { $blockers.Add('The publisher connection/token binding is not active and complete.') }
    if ([long]$Snapshot.sharedActiveShareCount -ne 1 -or $Snapshot.sharedScopeOk -ne $true) {
        $blockers.Add('The SHARED fixture must have exactly one active, unblocked INTERNAL share owned by the publisher on source 2.')
    }
    if ($Snapshot.sharedAudience -notin @('ALL_AUTHENTICATED', 'NAMED_USERS')) {
        $blockers.Add('The SHARED fixture audience is missing or not one of the recognized values.')
    } elseif ($Snapshot.sharedAudienceRecipientsOk -ne $true) {
        $blockers.Add('The SHARED fixture audience and its recipients are inconsistent (ALL_AUTHENTICATED must have zero recipients; NAMED_USERS must have at least one non-blank recipient).')
    }
    if ($Snapshot.sharedActionsOk -ne $true) {
        $blockers.Add('The SHARED fixture must grant VIEW (DOWNLOAD is optional) with no unknown or duplicate actions.')
    }
    if ($Snapshot.internalLocalPolicyOk -ne $true) { $blockers.Add('INTERNAL must remain LOCAL_ONLY with external_provider_allowed=false.') }
    if ([long]$Snapshot.privateDocumentMatches -ne 1) { $blockers.Add('The approved PRIVATE fixture is missing or ambiguous under source 2.') }
    if ($Snapshot.privateDocumentActive -ne $true) { $blockers.Add('The approved PRIVATE fixture is not ACTIVE.') }
    if ([long]$Snapshot.privateActiveShareCount -ne 0 -or [long]$Snapshot.privateEmbeddingCount -ne 0) {
        $blockers.Add('PRIVATE must remain unshared and without embeddings.')
    }
    if ([long]$Snapshot.otherFetchEligibleCount -ne 0) { $blockers.Add('Another active shared document could become eligible for content fetching.') }
    if ([long]$Snapshot.unknownRelevantOutboxCount -ne 0) { $blockers.Add('Relevant outbox backlog contains an unknown or unclassifiable document target.') }
    if ([long]$Snapshot.otherFetchEligibleOutboxCount -ne 0) { $blockers.Add('Relevant outbox history/backlog could fetch content for another document.') }
    if ([long]$Snapshot.publishingOutboxCount -ne 0) { $blockers.Add('An outbox row is already PUBLISHING; another publisher or an unresolved claim may be active.') }
    if ($ActivationMode -eq 'FirstRun' -and [long]$Snapshot.fixtureRelevantLiveCount -lt 1) {
        $blockers.Add('No pending/retry INDEX_REQUESTED or SOURCE_DOCUMENT_CHANGED work resolves to SHARED.')
    }
    if ($Snapshot.sharedIndexStatus -notin @('PENDING', 'STALE', 'INDEXED')) {
        $blockers.Add('SHARED index_status is not a safe activation/resume state; diagnose its allowlisted reason first.')
    }
    if ($IndexHmacState -eq 'NewEmptyIndex' -and [long]$Snapshot.totalEmbeddingCount -ne 0) {
        $blockers.Add('Embeddings already exist, so a newly created/session-only HMAC key cannot be assumed safe.')
    }
    return $blockers.ToArray()
}

function Assert-M17IndexingReadinessSnapshot {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$ActivationMode,
        [Parameter(Mandatory)][string]$IndexHmacState
    )
    $blockers = @(Get-M17IndexingReadinessBlockers -Snapshot $Snapshot -ActivationMode $ActivationMode `
            -IndexHmacState $IndexHmacState)
    if ($blockers.Count -gt 0) {
        throw ($blockers -join ' ')
    }
}

function Invoke-M17BoundedNativeCapture {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [int]$TimeoutSeconds = 15,
        [string]$FailureLabel = 'External readiness check'
    )

    $process = [System.Diagnostics.Process]::new()
    try {
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $FilePath
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        foreach ($argument in $ArgumentList) { [void]$psi.ArgumentList.Add($argument) }
        $process.StartInfo = $psi
        if (-not $process.Start()) { throw "$FailureLabel could not start." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill($true) } catch { }
            throw "$FailureLabel timed out; activation is blocked."
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        [void]$stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "$FailureLabel failed; activation is blocked." }
        return [PSCustomObject]@{ ExitCode = $process.ExitCode; Stdout = $stdout }
    } catch {
        if ($_.Exception.Message -like "$FailureLabel*") { throw }
        throw "$FailureLabel failed; activation is blocked."
    } finally {
        $process.Dispose()
    }
}

function Get-M17DockerPath {
    try {
        return (Get-Command docker -ErrorAction Stop).Source
    } catch {
        throw 'Docker CLI is unavailable; activation is blocked.'
    }
}

function Invoke-M17ContentFreeDatabaseSnapshot {
    param(
        [Parameter(Mandatory)]$Paths,
        [Parameter(Mandatory)][ValidateSet('FirstRun', 'Resume')][string]$ActivationMode,
        [Parameter(Mandatory)][long]$OutboxHighWatermark
    )

    if ($OutboxHighWatermark -lt 0) { throw 'Outbox provenance watermark is invalid; activation is blocked.' }
    $historyClause = if ($ActivationMode -eq 'Resume') { " OR o.id > $OutboxHighWatermark" } else { '' }
    $sql = @"
WITH
shared_fixture AS (
    SELECT id, source_id, state, index_status, source_version
    FROM source_documents
    WHERE source_id = $script:M17SourceId AND name = '$script:M17SharedName'
),
private_fixture AS (
    SELECT id, state
    FROM source_documents
    WHERE source_id = $script:M17SourceId AND name = '$script:M17PrivateName'
),
shared_id AS (
    SELECT CASE WHEN COUNT(*) = 1 THEN MIN(id) END AS id FROM shared_fixture
),
shared_share AS (
    SELECT s.id, s.classification, s.allowed_actions, s.admin_blocked, s.audience,
           s.publisher_subject, s.source_id
    FROM document_shares s
    JOIN shared_id f ON f.id = s.document_id
    WHERE s.revoked_at IS NULL
),
shared_share_actions AS (
    SELECT sh.id AS share_id, btrim(a.action) AS action
    FROM shared_share sh
    CROSS JOIN LATERAL unnest(string_to_array(sh.allowed_actions, ',')) AS a(action)
),
shared_share_recipients AS (
    SELECT sh.id AS share_id, r.recipient_subject
    FROM shared_share sh
    JOIN document_share_recipients r ON r.share_id = sh.id
),
eligible_documents AS (
    SELECT d.id
    FROM source_documents d
    JOIN source_connections c ON c.id = d.source_id
    JOIN document_shares s ON s.document_id = d.id
    JOIN ai_usage_policies p ON p.security_level = s.classification
    WHERE d.state = 'ACTIVE' AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE'
      AND s.revoked_at IS NULL AND s.admin_blocked = FALSE AND p.mode <> 'AI_DENIED'
),
considered_outbox AS (
    SELECT o.id, o.event_type, o.status,
           CASE WHEN jsonb_typeof(o.payload) = 'object'
                     AND COALESCE(o.payload->>'internalDocumentId', '') ~ '^[0-9]+$'
                THEN (o.payload->>'internalDocumentId')::BIGINT END AS document_id
    FROM outbox_events o
    WHERE o.event_type IN ('INDEX_REQUESTED', 'SOURCE_DOCUMENT_CHANGED')
      AND (o.status IN ('PENDING', 'PUBLISHING')$historyClause)
),
outbox_scope AS (
    SELECT o.*,
           d.id IS NOT NULL AS document_known,
           e.id IS NOT NULL AS fetch_eligible
    FROM considered_outbox o
    LEFT JOIN source_documents d ON d.id = o.document_id
    LEFT JOIN eligible_documents e ON e.id = o.document_id
)
SELECT json_build_object(
    'dbIdentityOk', current_database() = 'sdv' AND current_user = 'sdv_user',
    'databaseSystemIdentifier', (SELECT system_identifier::text FROM pg_control_system()),
    'runtimeBackendSessions', (SELECT COUNT(*) FROM pg_stat_activity WHERE datname = 'sdv' AND usename = 'sdv' AND pid <> pg_backend_pid()),
    'sharedDocumentMatches', (SELECT COUNT(*) FROM shared_fixture),
    'sharedDocumentActive', COALESCE((SELECT bool_and(state = 'ACTIVE') FROM shared_fixture), FALSE),
    'sharedVersionReady', COALESCE((SELECT bool_and(btrim(COALESCE(source_version, '')) <> '') FROM shared_fixture), FALSE),
    'sharedIndexStatus', COALESCE((SELECT MIN(index_status) FROM shared_fixture), 'UNKNOWN'),
    'publisherConnectionReady', EXISTS (
        SELECT 1 FROM source_connections c
        JOIN source_oauth_tokens t ON t.source_id = c.id AND t.owner_subject = c.owner_subject
        WHERE c.id = $script:M17SourceId AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE'
          AND btrim(COALESCE(c.owner_subject, '')) <> ''
          AND btrim(COALESCE(c.provider_account_id, '')) <> ''
          AND c.token_ref = t.token_ref::text
    ),
    'sharedActiveShareCount', (SELECT COUNT(*) FROM shared_share),
    -- Classification/ownership/admin-block scope only - audience/recipient
    -- correlation and the allowed-action set are separate booleans below, so
    -- each kind of mismatch gets its own reason instead of one combined
    -- catch-all (docs/spec/SDV_v3.2_CORE_SPEC.md audience/action contract -
    -- ALL_AUTHENTICATED with zero recipients is a normal, expected state,
    -- never a missing-recipient failure).
    'sharedScopeOk', COALESCE((
        SELECT bool_and(sh.classification = 'INTERNAL' AND sh.admin_blocked = FALSE
                        AND sh.publisher_subject = c.owner_subject AND sh.source_id = $script:M17SourceId)
        FROM shared_share sh JOIN source_connections c ON c.id = sh.source_id
    ), FALSE),
    -- VIEW is mandatory; DOWNLOAD is optional and independent (SHR-001/M10C).
    -- Any action outside {VIEW, DOWNLOAD}, a duplicate, or a download-only
    -- grant (no VIEW) fails this - never inferred from a substring match on
    -- the raw stored string.
    'sharedActionsOk', COALESCE((
        SELECT bool_and(
            EXISTS (SELECT 1 FROM shared_share_actions a WHERE a.share_id = sh.id AND a.action = 'VIEW')
            AND NOT EXISTS (SELECT 1 FROM shared_share_actions a WHERE a.share_id = sh.id AND a.action NOT IN ('VIEW', 'DOWNLOAD'))
            AND (SELECT COUNT(*) FROM shared_share_actions a WHERE a.share_id = sh.id)
                = (SELECT COUNT(DISTINCT a.action) FROM shared_share_actions a WHERE a.share_id = sh.id)
        )
        FROM shared_share sh
    ), FALSE),
    -- Read the persisted audience directly - never guessed from recipient
    -- count. ALL_AUTHENTICATED requires exactly zero recipients; NAMED_USERS
    -- requires at least one recipient with a non-blank subject (the same
    -- correlation SourceSharingService.validateAudienceRecipients and
    -- DocumentShare's constructor already enforce at write time - this is a
    -- read-time re-check of that same contract, not a new one).
    'sharedAudience', COALESCE((SELECT MIN(audience) FROM shared_share), 'UNKNOWN'),
    'sharedAudienceRecipientsOk', COALESCE((
        SELECT bool_and(
            CASE sh.audience
                WHEN 'ALL_AUTHENTICATED' THEN
                    (SELECT COUNT(*) FROM shared_share_recipients r WHERE r.share_id = sh.id) = 0
                WHEN 'NAMED_USERS' THEN
                    (SELECT COUNT(*) FROM shared_share_recipients r WHERE r.share_id = sh.id) >= 1
                    AND NOT EXISTS (SELECT 1 FROM shared_share_recipients r WHERE r.share_id = sh.id AND btrim(r.recipient_subject) = '')
                ELSE FALSE
            END
        )
        FROM shared_share sh
    ), FALSE),
    'sharedRecipientCount', (SELECT COUNT(*) FROM shared_share_recipients),
    'internalLocalPolicyOk', EXISTS (SELECT 1 FROM ai_usage_policies WHERE security_level = 'INTERNAL' AND mode = 'LOCAL_ONLY' AND external_provider_allowed = FALSE),
    'privateDocumentMatches', (SELECT COUNT(*) FROM private_fixture),
    'privateDocumentActive', COALESCE((SELECT bool_and(state = 'ACTIVE') FROM private_fixture), FALSE),
    'privateActiveShareCount', (SELECT COUNT(*) FROM document_shares s JOIN private_fixture f ON f.id = s.document_id WHERE s.revoked_at IS NULL),
    'privateEmbeddingCount', (SELECT COUNT(*) FROM document_embedding_index e JOIN private_fixture f ON f.id = e.document_id),
    'otherFetchEligibleCount', (SELECT COUNT(*) FROM eligible_documents e CROSS JOIN shared_id f WHERE e.id <> f.id),
    'unknownRelevantOutboxCount', (SELECT COUNT(*) FROM outbox_scope WHERE document_id IS NULL OR document_known = FALSE),
    'otherFetchEligibleOutboxCount', (SELECT COUNT(*) FROM outbox_scope o CROSS JOIN shared_id f WHERE o.fetch_eligible = TRUE AND o.document_id <> f.id),
    'publishingOutboxCount', (SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHING'),
    'fixtureRelevantLiveCount', (SELECT COUNT(*) FROM outbox_scope o CROSS JOIN shared_id f WHERE o.document_id = f.id AND o.status IN ('PENDING', 'PUBLISHING')),
    'contentFreeLiveCount', (SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING', 'PUBLISHING') AND event_type NOT IN ('INDEX_REQUESTED', 'SOURCE_DOCUMENT_CHANGED'))
        + (SELECT COUNT(*) FROM outbox_scope WHERE status IN ('PENDING', 'PUBLISHING') AND fetch_eligible = FALSE AND document_known = TRUE),
    'totalEmbeddingCount', (SELECT COUNT(*) FROM document_embedding_index),
    'outboxHighWatermark', COALESCE((SELECT MAX(id) FROM outbox_events), 0)
);
"@

    $docker = Get-M17DockerPath
    $arguments = @('compose', '-p', $Paths.ProjectName, '-f', $Paths.ComposeFile, 'exec', '-T', 'postgres',
        'psql', '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-U', 'sdv_user', '-d', 'sdv', '-c', $sql)
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList $arguments -TimeoutSeconds 15 `
        -FailureLabel 'Content-free testbed database readiness query'
    $raw = $result.Stdout.Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) { throw 'Database readiness output was empty; activation is blocked.' }
    try {
        $snapshot = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw 'Database readiness output was malformed; activation is blocked.'
    }
    return $snapshot
}

function Get-M17KafkaTopics {
    $docker = Get-M17DockerPath
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-topics.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--list'
    ) -TimeoutSeconds 15 -FailureLabel 'Kafka protocol readiness check'
    return @($result.Stdout -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-M17KafkaGroups {
    $docker = Get-M17DockerPath
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-consumer-groups.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--list'
    ) -TimeoutSeconds 15 -FailureLabel 'Kafka consumer-group readiness check'
    return @($result.Stdout -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-M17KafkaGroupMemberCount {
    param([Parameter(Mandatory)][string]$GroupId)
    $groups = @(Get-M17KafkaGroups)
    if ($groups -notcontains $GroupId) { return 0L }
    $docker = Get-M17DockerPath
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-consumer-groups.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--describe', '--group', $GroupId, '--state'
    ) -TimeoutSeconds 15 -FailureLabel 'Kafka consumer-group state check'
    $dataLine = @($result.Stdout -split "`r?`n" | Where-Object { $_.Trim() -match ('^' + [regex]::Escape($GroupId) + '\s+') })
    if ($dataLine.Count -ne 1 -or $dataLine[0].Trim() -notmatch '\s(?<members>\d+)\s*$') {
        throw 'Kafka consumer-group state output was malformed; activation is blocked.'
    }
    return [long]$Matches['members']
}

function New-M17KafkaTopic {
    param([Parameter(Mandatory)][string]$Topic)
    $docker = Get-M17DockerPath
    [void](Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-topics.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--create', '--topic', $Topic,
        '--partitions', '1', '--replication-factor', '1'
    ) -TimeoutSeconds 15 -FailureLabel 'Explicit Kafka topic creation')
}

function Get-M17KafkaTopicOffsetTotal {
    param([Parameter(Mandatory)][string]$Topic, [Parameter(Mandatory)][ValidateSet('earliest', 'latest')][string]$Point)
    $rows = @(Get-M17KafkaTopicOffsets -Topic $Topic -Point $Point)
    [long]$total = 0
    foreach ($row in $rows) { $total += [long]$row.offset }
    return $total
}

function ConvertFrom-M17KafkaTopicOffsetOutput {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Output,
        [Parameter(Mandatory)][string]$ExpectedTopic
    )

    $lines = @($Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -lt 1) { throw 'Kafka topic offset output was empty; activation is blocked.' }
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        if ($line -notmatch '^(?<topic>[^:]+):(?<partition>\d+):(?<offset>\d+)$' -or
                $Matches['topic'] -ne $ExpectedTopic) {
            throw 'Kafka topic offset output was malformed or mismatched; activation is blocked.'
        }
        try {
            [int]$partition = $Matches['partition']
            [long]$offset = $Matches['offset']
        } catch {
            throw 'Kafka topic offset output was malformed or mismatched; activation is blocked.'
        }
        if ($partition -lt 0 -or $offset -lt 0 -or -not $seen.Add($partition)) {
            throw 'Kafka topic offset output was malformed or mismatched; activation is blocked.'
        }
        $rows.Add([PSCustomObject]@{ topic = $ExpectedTopic; partition = $partition; offset = $offset })
    }
    return $rows.ToArray()
}

function Get-M17KafkaTopicOffsets {
    param([Parameter(Mandatory)][string]$Topic, [Parameter(Mandatory)][ValidateSet('earliest', 'latest')][string]$Point)
    $time = if ($Point -eq 'earliest') { '-2' } else { '-1' }
    $docker = Get-M17DockerPath
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-get-offsets.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--topic', $Topic, '--time', $time
    ) -TimeoutSeconds 15 -FailureLabel 'Kafka topic offset check'
    return @(ConvertFrom-M17KafkaTopicOffsetOutput -Output $result.Stdout -ExpectedTopic $Topic)
}

function ConvertFrom-M17KafkaCommittedOffsetOutput {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Output,
        [Parameter(Mandatory)][string]$ExpectedTopic,
        [Parameter(Mandatory)][string]$ExpectedGroup
    )

    $lines = @($Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -lt 1) { throw 'Kafka committed-offset output was empty; activation is blocked.' }
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    $rows = [System.Collections.Generic.List[object]]::new()
    $sawNoOffsets = $false
    foreach ($line in $lines) {
        if ($line -match '^GROUP\s+TOPIC\s+PARTITION\s+CURRENT-OFFSET\s+LOG-END-OFFSET\s+LAG(?:\s+.*)?$') {
            continue
        }
        if ($line -eq "Consumer group '$ExpectedGroup' has no active members.") { continue }
        if ($line -eq "Consumer group '$ExpectedGroup' has no committed offsets.") {
            $sawNoOffsets = $true
            continue
        }

        $columns = @($line -split '\s+')
        if ($columns.Count -lt 6 -or $columns[0] -ne $ExpectedGroup -or $columns[1] -ne $ExpectedTopic -or
                $columns[2] -notmatch '^\d+$' -or $columns[3] -notmatch '^(?:-|\d+)$' -or
                $columns[4] -notmatch '^\d+$') {
            throw 'Kafka committed-offset output was malformed or mismatched; activation is blocked.'
        }
        try {
            [int]$partition = $columns[2]
            [Nullable[long]]$currentOffset = if ($columns[3] -eq '-') { $null } else { [long]$columns[3] }
            [long]$logEndOffset = $columns[4]
        } catch {
            throw 'Kafka committed-offset output was malformed or mismatched; activation is blocked.'
        }
        if ($partition -lt 0 -or $logEndOffset -lt 0 -or
                ($null -ne $currentOffset -and [long]$currentOffset -lt 0) -or -not $seen.Add($partition)) {
            throw 'Kafka committed-offset output was malformed or mismatched; activation is blocked.'
        }
        $rows.Add([PSCustomObject]@{
            topic = $ExpectedTopic
            group = $ExpectedGroup
            partition = $partition
            currentOffset = $currentOffset
            logEndOffset = $logEndOffset
        })
    }
    if ($sawNoOffsets -and $rows.Count -gt 0) {
        throw 'Kafka committed-offset output was inconsistent; activation is blocked.'
    }
    if (-not $sawNoOffsets -and $rows.Count -lt 1) {
        throw 'Kafka committed-offset output was malformed or mismatched; activation is blocked.'
    }
    return [PSCustomObject]@{ noCommittedOffsets = $sawNoOffsets; rows = $rows.ToArray() }
}

function Get-M17KafkaCommittedOffsets {
    param([Parameter(Mandatory)][string]$Topic, [Parameter(Mandatory)][string]$GroupId)
    $docker = Get-M17DockerPath
    $result = Invoke-M17BoundedNativeCapture -FilePath $docker -ArgumentList @(
        'exec', $script:M17KafkaContainer, '/opt/kafka/bin/kafka-consumer-groups.sh',
        '--bootstrap-server', $script:M17KafkaContainerBootstrap, '--describe', '--group', $GroupId, '--topic', $Topic
    ) -TimeoutSeconds 15 -FailureLabel 'Kafka committed-offset readiness check'
    return ConvertFrom-M17KafkaCommittedOffsetOutput -Output $result.Stdout -ExpectedTopic $Topic -ExpectedGroup $GroupId
}

function Get-M17KafkaResumeWorkEvidence {
    param([Parameter(Mandatory)][string]$Topic, [Parameter(Mandatory)][string]$GroupId)

    if ($script:M17IndexConsumerOffsetReset -ne 'earliest') {
        throw 'Kafka consumer offset-reset behavior is not the approved earliest mode; activation is blocked.'
    }
    $groups = @(Get-M17KafkaGroups)
    $matchingGroups = @($groups | Where-Object { $_ -eq $GroupId })
    if ($matchingGroups.Count -gt 1) {
        throw 'Kafka consumer-group listing was ambiguous; activation is blocked.'
    }

    $earliestRows = @(Get-M17KafkaTopicOffsets -Topic $Topic -Point earliest)
    $latestRows = @(Get-M17KafkaTopicOffsets -Topic $Topic -Point latest)
    if ($earliestRows.Count -ne $latestRows.Count -or $earliestRows.Count -lt 1) {
        throw 'Kafka topic partition offsets were incomplete or inconsistent; activation is blocked.'
    }

    $earliestByPartition = @{}
    $latestByPartition = @{}
    foreach ($row in $earliestRows) { $earliestByPartition[[int]$row.partition] = [long]$row.offset }
    foreach ($row in $latestRows) { $latestByPartition[[int]$row.partition] = [long]$row.offset }
    if ($earliestByPartition.Count -ne $earliestRows.Count -or $latestByPartition.Count -ne $latestRows.Count) {
        throw 'Kafka topic partition offsets were incomplete or inconsistent; activation is blocked.'
    }
    foreach ($partition in $earliestByPartition.Keys) {
        if (-not $latestByPartition.ContainsKey($partition) -or
                [long]$earliestByPartition[$partition] -gt [long]$latestByPartition[$partition]) {
            throw 'Kafka topic partition offsets were incomplete or inconsistent; activation is blocked.'
        }
    }

    $effectiveByPartition = @{}
    $offsetSource = 'earliest-no-group'
    if ($matchingGroups.Count -eq 0) {
        foreach ($partition in $earliestByPartition.Keys) {
            $effectiveByPartition[$partition] = [long]$earliestByPartition[$partition]
        }
    } else {
        $committed = Get-M17KafkaCommittedOffsets -Topic $Topic -GroupId $GroupId
        if ($committed.noCommittedOffsets -eq $true) {
            $offsetSource = 'earliest-no-commit'
            foreach ($partition in $earliestByPartition.Keys) {
                $effectiveByPartition[$partition] = [long]$earliestByPartition[$partition]
            }
        } else {
            $offsetSource = 'committed-or-earliest-per-partition'
            $committedRows = @($committed.rows)
            if ($committedRows.Count -ne $earliestByPartition.Count) {
                throw 'Kafka committed offsets did not cover the exact main-topic partitions; activation is blocked.'
            }
            foreach ($row in $committedRows) {
                [int]$partition = $row.partition
                if (-not $earliestByPartition.ContainsKey($partition) -or
                        [long]$row.logEndOffset -ne [long]$latestByPartition[$partition]) {
                    throw 'Kafka committed offsets were inconsistent with retained main-topic offsets; activation is blocked.'
                }
                [long]$effective = if ($null -eq $row.currentOffset) {
                    [long]$earliestByPartition[$partition]
                } else {
                    [long]$row.currentOffset
                }
                if ($effective -lt [long]$earliestByPartition[$partition] -or
                        $effective -gt [long]$latestByPartition[$partition] -or
                        $effectiveByPartition.ContainsKey($partition)) {
                    throw 'Kafka committed offsets were outside retained main-topic ranges; activation is blocked.'
                }
                $effectiveByPartition[$partition] = $effective
            }
        }
    }

    [long]$remaining = 0
    foreach ($partition in $latestByPartition.Keys) {
        if (-not $effectiveByPartition.ContainsKey($partition)) {
            throw 'Kafka resume offsets were incomplete; activation is blocked.'
        }
        $remaining += [long]$latestByPartition[$partition] - [long]$effectiveByPartition[$partition]
    }
    return [PSCustomObject]@{
        hasResumableWork = $remaining -gt 0
        remainingCount = $remaining
        offsetSource = $offsetSource
    }
}

function Assert-M17AiHealth {
    param([Parameter(Mandatory)][string]$AiServiceUrl)
    try {
        $health = Invoke-RestMethod -Method Get -Uri "$AiServiceUrl/health" -TimeoutSec 5
    } catch {
        throw 'The loopback AI health endpoint did not return a bounded successful response; activation is blocked.'
    }
    if ($null -eq $health -or $health.status -ne 'OK') {
        throw 'The loopback AI health response was malformed or not OK; activation is blocked.'
    }
}

function Read-M17TopicProvenance {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try {
        return Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw 'Indexing topic provenance is malformed; activation is blocked.'
    }
}

function Assert-M17ResumeProvenance {
    param(
        [Parameter(Mandatory)]$Provenance,
        [Parameter(Mandatory)][string]$KafkaBootstrapServers,
        [Parameter(Mandatory)][string]$Topic,
        [Parameter(Mandatory)][string]$GroupId,
        [string]$DatabaseSystemIdentifier
    )
    if ($null -eq $Provenance -or $Provenance.schemaVersion -ne 1 -or
            $Provenance.kafkaBootstrapServers -ne $KafkaBootstrapServers -or
            $Provenance.topic -ne $Topic -or $Provenance.dltTopic -ne "$Topic.DLT" -or
            $Provenance.groupId -ne $GroupId -or $Provenance.outboxHighWatermark -isnot [long] -and
            $Provenance.outboxHighWatermark -isnot [int] -or [long]$Provenance.outboxHighWatermark -lt 0 -or
            (-not [string]::IsNullOrWhiteSpace($DatabaseSystemIdentifier) -and
             $Provenance.databaseSystemIdentifier -ne $DatabaseSystemIdentifier)) {
        throw 'Resume settings do not exactly match established testbed-only topic provenance; activation is blocked.'
    }
}

function Write-M17TopicProvenance {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$KafkaBootstrapServers,
        [Parameter(Mandatory)][string]$Topic,
        [Parameter(Mandatory)][string]$GroupId,
        [Parameter(Mandatory)][long]$OutboxHighWatermark,
        [Parameter(Mandatory)][string]$DatabaseSystemIdentifier
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [PSCustomObject]@{
        schemaVersion = 1
        kafkaBootstrapServers = $KafkaBootstrapServers
        topic = $Topic
        dltTopic = "$Topic.DLT"
        groupId = $GroupId
        outboxHighWatermark = $OutboxHighWatermark
        databaseSystemIdentifier = $DatabaseSystemIdentifier
        createdAt = [DateTimeOffset]::UtcNow.ToString('O')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $Path
}

function Invoke-M17IndexingActivationPreflight {
    param(
        [Parameter(Mandatory)]$Paths,
        [Parameter(Mandatory)][ValidateSet('FirstRun', 'Resume')][string]$ActivationMode,
        [Parameter(Mandatory)][string]$KafkaBootstrapServers,
        [Parameter(Mandatory)][string]$Topic,
        [Parameter(Mandatory)][string]$GroupId,
        [Parameter(Mandatory)][string]$AiServiceUrl,
        [Parameter(Mandatory)][ValidateSet('NewEmptyIndex', 'ConfirmedSameKey')][string]$IndexHmacState,
        [switch]$PrepareNewTopics
    )

    $provenancePath = Join-Path $Paths.StateDir $script:M17ProvenanceFileName
    $provenance = Read-M17TopicProvenance -Path $provenancePath
    [long]$watermark = 0
    if ($ActivationMode -eq 'Resume') {
        Assert-M17ResumeProvenance -Provenance $provenance -KafkaBootstrapServers $KafkaBootstrapServers `
            -Topic $Topic -GroupId $GroupId
        $watermark = [long]$provenance.outboxHighWatermark
    } elseif ($null -ne $provenance) {
        throw 'FirstRun cannot replace existing indexing topic provenance; use Resume with the same settings.'
    }

    $snapshot = Invoke-M17ContentFreeDatabaseSnapshot -Paths $Paths -ActivationMode $ActivationMode `
        -OutboxHighWatermark $watermark
    Assert-M17IndexingReadinessSnapshot -Snapshot $snapshot -ActivationMode $ActivationMode `
        -IndexHmacState $IndexHmacState
    if ($ActivationMode -eq 'Resume') {
        Assert-M17ResumeProvenance -Provenance $provenance -KafkaBootstrapServers $KafkaBootstrapServers `
            -Topic $Topic -GroupId $GroupId -DatabaseSystemIdentifier $snapshot.databaseSystemIdentifier
    }
    Assert-M17AiHealth -AiServiceUrl $AiServiceUrl

    $topics = @(Get-M17KafkaTopics)
    $dltTopic = "$Topic.DLT"
    $mainExists = $topics -contains $Topic
    $dltExists = $topics -contains $dltTopic
    if ($ActivationMode -eq 'FirstRun') {
        if ($mainExists -or $dltExists) {
            throw 'FirstRun requires both the testbed topic and its DLT topic to be absent; provenance is unknown.'
        }
        if ((Get-M17KafkaGroups) -contains $GroupId) {
            throw 'The first-run consumer group already exists; provenance is unknown and activation is blocked.'
        }
        if (-not $PrepareNewTopics) {
            throw 'FirstRun readiness passed, but explicit -PrepareNewIndexingTopics is required to create the new empty topic pair.'
        }
        New-M17KafkaTopic -Topic $Topic
        New-M17KafkaTopic -Topic $dltTopic
        $topics = @(Get-M17KafkaTopics)
        if ($topics -notcontains $Topic -or $topics -notcontains $dltTopic) {
            throw 'The explicitly created Kafka topic pair could not be verified; activation is blocked.'
        }
        foreach ($candidate in @($Topic, $dltTopic)) {
            $earliest = Get-M17KafkaTopicOffsetTotal -Topic $candidate -Point earliest
            $latest = Get-M17KafkaTopicOffsetTotal -Topic $candidate -Point latest
            if ($earliest -ne 0 -or $latest -ne 0) {
                throw 'A newly created Kafka topic was not empty; activation is blocked.'
            }
        }
        Write-M17TopicProvenance -Path $provenancePath -KafkaBootstrapServers $KafkaBootstrapServers `
            -Topic $Topic -GroupId $GroupId -OutboxHighWatermark ([long]$snapshot.outboxHighWatermark) `
            -DatabaseSystemIdentifier $snapshot.databaseSystemIdentifier
    } else {
        if (-not $mainExists -or -not $dltExists) {
            throw 'Resume requires the established main and DLT topic pair; activation is blocked.'
        }
    }

    $activeMembers = Get-M17KafkaGroupMemberCount -GroupId $GroupId
    if ($activeMembers -ne 0) {
        throw 'Another index consumer is active in the approved testbed group; activation is blocked.'
    }

    if ($ActivationMode -eq 'Resume' -and $snapshot.sharedIndexStatus -in @('PENDING', 'STALE') -and
            [long]$snapshot.fixtureRelevantLiveCount -lt 1) {
        $resumeWork = Get-M17KafkaResumeWorkEvidence -Topic $Topic -GroupId $GroupId
        if ($resumeWork.hasResumableWork -ne $true -or $resumeWork.remainingCount -isnot [long] -and
                $resumeWork.remainingCount -isnot [int] -or [long]$resumeWork.remainingCount -lt 1) {
            throw 'SHARED still needs indexing, but neither live outbox work nor resumable main-topic work is available; activation is blocked.'
        }
    }

    Write-Ok "Indexing gate passed: SHARED scope unique; PRIVATE unshared; embeddings=$($snapshot.totalEmbeddingCount); content-free queued events=$($snapshot.contentFreeLiveCount)"
    Write-Warn2 'This point-in-time gate is not a concurrency fence. Do not change unrelated shares or run another publisher during the controlled test.'
    Write-Warn2 'AI /health does not prove the HMAC key or embedding model; IndexHmacState is the operator confirmation, not secret inspection.'
}
