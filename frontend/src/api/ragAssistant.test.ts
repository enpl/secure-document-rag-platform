import { describe, expect, it } from 'vitest'
import { citationDownloadPath, describeRagOutcome } from './ragAssistant'

describe('rag assistant API contract', () => {
  it('accepts only the exact server-issued same-origin citation download route and strips /api once', () => {
    expect(citationDownloadPath('/api/shares/42/download')).toBe('/shares/42/download')
    for (const value of ['https://evil.invalid/api/shares/42/download', '//evil.invalid/x',
      '/api/api/shares/42/download', '/api/shares/42/download?token=x', '/api/shares/42/download#x',
      '/api/shares/not-a-number/download']) expect(citationDownloadPath(value)).toBeNull()
  })

  it.each([
    ['PARTIAL', 'COMPARISON_INPUT_INCOMPLETE'], ['NO_EVIDENCE', 'NO_RELEVANT_EVIDENCE'],
    ['REJECTED', 'POLICY_BYPASS'], ['FAILED', 'MODEL_UNAVAILABLE'], ['FAILED', 'REQUEST_TIMEOUT'],
    ['FAILED', 'CAPACITY_EXHAUSTED'], ['UNKNOWN', 'NEW_SERVER_CODE'],
  ])('gives a bounded message for %s/%s', (status, reason) => {
    expect(describeRagOutcome(status, reason)).toBeTruthy()
  })
})
