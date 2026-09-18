#requires -Version 7.0
<#
.SYNOPSIS
  Non-secret, fail-closed helpers for the explicit M17 testbed Assistant
  (local generation) activation gate.

.NOTES
  This file has no top-level service calls. It may be dot-sourced by focused
  synthetic tests. The real network checks run only when start-testbed.ps1
  explicitly calls Invoke-M17AssistantActivationPreflight, after the isolated
  backend/indexing gates (if any) have already been evaluated and before the
  backend process is spawned.

  Assistant stays OFF unless -EnableAssistant is passed explicitly to
  start-testbed.ps1 - baseline runs and indexing-only runs are unaffected
  (Get-M17IndexingBackendOverrides already forces SDV_RAG_ASSISTANT_ENABLED=
  false; start-testbed.ps1 applies this file's overrides afterward so an
  explicit -EnableAssistant choice always wins over that default). No
  network/Ollama call, model download, or key generation happens merely by
  dot-sourcing this file or by an inherited parent-shell environment
  variable - New-IsolatedEnvironment (_lib.ps1) already strips every
  inherited SDV_*/SPRING_*/... variable before these overrides are applied
  on top, so setting SDV_RAG_ASSISTANT_ENABLED in the parent shell cannot
  activate the Assistant by itself (Assert-M17AssistantParameters also
  refuses to accept activation values without the explicit switch, the same
  pattern _indexing-activation.ps1 already established for indexing).
#>

Set-StrictMode -Version Latest

# 사용자가 이미 로컬에 설치/승인을 확인한 유일한 생성 모델과 loopback Ollama
# Endpoint(이 작업 지시사항 "설치·승인된 생성 모델: qwen2.5:7b-instruct-q4_K_M").
# 다른 값은 전부 거부한다 - _indexing-activation.ps1의 KafkaBootstrapServers/
# AiServiceUrl과 동일한 관례(고정값 하나만 허용, 자동 선택/다운로드 없음).
$script:M17ApprovedAssistantModel = 'qwen2.5:7b-instruct-q4_K_M'
$script:M17ApprovedAssistantOllamaUrl = 'http://127.0.0.1:11434'

# M17 진단 교정(AI 서비스 주소 전달 누락) - Assistant가 실제로 답변을 생성하려면
# embedQuery/생성 호출이 이 주소로 나가야 한다(DocumentParsingClient.embedQuery,
# sdv.ai-service.url). _indexing-activation.ps1의 Assert-M17IndexingParameters가
# 이미 이 정확한 값 하나만 AiServiceUrl로 허용한다 - Assistant 단독 실행에
# 새 CLI 옵션을 만들지 않고, 이미 승인된 그 값을 그대로 재사용한다.
$script:M17ApprovedAiServiceUrl = 'http://127.0.0.1:8000'

function Assert-M17AssistantParameters {
    <#
    .SYNOPSIS
      Fail-closed parameter validation - no network/Ollama call. Mirrors
      Assert-M17IndexingParameters's "activation values require the explicit
      switch" and "only the exact approved value is accepted" conventions.
    #>
    param(
        [Parameter(Mandatory)][bool]$Enabled,
        [string]$AssistantModel,
        [string]$AssistantOllamaUrl
    )

    $activationValuesPresent = -not [string]::IsNullOrWhiteSpace($AssistantModel) -or
        -not [string]::IsNullOrWhiteSpace($AssistantOllamaUrl)

    if (-not $Enabled) {
        if ($activationValuesPresent) {
            throw 'Assistant activation parameters require -EnableAssistant; the Assistant remains explicitly disabled otherwise.'
        }
        return
    }

    if ($AssistantModel -ne $script:M17ApprovedAssistantModel) {
        throw "AssistantModel must be exactly the installed/approved model ('$script:M17ApprovedAssistantModel') for this controlled testbed activation."
    }
    if ($AssistantOllamaUrl -ne $script:M17ApprovedAssistantOllamaUrl) {
        throw "AssistantOllamaUrl must be exactly the loopback endpoint ('$script:M17ApprovedAssistantOllamaUrl') for this controlled testbed activation."
    }
}

