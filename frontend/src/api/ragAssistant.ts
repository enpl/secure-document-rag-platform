import type { ApiClient } from './client'
import type { RagFileSearchResponse } from './fileDiscovery'

export interface RagCitation {
  documentId: number
  locatorType: string
  locatorValue: string
  sourceVersion: string
  verifiedAt: string
  downloadUrl: string | null
}

export interface RagAnswerResponse {
  status: string
  reasonCode: string | null
  answer: string | null
  generatedAnalysis: string | null
  citations: RagCitation[]
  files: RagFileSearchResponse | null
  partial: boolean
}

export function askRag(
  client: ApiClient,
  question: string,
  selectedDocumentIds: number[],
  signal: AbortSignal,
): Promise<RagAnswerResponse> {
  return client.post('/rag/ask', { question, selectedDocumentIds }, signal)
}

/** Convert only CitationAssembler's exact same-origin route to ApiClient's already-/api-prefixed form. */
export function citationDownloadPath(value: string | null): string | null {
  if (!value) return null
  const match = /^\/api\/shares\/([1-9]\d*)\/download$/.exec(value)
  return match ? `/shares/${match[1]}/download` : null
}

/**
 * M17 프론트 고급화 - 답변 상태의 시각적 심각도(성공/부분/실패)를 한 곳에서
 * 결정한다. 서버가 이미 돌려주는 `status`만 기준으로 삼는다(새 값을 지어내지
 * 않는다) - `reasonCode`는 여전히 {@link describeRagOutcome}의 문구만 정한다.
 */
export function ragOutcomeSeverity(status: string): 'error' | 'warning' | 'info' {
  if (status === 'REJECTED' || status === 'FAILED') return 'error'
  if (status === 'PARTIAL' || status === 'NO_EVIDENCE' || status === 'CLARIFICATION_REQUIRED') return 'warning'
  return 'info'
}

export function describeRagOutcome(status: string, reasonCode: string | null): string {
  const reasons: Record<string, string> = {
    POLICY_BYPASS: '권한이나 보안 정책을 우회하는 요청은 처리할 수 없습니다.',
    OUT_OF_SCOPE: '업무 문서 찾기·질문·요약·비교 범위의 요청을 입력해 주세요.',
    CLASSIFICATION_UNAVAILABLE: '요청 의도를 안전하게 확인하지 못했습니다. 더 명확하게 다시 작성해 주세요.',
    REQUEST_TIMEOUT: '처리 시간이 초과되었습니다. 같은 질문으로 다시 시도할 수 있습니다.',
    CREATED_DATE_UNSUPPORTED: '현재는 생성일 조건을 지원하지 않습니다. 수정일 기준으로 질문해 주세요.',
    UNSUPPORTED_FILE_CONSTRAINT: '지원하지 않는 파일 검색 조건입니다. 조건을 단순하게 바꿔 주세요.',
    AMBIGUOUS_FILE_TYPE: '파일 형식을 하나로 명확히 지정해 주세요.',
    FILE_QUERY_TOO_LONG: '파일 검색어가 너무 깁니다.',
    NOT_AUTHORIZED: '현재 이 자료를 사용할 권한을 확인하지 못했습니다.',
    PROVIDER_UNAVAILABLE: '원본 제공자 확인이 일시적으로 불가능합니다.',
    // M17 진단 교정 - 이전에는 이 경우도 위 PROVIDER_UNAVAILABLE(Google 등 원본
    // 제공자)로 잘못 표시됐다. 실제로는 로컬 문서 임베딩(의미 검색) 서비스 호출
    // 실패이며 원본 제공자와 무관하다 - 별도 안내로 구분한다.
    EMBEDDING_PROVIDER_UNAVAILABLE: '문서 검색(임베딩) 서비스에 일시적으로 연결할 수 없습니다.',
    NO_RELEVANT_EVIDENCE: '현재 권한과 범위에서 관련 근거를 찾지 못했습니다.',
    COMPARISON_INPUT_INCOMPLETE: '비교 대상 모두의 근거를 확인하지 못해 비교 답변을 만들지 않았습니다.',
    PARTIAL_EVIDENCE_COVERAGE: '확인 가능한 범위의 일부 근거만 사용했습니다.',
    CAPACITY_EXHAUSTED: '현재 동시 처리 용량이 가득 찼습니다. 잠시 후 다시 시도해 주세요.',
    MODEL_UNAVAILABLE: '로컬 AI 모델을 현재 사용할 수 없습니다.',
    CONTEXT_LIMIT_EXCEEDED: '질문과 근거가 모델 입력 한도를 초과했습니다.',
    DOCUMENT_CHANGED: '처리 중 원본 문서가 변경되었습니다. 다시 시도해 주세요.',
    UNSUPPORTED_FORMAT: '이 파일 형식은 내용 질문을 지원하지 않습니다.',
    EXPORT_LIMIT_EXCEEDED: '원본 제공자의 내보내기 한도를 초과했습니다.',
    EVIDENCE_EXPIRED: '검증된 근거의 사용 시간이 만료되었습니다. 다시 질문해 주세요.',
    EVIDENCE_UNAVAILABLE: '검증된 근거를 더 이상 사용할 수 없습니다.',
    UNSUPPORTED_MODEL_CLAIMS: '근거로 확인할 수 있는 답변 문장을 만들지 못했습니다.',
    SELECTION_LIMIT_EXCEEDED: '한 번에 선택할 수 있는 문서 수를 초과했습니다.',
  }
  if (reasonCode && reasons[reasonCode]) return reasons[reasonCode]
  if (status === 'SUCCESS') return '답변을 완료했습니다.'
  if (status === 'PARTIAL') return '일부 범위만 확인했습니다.'
  if (status === 'NO_EVIDENCE') return '답변에 사용할 검증된 근거가 없습니다.'
  if (status === 'REJECTED') return '이 요청은 처리할 수 없습니다.'
  if (status === 'CLARIFICATION_REQUIRED') return '요청을 더 명확하게 입력해 주세요.'
  return '요청을 완료하지 못했습니다. 상태를 확인한 뒤 다시 시도해 주세요.'
}
