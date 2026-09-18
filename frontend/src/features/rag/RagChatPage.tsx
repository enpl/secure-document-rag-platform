import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useApiClient } from '../../api/useApiClient'
import { ApiError } from '../../api/client'
import type { RagAnswerResponse } from '../../api/ragAssistant'
import { askRag, describeRagOutcome, ragOutcomeSeverity } from '../../api/ragAssistant'
import type { RagFileItem } from '../../api/fileDiscovery'
import { describeDownloadError, downloadSharedFile } from '../../api/fileDiscovery'
import { triggerBrowserDownload } from '../../api/browserDownload'
import { CitationList } from './CitationList'

type ViewState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'loaded'; response: RagAnswerResponse }
  | { kind: 'error'; message: string }

export function RagChatPage() {
  const client = useApiClient()
  const [question, setQuestion] = useState('')
  const [selected, setSelected] = useState<RagFileItem[]>([])
  const [view, setView] = useState<ViewState>({ kind: 'idle' })
  const [lastInput, setLastInput] = useState<{ question: string; ids: number[] } | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const requestRef = useRef(0)

  useEffect(
    () => () => {
      requestRef.current++
      controllerRef.current?.abort()
    },
    [],
  )

  async function run(input: { question: string; ids: number[] }) {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    const requestId = ++requestRef.current
    setLastInput(input)
    setView({ kind: 'loading' })
    try {
      const response = await askRag(client, input.question, input.ids, controller.signal)
      if (!controller.signal.aborted && requestRef.current === requestId) setView({ kind: 'loaded', response })
    } catch (error) {
      if (controller.signal.aborted || requestRef.current !== requestId) return
      const message =
        error instanceof ApiError
          ? error.code === 'AUTHENTICATION_REQUIRED'
            ? '로그인이 만료되었습니다. 다시 로그인해 주세요.'
            : error.message
          : '요청 중 네트워크 오류가 발생했습니다.'
      setView({ kind: 'error', message })
    } finally {
      if (requestRef.current === requestId) controllerRef.current = null
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = question.trim()
    if (!value || view.kind === 'loading') return
    void run({ question: value, ids: selected.map((item) => item.documentId) })
  }

  function select(item: RagFileItem) {
    setSelected((current) =>
      current.some((entry) => entry.documentId === item.documentId) ? current : [...current, item],
    )
  }

  return (
    <>
      <h1 className="home-heading">업무 문서를 찾고 질문해 보세요.</h1>
      <p className="text-secondary">
        선택 없이 질문하면 현재 공유받은 범위에서 찾습니다. 선택은 검색 범위를 좁힐 뿐 권한을 만들지 않습니다.
      </p>
      <div className="example-prompts" aria-label="질문 예시">
        {['어제 수정된 PDF 파일 찾아줘', '보안 정책의 핵심을 요약해줘', '선택한 두 문서를 비교해줘'].map((example) => (
          <button type="button" className="btn" key={example} onClick={() => setQuestion(example)}>
            {example}
          </button>
        ))}
      </div>
      {selected.length > 0 && (
        <div className="selected-documents" aria-label="선택 문서">
          {selected.map((item) => (
            <button
              type="button"
              className="pill"
              key={item.documentId}
              onClick={() => setSelected((current) => current.filter((entry) => entry.documentId !== item.documentId))}
            >
              {item.name} 선택 해제
            </button>
          ))}
          <button type="button" className="btn" onClick={() => setSelected([])}>
            선택 모두 지우기
          </button>
        </div>
      )}
      <form className="card rag-composer" onSubmit={submit}>
        <label className="form-label" htmlFor="rag-question">
          질문
        </label>
        <textarea
          id="rag-question"
          className="input"
          rows={4}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={4000}
        />
        <div className="form-row">
          <button type="submit" className="btn btn--primary" disabled={!question.trim() || view.kind === 'loading'}>
            {view.kind === 'loading' ? '확인 중...' : '질문 보내기'}
          </button>
          {view.kind === 'loading' && (
            <button
              type="button"
              className="btn"
              onClick={() => {
                requestRef.current++
                controllerRef.current?.abort()
                controllerRef.current = null
                setView({ kind: 'idle' })
              }}
            >
              취소
            </button>
          )}
          {(view.kind === 'error' || (view.kind === 'loaded' && view.response.status !== 'SUCCESS')) && lastInput && (
            <button type="button" className="btn" onClick={() => void run(lastInput)}>
              같은 내용으로 다시 시도
            </button>
          )}
        </div>
      </form>
      {view.kind === 'loading' && (
        <div className="status-banner" aria-live="polite">
          질문을 확인하고 있습니다...
        </div>
      )}
      {view.kind === 'error' && <div className="status-banner status-banner--error">{view.message}</div>}
      {view.kind === 'loaded' && <RagResult response={view.response} client={client} onSelect={select} />}
    </>
  )
}

/** 'info'는 기존 중립 배너(`.status-banner`) 그대로 - 새 값을 지어내지 않고 심각도만 색으로 구분한다. */
function statusBannerClassName(severity: 'error' | 'warning' | 'info'): string {
  return severity === 'info' ? 'status-banner' : `status-banner status-banner--${severity}`
}

function RagResult({
  response,
  client,
  onSelect,
}: {
  response: RagAnswerResponse
  client: ReturnType<typeof useApiClient>
  onSelect: (item: RagFileItem) => void
}) {
  const outcome = describeRagOutcome(response.status, response.reasonCode)
  const fileCoverageMessage = response.files?.partial
    ? '일부 검색 범위만 확인했습니다. 표시된 파일은 사용할 수 있습니다.'
    : response.files?.hasMore === null
      ? '검색 범위의 완전성을 확인할 수 없습니다. 표시된 결과만 확인해 주세요.'
      : null
  const [downloadErrors, setDownloadErrors] = useState<Record<number, string>>({})
  const [downloading, setDownloading] = useState<Set<number>>(new Set())
  const downloads = useRef(new Map<number, AbortController>())
  const mounted = useRef(true)
  useEffect(() => {
    const activeDownloads = downloads.current
    mounted.current = true
    return () => {
      mounted.current = false
      activeDownloads.forEach((controller) => controller.abort())
      activeDownloads.clear()
    }
  }, [response])
  async function download(item: RagFileItem) {
    if (downloads.current.has(item.shareId)) return
    const controller = new AbortController()
    downloads.current.set(item.shareId, controller)
    setDownloading((current) => new Set(current).add(item.shareId))
    setDownloadErrors((current) => {
      const next = { ...current }
      delete next[item.shareId]
      return next
    })
    try {
      const result = await downloadSharedFile(client, item.shareId, controller.signal)
      if (!mounted.current || controller.signal.aborted || downloads.current.get(item.shareId) !== controller) return
      triggerBrowserDownload(result, item.name)
    } catch (error) {
      if (
        mounted.current &&
        downloads.current.get(item.shareId) === controller &&
        !(error instanceof DOMException && error.name === 'AbortError')
      )
        setDownloadErrors((current) => ({ ...current, [item.shareId]: describeDownloadError(error) }))
    } finally {
      if (downloads.current.get(item.shareId) === controller) downloads.current.delete(item.shareId)
      if (mounted.current)
        setDownloading((current) => {
          const next = new Set(current)
          next.delete(item.shareId)
          return next
        })
    }
  }
  return (
    <section className="rag-result" aria-live="polite">
      {(response.status !== 'SUCCESS' || response.partial) && (
        <div className={statusBannerClassName(ragOutcomeSeverity(response.status))}>{outcome}</div>
      )}
      {response.answer && (
        <div className="card">
          <h2>근거 기반 답변</h2>
          <div className="inert-text">{response.answer}</div>
        </div>
      )}
      {response.generatedAnalysis && (
        <div className="card">
          <h2>AI 생성 분석·제안</h2>
          <p className="text-secondary">아래 내용은 출처의 사실 그 자체가 아니라 AI가 생성한 분석입니다.</p>
          <div className="inert-text">{response.generatedAnalysis}</div>
        </div>
      )}
      {response.files && (
        <section className="card">
          <h2>찾은 파일</h2>
          {fileCoverageMessage && <div className="status-banner status-banner--warning">{fileCoverageMessage}</div>}
          {response.files.items.length === 0 && !fileCoverageMessage && <p>조건에 맞는 파일이 없습니다.</p>}
          <ul className="file-list">
            {response.files.items.map((item) => (
              <li className="file-row" key={item.documentId}>
                <div className="file-row__meta">
                  <span className="file-row__name">{item.name}</span>
                  {downloadErrors[item.shareId] && (
                    <span className="status-banner status-banner--error">{downloadErrors[item.shareId]}</span>
                  )}
                </div>
                <div className="file-row__actions">
                  <button type="button" className="btn" onClick={() => onSelect(item)}>
                    질문 대상으로 선택
                  </button>
                  {item.allowedActions.includes('DOWNLOAD') && (
                    <button
                      type="button"
                      className="btn btn--primary"
                      disabled={downloading.has(item.shareId)}
                      onClick={() => void download(item)}
                    >
                      {downloading.has(item.shareId) ? '다운로드 중...' : 'SDV 다운로드'}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
          {response.files.hasMore === true && <p className="text-secondary">더 많은 결과가 있습니다.</p>}
        </section>
      )}
      <CitationList citations={response.citations} client={client} />
    </section>
  )
}
