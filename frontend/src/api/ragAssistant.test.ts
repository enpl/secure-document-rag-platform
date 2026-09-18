import { describe, expect, it } from 'vitest'
import { citationDownloadPath, describeRagOutcome } from './ragAssistant'

describe('rag assistant API contract', () => {
  it('accepts only the exact server-issued same-origin citation download route and strips /api once', () => {
    expect(citationDownloadPath('/api/shares/42/download')).toBe('/shares/42/download')
    for (const value of [
      'https://evil.invalid/api/shares/42/download',
      '//evil.invalid/x',
      '/api/api/shares/42/download',
      '/api/shares/42/download?token=x',
      '/api/shares/42/download#x',
      '/api/shares/not-a-number/download',
    ])
      expect(citationDownloadPath(value)).toBeNull()
  })

  it.each([
    ['PARTIAL', 'COMPARISON_INPUT_INCOMPLETE'],
    ['NO_EVIDENCE', 'NO_RELEVANT_EVIDENCE'],
    ['REJECTED', 'POLICY_BYPASS'],
    ['FAILED', 'MODEL_UNAVAILABLE'],
    ['FAILED', 'REQUEST_TIMEOUT'],
    ['FAILED', 'CAPACITY_EXHAUSTED'],
    ['UNKNOWN', 'NEW_SERVER_CODE'],
    ['FAILED', 'EMBEDDING_PROVIDER_UNAVAILABLE'],
  ])('gives a bounded message for %s/%s', (status, reason) => {
    expect(describeRagOutcome(status, reason)).toBeTruthy()
  })

  // M17 진단 교정 - 임베딩(로컬 검색) 서비스 실패와 실제 원본(Google 등) 제공자
  // 실패는 서로 다른 원인이므로 서로 다른 안내 문구를 보여줘야 한다(하나로
  // 뭉뚱그려 오해를 만들지 않는다).
  it('distinguishes the embedding-service failure from the actual source-provider failure', () => {
    const embeddingMessage = describeRagOutcome('FAILED', 'EMBEDDING_PROVIDER_UNAVAILABLE')
    const providerMessage = describeRagOutcome('FAILED', 'PROVIDER_UNAVAILABLE')
    expect(embeddingMessage).not.toBe(providerMessage)
  })
})
