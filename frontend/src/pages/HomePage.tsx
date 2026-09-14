import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useApiClient } from '../api/useApiClient'
import { listSources } from '../api/sources'
import type { SourceResponse } from '../api/sources'

type ConnectionSummary = 'loading' | 'error' | 'none' | 'pending' | 'connected'

/**
 * Honest preparation-state Home. No fake composer, no canned answers, no
 * scope selector or citation panel (those belong to a later slice per the
 * approved future layout - this page never implements or stubs them).
 */
export function HomePage() {
  const { isAdmin } = useAuth()
  const apiClient = useApiClient()
  const [summary, setSummary] = useState<ConnectionSummary>(isAdmin ? 'loading' : 'none')

  useEffect(() => {
    if (!isAdmin) {
      return
    }
    let cancelled = false
    // 초기값(useState 위)이 이미 admin일 때 'loading'이므로, 여기서 다시
    // setSummary('loading')을 동기 호출할 필요가 없다 - Effect Body에서의
    // 동기 setState는 Cascading Render를 유발한다(react-hooks/set-state-in-effect).
    listSources(apiClient)
      .then((sources: SourceResponse[]) => {
        if (cancelled) return
        const active = sources.filter((source) => source.status === 'ACTIVE')
        if (active.length === 0) {
          setSummary('none')
        } else if (active.some((source) => source.credentialPresent)) {
          setSummary('connected')
        } else {
          setSummary('pending')
        }
      })
      .catch(() => {
        if (cancelled) return
        setSummary('error')
      })
    return () => {
      cancelled = true
    }
  }, [apiClient, isAdmin])

  return (
    <>
      <h1 className="home-heading">업무 문서에 질문해 보세요.</h1>

      <div className="status-banner">{prepStateMessage(isAdmin, summary)}</div>

      {isAdmin && summary !== 'connected' && (
        <div>
          <Link to="/admin/sources" className="btn btn--primary">
            연결 관리로 이동
          </Link>
        </div>
      )}

      <div className="composer" aria-disabled="true">
        <span>질문 기능은 아직 준비 중입니다.</span>
      </div>
    </>
  )
}

function prepStateMessage(isAdmin: boolean, summary: ConnectionSummary): string {
  if (!isAdmin) {
    return '문서 검색과 답변 기능은 아직 준비 중입니다. 이용을 원하시면 관리자에게 Google Drive 연결을 요청해 주세요.'
  }
  switch (summary) {
    case 'loading':
      return 'Source 연결 상태를 확인하는 중입니다...'
    case 'error':
      return 'Source 연결 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    case 'none':
      return '아직 연결된 Source가 없습니다. 문서 검색과 답변 기능을 준비하려면 먼저 Google Drive를 연결해 주세요.'
    case 'pending':
      return 'Source는 등록되어 있지만 아직 Google 계정 연결이 끝나지 않았습니다. 문서 검색과 답변 기능은 아직 준비 중입니다.'
    case 'connected':
      return 'Google Drive가 연결되어 있습니다. 문서 검색과 답변 기능은 아직 준비 중이며, 곧 제공될 예정입니다.'
  }
}
