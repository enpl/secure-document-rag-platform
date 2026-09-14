import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useApiClient } from '../api/useApiClient'
import { checkTestbedDiagnosticsStatus, checkTestbedRead } from '../api/testbedDiagnostics'
import type { TestbedReadDiagnosticResponse } from '../api/testbedDiagnostics'
import type { SourceResponse } from '../api/sources'
import { ApiError } from '../api/client'

interface TestbedDiagnosticPanelProps {
  sources: SourceResponse[]
}

type AvailabilityState = 'checking' | 'available' | 'unavailable'

type CheckState =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'result'; response: TestbedReadDiagnosticResponse }
  | { kind: 'error'; message: string }

/**
 * M16A follow-up (docs/runbooks/M16A_LOCAL_TESTBED.md) - clearly-labeled
 * testbed-only diagnostic action. Proves one bounded, live, permission-checked
 * read of a single explicitly-allowlisted Google Drive file - never document
 * search, catalog sync, indexing or an AI answer.
 *
 * <p>Availability is checked ONCE via a trivial dedicated status endpoint
 * before the form is ever shown - a safety-correction fix. The previous
 * version treated every HTTP 500 from the read-check call itself as "this
 * backend doesn't have it", which also hid a genuine runtime error behind
 * the same message. Now: any failure calling the status endpoint means
 * "not registered here" (honestly shown, never faked as success); once that
 * status call has succeeded, a later read-check failure is shown as a real
 * error.</p>
 */
export function TestbedDiagnosticPanel({ sources }: TestbedDiagnosticPanelProps) {
  const apiClient = useApiClient()
  const [availability, setAvailability] = useState<AvailabilityState>('checking')
  const [sourceId, setSourceId] = useState('')
  const [fileId, setFileId] = useState('')
  const [state, setState] = useState<CheckState>({ kind: 'idle' })

  useEffect(() => {
    let cancelled = false
    checkTestbedDiagnosticsStatus(apiClient)
      .then((result) => {
        if (cancelled) return
        setAvailability(result.enabled ? 'available' : 'unavailable')
      })
      .catch(() => {
        if (cancelled) return
        setAvailability('unavailable')
      })
    return () => {
      cancelled = true
    }
  }, [apiClient])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (state.kind === 'checking') {
      return
    }
    const parsedSourceId = Number(sourceId)
    if (!sourceId || Number.isNaN(parsedSourceId) || !fileId.trim()) {
      return
    }
    setState({ kind: 'checking' })
    try {
      const response = await checkTestbedRead(apiClient, parsedSourceId, fileId.trim())
      setState({ kind: 'result', response })
    } catch (error) {
      // 이 시점에는 이미 위 Status 확인이 성공했다 - 즉 이 Endpoint는 실제로
      // 등록되어 있다. 그러므로 여기서 나는 어떤 오류든 "비활성화"가 아니라
      // 실제 오류로 취급한다(예전 버전의 결함 - 매 500을 곧바로 "비활성화"로
      // 오인했다).
      setState({ kind: 'error', message: describeError(error) })
    }
  }

  if (availability === 'checking') {
    return (
      <div className="card">
        <span className="text-secondary">테스트베드 진단 기능 확인 중...</span>
      </div>
    )
  }

  if (availability === 'unavailable') {
    return (
      <div className="card">
        <span className="text-secondary">
          이 백엔드에서는 테스트베드 진단 기능을 사용할 수 없습니다(testbed Profile이 아니거나
          진단 Flag가 꺼져 있음).
        </span>
      </div>
    )
  }

  return (
    <details className="card">
      <summary style={{ cursor: 'pointer', fontWeight: 600 }}>
        테스트베드 진단: 파일 읽기 확인 (Local Testbed 전용)
      </summary>
      <p className="text-secondary" style={{ marginTop: 8 }}>
        문서 검색/색인/AI 답변이 아닙니다 - 미리 지정해 둔 파일 1개를 지금 이 순간 실제로 읽을
        수 있는지만 확인합니다. 몇 KB 수준의 작은 텍스트 파일을 쓰세요 - 이 진단 자체의 상한은
        1MiB이지만, 그 확인은 기존 Connector가 이미 그보다 큰(25MB) 상한으로 파일을 내려받은
        *뒤에* 적용됩니다(사전에 다운로드 자체를 막는 별도 상한이 아닙니다).
      </p>
      <form onSubmit={handleSubmit}>
        <label className="form-label" htmlFor="testbed-diagnostic-source">
          대상 Source
        </label>
        <div className="form-row">
          <select
            id="testbed-diagnostic-source"
            className="input"
            value={sourceId}
            onChange={(event) => setSourceId(event.target.value)}
            disabled={state.kind === 'checking'}
          >
            <option value="">Source 선택...</option>
            {sources.map((source) => (
              <option key={source.id} value={source.id}>
                {source.name} (#{source.id}){source.status !== 'ACTIVE' ? ' - 연결 해제됨' : ''}
              </option>
            ))}
          </select>
        </div>
        <p className="text-secondary" style={{ margin: '4px 0 0', fontSize: 13 }}>
          "연결 해제됨" Source를 일부러 선택하면 실제로 거부되는지(Google 호출 없이) 확인할 수
          있습니다 - Runbook의 읽기 거부 확인 항목이 이 방법을 씁니다.
        </p>
        <label className="form-label" htmlFor="testbed-diagnostic-file-id">
          허용된 Google 파일 ID
        </label>
        <div className="form-row">
          <input
            id="testbed-diagnostic-file-id"
            className="input"
            value={fileId}
            onChange={(event) => setFileId(event.target.value)}
            placeholder="사전에 지정해 둔 File ID"
            disabled={state.kind === 'checking'}
          />
          <button
            type="submit"
            className="btn btn--primary"
            disabled={state.kind === 'checking' || !sourceId || !fileId.trim()}
          >
            {state.kind === 'checking' ? '확인 중...' : '읽기 확인'}
          </button>
        </div>
      </form>

      {state.kind === 'error' && <div className="status-banner status-banner--error">{state.message}</div>}
      {state.kind === 'result' && <DiagnosticResultBanner response={state.response} />}
    </details>
  )
}

function DiagnosticResultBanner({ response }: { response: TestbedReadDiagnosticResponse }) {
  const className = response.success ? 'status-banner' : 'status-banner status-banner--error'
  return (
    <div className={className}>
      <p style={{ margin: 0 }}>
        {response.success ? '읽기 성공' : '읽기 거부/실패'} - outcome: {response.outcome}
      </p>
      <p style={{ margin: '4px 0 0' }}>
        읽은 바이트 수: {response.bytesRead} / Version 검증 통과: {response.versionVerified ? '예' : '아니오'}
      </p>
      {response.reason && <p style={{ margin: '4px 0 0' }}>{response.reason}</p>}
    </div>
  )
}

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'VALIDATION_ERROR':
        return '입력값을 확인해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다.'
      default:
        return '진단 요청 중 실제 오류가 발생했습니다(기능이 비활성화된 것이 아닙니다).'
    }
  }
  return '진단 요청 중 실제 오류가 발생했습니다(기능이 비활성화된 것이 아닙니다).'
}
