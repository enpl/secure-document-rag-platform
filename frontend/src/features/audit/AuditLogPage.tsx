import { useEffect, useRef, useState } from 'react'
import { useApiClient } from '../../api/useApiClient'
import { listAudits } from '../../api/audit'
import type { AuditPage } from '../../api/audit'

export function AuditLogPage() {
  const client = useApiClient()
  const [page, setPage] = useState(0)
  const [state, setState] = useState<{ loading: boolean; data: AuditPage | null; error: boolean }>({
    loading: true,
    data: null,
    error: false,
  })
  const request = useRef(0)
  useEffect(() => {
    const controller = new AbortController()
    const requestId = ++request.current
    listAudits(client, page, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted && request.current === requestId)
          setState({ loading: false, data, error: false })
      })
      .catch((error) => {
        if (
          !controller.signal.aborted &&
          request.current === requestId &&
          !(error instanceof DOMException && error.name === 'AbortError')
        )
          setState({ loading: false, data: null, error: true })
      })
    return () => {
      controller.abort()
      request.current = requestId + 1
    }
  }, [client, page])
  function movePage(nextPage: number) {
    setState((current) => ({ ...current, loading: true, error: false }))
    setPage(nextPage)
  }
  return (
    <>
      <h1>감사 로그</h1>
      <p className="text-secondary">내용을 저장하지 않는 단계·결정 기록입니다.</p>
      {state.loading && <div className="status-banner">불러오는 중...</div>}
      {state.error && <div className="status-banner status-banner--error">감사 로그를 불러오지 못했습니다.</div>}
      {state.data && (
        <>
          <ul className="file-list">
            {state.data.items.map((item) => (
              <li className="file-row" key={item.id}>
                <div className="file-row__meta">
                  <strong>{item.action}</strong>
                  <span>
                    {item.result} · {item.reasonCode ?? 'OK'}
                  </span>
                  <span className="text-secondary">
                    {item.createdAt} · trace {item.traceId ?? '-'}
                  </span>
                </div>
              </li>
            ))}
          </ul>
          <div className="form-row">
            <button className="btn" type="button" disabled={page === 0} onClick={() => movePage(page - 1)}>
              이전
            </button>
            <button className="btn" type="button" disabled={!state.data.hasMore} onClick={() => movePage(page + 1)}>
              다음
            </button>
          </div>
        </>
      )}
    </>
  )
}
