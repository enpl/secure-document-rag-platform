import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { CitationList } from './CitationList'

const citation = { documentId: 7, locatorType: 'PAGE', locatorValue: '2', sourceVersion: 'v3', verifiedAt: '2026-09-16T00:00:00Z', downloadUrl: '/api/shares/91/download' }

describe('CitationList', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:safe'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  })
  it('adapts the validated path exactly once and uses authenticated blob download', async () => {
    const getBlob = vi.fn().mockResolvedValue({ blob: new Blob(['x']), filename: 'safe.pdf' })
    render(<CitationList citations={[citation]} client={{ getBlob } as never} />)
    await userEvent.click(screen.getByRole('button', { name: 'SDV 다운로드' }))
    expect(getBlob).toHaveBeenCalledWith('/shares/91/download', expect.any(AbortSignal))
    expect(getBlob.mock.calls[0][0]).not.toContain('/api/api')
  })
  it('keeps a valid citation visible but hides download for an unsafe URL', () => {
    render(<CitationList citations={[{ ...citation, downloadUrl: 'https://evil.invalid/file?token=secret' }]} client={{} as never} />)
    expect(screen.getByText(/문서 7/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'SDV 다운로드' })).not.toBeInTheDocument()
    expect(document.querySelector('[href*="evil.invalid"]')).toBeNull()
  })

  it('clears failed download state under StrictMode and allows a later citation retry', async () => {
    const getBlob = vi.fn().mockRejectedValueOnce(new Error('failed'))
      .mockResolvedValueOnce({ blob: new Blob(['x']), filename: 'safe.pdf' })
    render(<StrictMode><CitationList citations={[citation]} client={{ getBlob } as never} /></StrictMode>)
    await userEvent.click(screen.getByRole('button', { name: 'SDV 다운로드' }))
    expect(await screen.findByText('다운로드 중 알 수 없는 오류가 발생했습니다.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'SDV 다운로드' }))
    await waitFor(() => expect(getBlob).toHaveBeenCalledTimes(2))
  })

  it('does not save citation bytes that arrive after the component was unmounted', async () => {
    let resolve!: (value: { blob: Blob; filename: string }) => void
    const getBlob = vi.fn(() => new Promise((done) => { resolve = done }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const view = render(<CitationList citations={[citation]} client={{ getBlob } as never} />)
    await userEvent.click(screen.getByRole('button', { name: 'SDV 다운로드' }))
    view.unmount()
    resolve({ blob: new Blob(['late']), filename: 'late.pdf' })
    await Promise.resolve()
    expect(click).not.toHaveBeenCalled()
  })
})