function Get-M17AssistantBackendOverrides {
    <#
    .SYNOPSIS
      The exact, small backend environment override set for this gate - no
      other SDV_RAG_ASSISTANT_* key is ever touched here (context-tokens/
      deadlines/etc. keep their existing application.yml defaults - this
      task reuses that configuration, it does not add new tunables).

    .NOTES
      M17 진단 교정(AI 서비스 주소 전달 누락) - 이전에는 AI_SERVICE_URL이
      -EnableIndexing일 때만 Get-M17IndexingBackendOverrides로 전달돼,
      Assistant 단독 실행(색인 없이)에서는 이 값이 전혀 설정되지 않아
      sdv.ai-service.url이 application-testbed.yml의 하드코딩된 기본값
      (http://localhost:8000, 명시적 127.0.0.1이 아니다)으로 되돌아갔다.
      이제 Assistant가 활성화될 때마다(색인 활성화 여부와 무관하게) 이미
      승인된 고정 주소를 함께 전달한다 - 새 CLI 옵션이나 임의 URL을 받지
      않는다(부모 환경의 다른 값에 의존하지 않는다 - New-IsolatedEnvironment가
      먼저 AI_SERVICE_URL을 포함해 그 어떤 상속값도 사용하지 않고, 이
      Override가 유일한 값이 된다 - 단, AI_SERVICE_URL 자체는 SDV_*/...
      Deny-list에 없으므로 이 Override가 없으면 부모 값이 새어 들어갈 수
      있었다는 것이 바로 이 교정의 이유다).
    #>
    param(
        [Parameter(Mandatory)][bool]$Enabled,
        [string]$AssistantModel,
        [string]$AssistantOllamaUrl
    )

    if (-not $Enabled) {
        return @{ SDV_RAG_ASSISTANT_ENABLED = 'false' }
    }
    return @{
        SDV_RAG_ASSISTANT_ENABLED    = 'true'
        SDV_RAG_ASSISTANT_MODEL      = $AssistantModel
        SDV_RAG_ASSISTANT_OLLAMA_URL = $AssistantOllamaUrl
        AI_SERVICE_URL               = $script:M17ApprovedAiServiceUrl
    }
}

function Assert-M17AssistantOllamaReachable {
    <#
    .SYNOPSIS
      Confirms the approved loopback Ollama endpoint answers a bounded,
      well-formed request - never installs, pulls, or configures anything.
    #>
    param([Parameter(Mandatory)][string]$AssistantOllamaUrl)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$AssistantOllamaUrl/api/version" -TimeoutSec 5
    } catch {
        throw 'The loopback Ollama endpoint did not return a bounded successful response; Assistant activation is blocked.'
    }
    if ($response.StatusCode -ne 200) {
        throw 'The loopback Ollama endpoint responded with a non-200 status; Assistant activation is blocked.'
    }
}

function Assert-M17AssistantModelInstalled {
    <#
    .SYNOPSIS
      Confirms the approved model is already present locally
      (GET /api/tags) - never triggers a pull/download and never connects to
      any endpoint other than the already-validated loopback AssistantOllamaUrl.
    #>
    param(
        [Parameter(Mandatory)][string]$AssistantOllamaUrl,
        [Parameter(Mandatory)][string]$AssistantModel
    )
    try {
        $tags = Invoke-RestMethod -Method Get -Uri "$AssistantOllamaUrl/api/tags" -TimeoutSec 5
    } catch {
        throw 'The loopback Ollama model listing did not return a bounded successful response; Assistant activation is blocked.'
    }
    $names = @($tags.models | ForEach-Object { $_.name })
    if ($names -notcontains $AssistantModel) {
        throw "The approved model '$AssistantModel' is not present in the local Ollama installation (no automatic download is performed); Assistant activation is blocked."
    }
}

function Invoke-M17AssistantActivationPreflight {
    <#
    .SYNOPSIS
      The only function in this file that makes a network call. Called by
      start-testbed.ps1 only after Assert-M17AssistantParameters has already
      passed, immediately before the backend process is spawned - matches
      the ordering _indexing-activation.ps1 already established for its own
      gate.
    #>
    param(
        [Parameter(Mandatory)][string]$AssistantModel,
        [Parameter(Mandatory)][string]$AssistantOllamaUrl
    )
    Assert-M17AssistantOllamaReachable -AssistantOllamaUrl $AssistantOllamaUrl
    Assert-M17AssistantModelInstalled -AssistantOllamaUrl $AssistantOllamaUrl -AssistantModel $AssistantModel
    Write-Ok "Assistant gate passed: model '$AssistantModel' installed and loopback Ollama reachable at $AssistantOllamaUrl"
}
