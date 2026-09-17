import { useEffect, useRef, useState } from 'react'
import { useApiClient } from '../../api/useApiClient'
import { listFindings, updateFinding } from '../../api/securityFindings'
import type { FindingPage, FindingStatus } from '../../api/securityFindings'

export function SecurityDashboardPage() {
  const client = useApiClient()
  const [page, setPage] = useState(0)
  const [state, setState] = useState<{ loading: boolean; data: FindingPage | null; error: boolean }>({
    loading: true,
    data: null,
    error: false,
  })
  const listRequest = useRef(0)
  const mutations = useRef(new Map<number, AbortController>())
  useEffect(() => {
    const activeMutations = mutations.current
    return () => {
      activeMutations.forEach((controller) => controller.abort())
      activeMutations.clear()
    }
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    const requestId = ++listRequest.current
    listFindings(client, page, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted && listRequest.current === requestId)
          setState({ loading: false, data, error: false })
      })
      .catch((error) => {
        if (
          !controller.signal.aborted &&
          listRequest.current === requestId &&
          !(error instanceof DOMException && error.name === 'AbortError')
        )
          setState({ loading: false, data: null, error: true })
      })
    return () => {
      controller.abort()
      listRequest.current = requestId + 1
    }
  }, [client, page])
  function movePage(nextPage: number) {
    setState((current) => ({ ...current, loading: true, error: false }))
    setPage(nextPage)
  }
  async function change(id: number, status: FindingStatus) {
    mutations.current.get(id)?.abort()
    const controller = new AbortController()
    mutations.current.set(id, controller)
    try {
      const updated = await updateFinding(client, id, status, controller.signal)
      if (controller.signal.aborted || mutations.current.get(id) !== controller) return
      setState((current) =>
        current.data
          ? {
              ...current,
              data: { ...current.data, items: current.data.items.map((item) => (item.id === id ? updated : item)) },
            }
          : current,
      )
    } catch (error) {
      if (
        !controller.signal.aborted &&
        mutations.current.get(id) === controller &&
        !(error instanceof DOMException && error.name === 'AbortError')
      )
        setState((current) => ({ ...current, error: true }))
    } finally {
      if (mutations.current.get(id) === controller) mutations.current.delete(id)
    }
  }
  return (
    <>
      <h1>보안 발견</h1>
      <p className="text-secondary">Provider 권한과 분류 근거의 위험을 보고합니다. 권한을 자동 변경하지 않습니다.</p>
      {state.loading && <div className="status-banner">불러오는 중...</div>}
      {state.error && <div className="status-banner status-banner--error">보안 발견 작업을 완료하지 못했습니다.</div>}
      {state.data && (
        <>
          <ul className="file-list">
            {state.data.items.map((item) => (
              <li className="file-row" key={item.id}>
                <div className="file-row__meta">
                  <strong>
                    {item.severity} · {item.type}
                  </strong>
                  <span>
                    {item.status} · source {item.sourceId ?? '-'} · document {item.documentId ?? '-'}
                  </span>
                </div>
                <div className="file-row__actions">
                  <button className="btn" type="button" onClick={() => void change(item.id, 'ACKNOWLEDGED')}>
                    확인
                  </button>
                  <button className="btn" type="button" onClick={() => void change(item.id, 'RESOLVED')}>
                    해결됨
                  </button>
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
