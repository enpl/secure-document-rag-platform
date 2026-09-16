import { useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../../api/client'
import { triggerBrowserDownload } from '../../api/browserDownload'
import { describeDownloadError } from '../../api/fileDiscovery'
import type { RagCitation } from '../../api/ragAssistant'
import { citationDownloadPath } from '../../api/ragAssistant'

export function CitationList({ citations, client }: { citations: RagCitation[]; client: ApiClient }) {
  const controllers = useRef(new Map<string, AbortController>())
  const mounted = useRef(true)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => {
    const activeControllers = controllers.current
    mounted.current = true
    return () => {
      mounted.current = false
      activeControllers.forEach((item) => item.abort())
      activeControllers.clear()
    }
  }, [])

  async function download(citation: RagCitation) {
    const path = citationDownloadPath(citation.downloadUrl)
    if (!path || controllers.current.has(path)) return
    const controller = new AbortController()
    controllers.current.set(path, controller)
    setMessage(null)
    try {
      const result = await client.getBlob(path, controller.signal)
      if (!mounted.current || controller.signal.aborted || controllers.current.get(path) !== controller) return
      triggerBrowserDownload(result, `document-${citation.documentId}`)
    } catch (error) {
      if (mounted.current && controllers.current.get(path) === controller
        && !(error instanceof DOMException && error.name === 'AbortError')) setMessage(describeDownloadError(error))
    } finally {
      if (controllers.current.get(path) === controller) controllers.current.delete(path)
    }
  }

  if (citations.length === 0) return null
  return (
    <section className="card" aria-labelledby="citations-heading">
      <h2 id="citations-heading">검증된 출처</h2>
      <ul className="citation-list">
        {citations.map((citation, index) => {
          const path = citationDownloadPath(citation.downloadUrl)
          return (
            <li key={`${citation.documentId}-${citation.locatorType}-${citation.locatorValue}-${index}`}>
              <span>문서 {citation.documentId} · {citation.locatorType} {citation.locatorValue}</span>
              <span className="text-secondary">버전 {citation.sourceVersion} · 확인 {citation.verifiedAt}</span>
              {path && <button type="button" className="btn" onClick={() => void download(citation)}>SDV 다운로드</button>}
            </li>
          )
        })}
      </ul>
      {message && <div className="status-banner status-banner--error">{message}</div>}
    </section>
  )
}
