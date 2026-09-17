import { useEffect, useRef, useState } from 'react'
import { useApiClient } from '../api/useApiClient'

type RegistryStatus = 'READY' | 'CLEARANCE_UNSET' | 'DISABLED' | 'REGISTRY_UNAVAILABLE'
interface MeBootstrapResponse {
  registryStatus: RegistryStatus
}
type BootstrapState = RegistryStatus | 'LOADING' | 'FAILED'

export function SessionBootstrap({ subject }: { subject: string }) {
  const client = useApiClient()
  const [state, setState] = useState<BootstrapState>('LOADING')
  const [retry, setRetry] = useState(0)
  const request = useRef<{
    subject: string
    retry: number
    controller: AbortController
    promise: Promise<MeBootstrapResponse>
  } | null>(null)

  useEffect(() => {
    let current = true
    if (!request.current || request.current.subject !== subject || request.current.retry !== retry) {
      request.current?.controller.abort()
      const controller = new AbortController()
      request.current = {
        subject,
        retry,
        controller,
        promise: client.get<MeBootstrapResponse>('/me', controller.signal),
      }
    }
    request.current.promise
      .then((response) => {
        if (current && request.current?.subject === subject && request.current.retry === retry) {
          setState(response.registryStatus ?? 'REGISTRY_UNAVAILABLE')
        }
      })
      .catch((failure: unknown) => {
        if (!current || (failure instanceof DOMException && failure.name === 'AbortError')) return
        setState('FAILED')
      })
    return () => {
      current = false
    }
  }, [client, retry, subject])

  if (state === 'READY') return null
  if (state === 'LOADING')
    return (
      <div className="status-banner" role="status">
        SDV 사용자 등록 상태를 확인하고 있습니다.
      </div>
    )
  if (state === 'CLEARANCE_UNSET') {
    return (
      <div className="status-banner" role="status">
        SDV 등록은 완료되었지만 읽기 clearance가 아직 지정되지 않았습니다.
      </div>
    )
  }
  if (state === 'DISABLED') {
    return (
      <div className="status-banner status-banner--error" role="alert">
        이 SDV 계정의 공유 콘텐츠 접근이 비활성화되었습니다.
      </div>
    )
  }
  return (
    <div className="status-banner status-banner--error" role="alert">
      SDV 사용자 등록을 확인하지 못했습니다. 공유 검색을 사용하기 전에 다시 확인해 주세요.
      <button
        type="button"
        className="btn"
        onClick={() => {
          setState('LOADING')
          setRetry((value) => value + 1)
        }}
      >
        다시 확인
      </button>
    </div>
  )
}
