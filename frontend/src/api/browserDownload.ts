import type { BlobResult } from './client'

export function triggerBrowserDownload(result: BlobResult, fallbackName: string): void {
  const filename = safeDownloadFilename(result.filename) ?? safeDownloadFilename(fallbackName) ?? 'download'
  const url = URL.createObjectURL(result.blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

function safeDownloadFilename(candidate: string | null): string | null {
  if (!candidate) return null
  const cleaned = candidate.replace(/[\\/:*?"<>|\r\n]/g, '').trim()
  return cleaned.length > 0 ? cleaned : null
}
