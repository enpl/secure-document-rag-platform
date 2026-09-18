#requires -Version 7.0
<#
.SYNOPSIS
  Offline/synthetic tests for the M17 Assistant (local generation) activation
  gate.

.NOTES
  Does not execute start-testbed.ps1, Ollama, or any secret resolver. The
  two functions that make a real network call (Assert-M17AssistantOllamaReachable/
  Assert-M17AssistantModelInstalled) are exercised only through a redefined,
  synthetic version of themselves (same technique _indexing-activation.tests.ps1
  already uses for Assert-M17AiHealth) - never a real loopback call.
#>

param([string]$TestScriptRoot = $PSScriptRoot)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command New-IsolatedEnvironment -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_lib.ps1')
}
if (-not (Get-Command Assert-M17IndexingParameters -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_indexing-activation.ps1')
}
if (-not (Get-Command Assert-M17AssistantParameters -ErrorAction SilentlyContinue)) {
    . (Join-Path $TestScriptRoot '_assistant-activation.ps1')
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

$approvedModel = 'qwen2.5:7b-instruct-q4_K_M'
$approvedUrl = 'http://127.0.0.1:11434'

Write-Step '1. Baseline remains explicitly OFF and needs no activation settings'
$baselineOk = $true
try { Assert-M17AssistantParameters -Enabled $false } catch { $baselineOk = $false }
Assert-True $baselineOk 'plain mode accepts no Assistant activation parameters'
$baselineOverrides = Get-M17AssistantBackendOverrides -Enabled $false
Assert-True ($baselineOverrides.Count -eq 1 -and $baselineOverrides['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') `
    'plain mode emits exactly one override: Assistant explicitly disabled'
Assert-Throws { Assert-M17AssistantParameters -Enabled $false -AssistantModel $approvedModel } `
    'an activation-only model value cannot be smuggled into plain mode'
Assert-Throws { Assert-M17AssistantParameters -Enabled $false -AssistantOllamaUrl $approvedUrl } `
    'an activation-only Ollama URL cannot be smuggled into plain mode'

Write-Step '2. Explicit ON with the exact approved model/endpoint is accepted'
$approvedAiServiceUrl = 'http://127.0.0.1:8000'
$validOk = $true
try {
    Assert-M17AssistantParameters -Enabled $true -AssistantModel $approvedModel -AssistantOllamaUrl $approvedUrl
} catch { $validOk = $false }
Assert-True $validOk 'the exact installed/approved model and loopback endpoint are accepted'
$enabledOverrides = Get-M17AssistantBackendOverrides -Enabled $true -AssistantModel $approvedModel `
    -AssistantOllamaUrl $approvedUrl
$expectedEnabled = @{
    SDV_RAG_ASSISTANT_ENABLED    = 'true'
    SDV_RAG_ASSISTANT_MODEL      = $approvedModel
    SDV_RAG_ASSISTANT_OLLAMA_URL = $approvedUrl
    # M17 진단 교정 - Assistant 단독 실행도 embedQuery/생성 호출을 위해 승인된
    # AI 서비스 주소가 필요하다(새 CLI 옵션 없음, 색인이 쓰는 것과 정확히 같은
    # 고정값).
    AI_SERVICE_URL               = $approvedAiServiceUrl
}
Assert-True ($enabledOverrides.Count -eq $expectedEnabled.Count) 'enabled mode emits only the exact four allowlist entries'
foreach ($key in $expectedEnabled.Keys) {
    Assert-True ($enabledOverrides[$key] -eq $expectedEnabled[$key]) "enabled override $key has the checked value"
}

Write-Step '3. Wrong/incomplete configuration is rejected (fail closed, no download/reconfiguration)'
$invalidCases = @(
    @{ Name = 'a different model name'; Model = 'llama3:8b'; Url = $approvedUrl },
    @{ Name = 'a non-loopback Ollama URL'; Model = $approvedModel; Url = 'http://example.com:11434' },
    @{ Name = 'a different loopback port'; Model = $approvedModel; Url = 'http://127.0.0.1:9999' },
    @{ Name = 'localhost instead of the exact loopback literal'; Model = $approvedModel; Url = 'http://localhost:11434' },
    @{ Name = 'a blank model while enabled'; Model = ''; Url = $approvedUrl },
    @{ Name = 'a blank Ollama URL while enabled'; Model = $approvedModel; Url = '' }
)
foreach ($case in $invalidCases) {
    Assert-Throws {
        Assert-M17AssistantParameters -Enabled $true -AssistantModel $case.Model -AssistantOllamaUrl $case.Url
    } "$($case.Name) is rejected"
}

Write-Step '4. Parent-environment pollution cannot activate or reconfigure the Assistant'
$originalEnabled = $env:SDV_RAG_ASSISTANT_ENABLED
$originalModel = $env:SDV_RAG_ASSISTANT_MODEL
$originalUrl = $env:SDV_RAG_ASSISTANT_OLLAMA_URL
try {
    $env:SDV_RAG_ASSISTANT_ENABLED = 'true'
    $env:SDV_RAG_ASSISTANT_MODEL = 'attacker-supplied-model'
    $env:SDV_RAG_ASSISTANT_OLLAMA_URL = 'http://attacker.example:11434'
    # 실제 launcher가 하는 것과 정확히 같은 순서: 부모 프로세스 환경(이미 오염됨)에서
    # 시작해 New-IsolatedEnvironment가 SDV_* 전부를 벗겨낸 뒤, 이 Baseline 실행이
    # 만드는 유일한 Override({ enabled=false })만 그 위에 얹는다.
    $childEnv = New-IsolatedEnvironment -Overrides (Get-M17AssistantBackendOverrides -Enabled $false)
    Assert-True ($childEnv['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') `
        'a parent-shell SDV_RAG_ASSISTANT_ENABLED=true cannot activate the Assistant in the isolated child environment'
    Assert-True (-not $childEnv.ContainsKey('SDV_RAG_ASSISTANT_MODEL')) `
        'a parent-shell SDV_RAG_ASSISTANT_MODEL is stripped, not inherited, by the isolated child environment'
    Assert-True (-not $childEnv.ContainsKey('SDV_RAG_ASSISTANT_OLLAMA_URL')) `
        'a parent-shell SDV_RAG_ASSISTANT_OLLAMA_URL is stripped, not inherited, by the isolated child environment'
} finally {
    $env:SDV_RAG_ASSISTANT_ENABLED = $originalEnabled
    $env:SDV_RAG_ASSISTANT_MODEL = $originalModel
    $env:SDV_RAG_ASSISTANT_OLLAMA_URL = $originalUrl
}

Write-Step '5. Launcher AST/ordering (parse only, real start-testbed.ps1 - not a re-simulation): Assistant overrides are actually wired after indexing''s, into the same isolation call'
# _indexing-activation.tests.ps1 already establishes this exact technique
# (parsing start-testbed.ps1's AST to confirm the indexing gate runs before
# the tracked backend start) for the same reason: a helper's own return value
# proves nothing about whether the real launcher actually calls it, in the
# right order, before the right side effect. This section applies the same
# proof to the Assistant/AI_SERVICE_URL wiring instead of re-typing the merge.
$launcherPath = Join-Path $TestScriptRoot 'start-testbed.ps1'
$launcherTokens = $null
$launcherParseErrors = $null
$launcherAst = [System.Management.Automation.Language.Parser]::ParseFile($launcherPath, [ref]$launcherTokens,
    [ref]$launcherParseErrors)
Assert-True ($launcherParseErrors.Count -eq 0) 'start-testbed.ps1 parses without PowerShell syntax errors'
$launcherCommands = @($launcherAst.FindAll(
        { param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
$indexingOverridesCall = $launcherCommands | Where-Object { $_.GetCommandName() -eq 'Get-M17IndexingBackendOverrides' } |
    Select-Object -First 1
$assistantOverridesCalls = @($launcherCommands | Where-Object { $_.GetCommandName() -eq 'Get-M17AssistantBackendOverrides' })
$isolateCall = $launcherCommands | Where-Object { $_.GetCommandName() -eq 'New-IsolatedEnvironment' } |
    Select-Object -First 1
$backendStartCall = $launcherCommands | Where-Object {
    $_.GetCommandName() -eq 'Start-TrackedProcess' -and $_.Extent.Text -match "-Name\s+'backend'"
} | Select-Object -First 1
Assert-True ($null -ne $indexingOverridesCall) 'launcher calls Get-M17IndexingBackendOverrides directly'
Assert-True ($assistantOverridesCalls.Count -eq 1) 'launcher calls Get-M17AssistantBackendOverrides exactly once'
Assert-True ($null -ne $isolateCall -and $null -ne $backendStartCall) `
    'launcher calls New-IsolatedEnvironment and starts exactly one tracked backend process'
Assert-True ($null -ne $indexingOverridesCall -and $assistantOverridesCalls.Count -eq 1 -and
        $indexingOverridesCall.Extent.StartOffset -lt $assistantOverridesCalls[0].Extent.StartOffset) `
    'Get-M17AssistantBackendOverrides is called AFTER Get-M17IndexingBackendOverrides in the real launcher source (Assistant, and its AI_SERVICE_URL, wins the merge)'
Assert-True ($assistantOverridesCalls.Count -eq 1 -and $null -ne $isolateCall -and
        $assistantOverridesCalls[0].Extent.StartOffset -lt $isolateCall.Extent.StartOffset) `
    'the Assistant override call happens before New-IsolatedEnvironment assembles the child environment'
Assert-True ($null -ne $isolateCall -and $null -ne $backendStartCall -and
        $isolateCall.Extent.StartOffset -lt $backendStartCall.Extent.StartOffset) `
    'the isolated environment is assembled before the backend process is actually spawned'
$assistantHelperText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $TestScriptRoot '_assistant-activation.ps1')
Assert-True ($assistantHelperText.Contains('AI_SERVICE_URL') -and $assistantHelperText.Contains($approvedAiServiceUrl)) `
    'the real Get-M17AssistantBackendOverrides source (not a test-side copy) carries the approved AI_SERVICE_URL fix'

Write-Step '6. The real helper functions, combined in the same order the launcher AST above just confirmed, produce the correct override set for every mode'
# This step calls the SAME two functions the launcher calls (no re-implemented
# business logic, only the same trivial "copy every key" merge start-testbed.ps1
# performs after the call sites verified above) to confirm their actual
# combined VALUES for each mode - Section 5 already proved the launcher calls
# them in this order; this proves what that order actually produces.
$validIndexing = @{
    KafkaBootstrapServers = '127.0.0.1:9092'
    IndexingTopic         = 'sdv.testbed.m17.indexing.v1'
    IndexingGroupId       = 'sdv-testbed-m17-indexing-v1'
    AiServiceUrl          = $approvedAiServiceUrl
}
function Get-M17MergedOverridesForTest {
    param([bool]$EnableIndexing, [hashtable]$Indexing, [bool]$EnableAssistant, [string]$Model, [string]$OllamaUrl)
    $result = @{}
    $indexing = if ($Indexing) {
        Get-M17IndexingBackendOverrides -Enabled $EnableIndexing -KafkaBootstrapServers $Indexing.KafkaBootstrapServers `
            -Topic $Indexing.IndexingTopic -GroupId $Indexing.IndexingGroupId -AiServiceUrl $Indexing.AiServiceUrl
    } else {
        Get-M17IndexingBackendOverrides -Enabled $EnableIndexing
    }
    foreach ($key in $indexing.Keys) { $result[$key] = $indexing[$key] }
    $assistant = Get-M17AssistantBackendOverrides -Enabled $EnableAssistant -AssistantModel $Model -AssistantOllamaUrl $OllamaUrl
    foreach ($key in $assistant.Keys) { $result[$key] = $assistant[$key] }
    return $result
}

# 6a. Baseline: both gates disabled - publisher/consumer/Assistant all
# explicitly OFF, and no AI_SERVICE_URL is fabricated when neither gate needs it.
$baseline = Get-M17MergedOverridesForTest -EnableIndexing $false -EnableAssistant $false
Assert-True ($baseline['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'false' -and
        $baseline['SDV_RAG_INDEX_CONSUMER_ENABLED'] -eq 'false' -and
        $baseline['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') `
    'baseline: publisher/consumer/Assistant are all explicitly disabled'
Assert-True (-not $baseline.ContainsKey('AI_SERVICE_URL')) `
    'baseline: no AI_SERVICE_URL override is fabricated when neither gate needs it'

# 6b. Indexing only - existing behavior preserved exactly.
$indexingOnly = Get-M17MergedOverridesForTest -EnableIndexing $true -Indexing $validIndexing -EnableAssistant $false
Assert-True ($indexingOnly['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'true' -and
        $indexingOnly['SDV_RAG_INDEX_CONSUMER_ENABLED'] -eq 'true' -and
        $indexingOnly['SDV_RAG_ASSISTANT_ENABLED'] -eq 'false') `
    'indexing-only: publisher/consumer enabled, Assistant stays disabled (existing behavior preserved)'
Assert-True ($indexingOnly['AI_SERVICE_URL'] -eq $approvedAiServiceUrl) `
    'indexing-only: AI_SERVICE_URL comes from the existing -AiServiceUrl parameter, unchanged'

# 6c. Assistant only (indexing off) - THE MVP-blocking gap this correction fixes.
$assistantOnly = Get-M17MergedOverridesForTest -EnableIndexing $false -EnableAssistant $true `
    -Model $approvedModel -OllamaUrl $approvedUrl
Assert-True ($assistantOnly['SDV_OUTBOX_PUBLISHER_ENABLED'] -eq 'false' -and
        $assistantOnly['SDV_RAG_INDEX_CONSUMER_ENABLED'] -eq 'false') `
    'Assistant-only: publisher/consumer remain explicitly OFF'
Assert-True ($assistantOnly['SDV_RAG_ASSISTANT_ENABLED'] -eq 'true' -and
        $assistantOnly['SDV_RAG_ASSISTANT_MODEL'] -eq $approvedModel -and
        $assistantOnly['SDV_RAG_ASSISTANT_OLLAMA_URL'] -eq $approvedUrl) `
    'Assistant-only: Assistant itself is enabled with the approved model/endpoint'
Assert-True ($assistantOnly['AI_SERVICE_URL'] -eq $approvedAiServiceUrl) `
    'Assistant-only (the MVP-blocking gap): AI_SERVICE_URL is now the approved address without -EnableIndexing'

# 6d. Both enabled together - the same approved address must be used by both.
$both = Get-M17MergedOverridesForTest -EnableIndexing $true -Indexing $validIndexing -EnableAssistant $true `
    -Model $approvedModel -OllamaUrl $approvedUrl
Assert-True ($both['SDV_RAG_ASSISTANT_ENABLED'] -eq 'true') `
    'both-enabled: an explicit Assistant activation overrides indexing''s own forced-disabled default when layered afterward'
Assert-True ($both['AI_SERVICE_URL'] -eq $approvedAiServiceUrl) `
    'both-enabled: indexing and Assistant agree on the exact same approved AI_SERVICE_URL'
Assert-True ($both['SPRING_KAFKA_BOOTSTRAP_SERVERS'] -eq $validIndexing.KafkaBootstrapServers -and
        $both['SDV_RAG_INDEX_CONSUMER_TOPIC'] -eq $validIndexing.IndexingTopic) `
    'both-enabled: unrelated indexing overrides are unaffected by the Assistant layer (existing indexing behavior preserved)'

Write-Step '7. Parent-environment pollution cannot redirect AI_SERVICE_URL either (real helpers, same merge order, real New-IsolatedEnvironment)'
$originalAiServiceUrl = $env:AI_SERVICE_URL
try {
    $env:AI_SERVICE_URL = 'http://attacker.example:9999'
    $pollutedMerged = Get-M17MergedOverridesForTest -EnableIndexing $false -EnableAssistant $true `
        -Model $approvedModel -OllamaUrl $approvedUrl
    $polluted = New-IsolatedEnvironment -Overrides $pollutedMerged
    Assert-True ($polluted['AI_SERVICE_URL'] -eq $approvedAiServiceUrl) `
        'a parent-shell AI_SERVICE_URL cannot redirect the Assistant''s embedding/generation calls elsewhere'
} finally {
    $env:AI_SERVICE_URL = $originalAiServiceUrl
}

Write-Step '8. Full preflight orchestration (Ollama reachability + installed-model check), synthetic network layer'
$script:mockOllamaFailure = $null
$script:mockModelInstalledFailure = $null
function Assert-M17AssistantOllamaReachable {
    param([string]$AssistantOllamaUrl)
    if ($null -ne $script:mockOllamaFailure) { throw $script:mockOllamaFailure }
}
function Assert-M17AssistantModelInstalled {
    param([string]$AssistantOllamaUrl, [string]$AssistantModel)
    if ($null -ne $script:mockModelInstalledFailure) { throw $script:mockModelInstalledFailure }
}

$preflightOk = $true
try { Invoke-M17AssistantActivationPreflight -AssistantModel $approvedModel -AssistantOllamaUrl $approvedUrl } catch { $preflightOk = $false }
Assert-True $preflightOk 'the full preflight passes when Ollama is reachable and the approved model is installed'

$script:mockOllamaFailure = 'The loopback Ollama endpoint did not return a bounded successful response; Assistant activation is blocked.'
Assert-Throws { Invoke-M17AssistantActivationPreflight -AssistantModel $approvedModel -AssistantOllamaUrl $approvedUrl } `
    'an unreachable loopback Ollama endpoint blocks activation before backend launch'
$script:mockOllamaFailure = $null

$script:mockModelInstalledFailure = "The approved model '$approvedModel' is not present in the local Ollama installation (no automatic download is performed); Assistant activation is blocked."
Assert-Throws { Invoke-M17AssistantActivationPreflight -AssistantModel $approvedModel -AssistantOllamaUrl $approvedUrl } `
    'a not-yet-installed approved model blocks activation instead of triggering an automatic download'
$script:mockModelInstalledFailure = $null

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All M17 Assistant activation tests passed.' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) M17 Assistant activation test(s) FAILED." -ForegroundColor Red
exit 1
