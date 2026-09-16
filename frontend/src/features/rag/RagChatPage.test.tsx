import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { RagChatPage } from './RagChatPage'

const post = vi.fn()
const getBlob = vi.fn()
const stableClient = { post, getBlob }
vi.mock('../../api/useApiClient', () => ({ useApiClient: () => stableClient }))

const base = { status: 'SUCCESS', reasonCode: null, answer: '답변', generatedAnalysis: null, citations: [], files: null, partial: false }
const downloadableFile = { documentId: 12, sourceId: 1, name: '정책.pdf', mimeType: 'application/pdf', sourceVersion: 'v1', modifiedAt: null, indexStatus: 'INDEXED', sourceVersionCurrent: true, downloadable: true, viewUrl: '', shareId: 81, allowedActions: ['VIEW', 'DOWNLOAD'] }
beforeEach(() => { post.mockReset(); getBlob.mockReset() })

describe('RagChatPage', () => {
  it('uses a server-returned file id only after the user selects it and can remove selection', async () => {
    post.mockResolvedValueOnce({ ...base, answer: null, files: { items: [{ documentId: 12, sourceId: 1, name: '정책.pdf', mimeType: 'application/pdf', sourceVersion: 'v1', modifiedAt: null, indexStatus: 'INDEXED', sourceVersionCurrent: true, downloadable: false, viewUrl: '', shareId: 81, allowedActions: ['VIEW'] }], hasMore: false, partial: false } })
      .mockResolvedValueOnce(base)
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '정책 파일 찾아줘')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await userEvent.click(await screen.findByRole('button', { name: '질문 대상으로 선택' }))
    await userEvent.clear(screen.getByLabelText('질문'))
    await userEvent.type(screen.getByLabelText('질문'), '핵심은?')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await waitFor(() => expect(post).toHaveBeenLastCalledWith('/rag/ask', { question: '핵심은?', selectedDocumentIds: [12] }, expect.any(AbortSignal)))
    await userEvent.click(screen.getByRole('button', { name: '정책.pdf 선택 해제' }))
    expect(screen.queryByLabelText('선택 문서')).not.toBeInTheDocument()
  })

  it('shows answer and AI-generated analysis separately and preserves incomplete file coverage', async () => {
    post.mockResolvedValue({ ...base, status: 'PARTIAL', reasonCode: 'COMPARISON_INPUT_INCOMPLETE', answer: null,
      generatedAnalysis: '제안', partial: true, files: { items: [], hasMore: null, partial: true } })
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '두 문서 비교')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    expect(await screen.findByText(/비교 대상 모두/)).toBeInTheDocument()
    expect(screen.getByText('AI 생성 분석·제안')).toBeInTheDocument()
    expect(screen.getByText(/일부 검색 범위만 확인/)).toBeInTheDocument()
    expect(screen.queryByText('근거 기반 답변')).not.toBeInTheDocument()
  })

  it('does not describe nested partial or unknown file coverage as a completed search', async () => {
    post.mockResolvedValueOnce({ ...base, answer: null, files: { items: [downloadableFile], hasMore: false, partial: true } })
      .mockResolvedValueOnce({ ...base, answer: null, files: { items: [], hasMore: null, partial: false } })
      .mockResolvedValueOnce({ ...base, answer: null, files: { items: [downloadableFile], hasMore: false, partial: false } })
    const view = render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '일부 파일 찾기')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    expect(await screen.findByText('일부 검색 범위만 확인했습니다. 표시된 파일은 사용할 수 있습니다.')).toBeInTheDocument()
    expect(screen.queryByText('답변을 완료했습니다.')).not.toBeInTheDocument()

    view.unmount()
    const unknown = render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '범위 미확인 파일 찾기')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    expect(await screen.findByText('검색 범위의 완전성을 확인할 수 없습니다. 표시된 결과만 확인해 주세요.')).toBeInTheDocument()
    expect(screen.queryByText('조건에 맞는 파일이 없습니다.')).not.toBeInTheDocument()
    expect(screen.queryByText('답변을 완료했습니다.')).not.toBeInTheDocument()

    unknown.unmount()
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '완전한 파일 찾기')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await screen.findByText('정책.pdf')
    expect(screen.queryByText('일부 검색 범위만 확인했습니다. 표시된 파일은 사용할 수 있습니다.')).not.toBeInTheDocument()
    expect(screen.queryByText('검색 범위의 완전성을 확인할 수 없습니다. 표시된 결과만 확인해 주세요.')).not.toBeInTheDocument()
  })

  it('prevents duplicate submission and aborts cancellation while ignoring a late result', async () => {
    let resolve!: (value: typeof base) => void
    post.mockImplementation(() => new Promise((done) => { resolve = done }))
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '느린 질문')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    expect(screen.getByRole('button', { name: '확인 중...' })).toBeDisabled()
    expect(post).toHaveBeenCalledTimes(1)
    const signal = post.mock.calls[0][2] as AbortSignal
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(signal.aborted).toBe(true)
    resolve(base)
    await Promise.resolve()
    expect(screen.queryByText('답변')).not.toBeInTheDocument()
  })

  it('never writes question content to browser persistence', async () => {
    const local = vi.spyOn(Storage.prototype, 'setItem')
    post.mockResolvedValue(base)
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '저장하면 안 되는 질문')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await screen.findByText('답변')
    expect(local).not.toHaveBeenCalled()
    local.mockRestore()
  })

  it('retries with exactly the same input only after the user asks', async () => {
    post.mockResolvedValueOnce({ ...base, status: 'FAILED', reasonCode: 'REQUEST_TIMEOUT', answer: null })
      .mockResolvedValueOnce(base)
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '같은 질문')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await userEvent.click(await screen.findByRole('button', { name: '같은 내용으로 다시 시도' }))
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2))
    expect(post.mock.calls[0][1]).toEqual(post.mock.calls[1][1])
  })

  it('aborts work on unmount so identity change or logout cannot apply a late response', async () => {
    post.mockImplementation(() => new Promise(() => undefined))
    const view = render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '계정 전환 전 질문')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    const signal = post.mock.calls[0][2] as AbortSignal
    view.unmount()
    expect(signal.aborted).toBe(true)
  })

  it('downloads a file result only with its server shareId and DOWNLOAD hint', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:file')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    getBlob.mockResolvedValue({ blob: new Blob(['x']), filename: 'policy.pdf' })
    post.mockResolvedValue({ ...base, answer: null, files: { items: [downloadableFile], hasMore: false, partial: false } })
    render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '정책 파일 찾아줘')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await userEvent.click(await screen.findByRole('button', { name: 'SDV 다운로드' }))
    expect(getBlob).toHaveBeenCalledWith('/shares/81/download', expect.any(AbortSignal))
  })

  it('is StrictMode-safe after a failed file download and permits an explicit retry', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:file')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    getBlob.mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce({ blob: new Blob(['x']), filename: 'policy.pdf' })
    post.mockResolvedValue({ ...base, answer: null, files: { items: [downloadableFile], hasMore: false, partial: false } })
    render(<StrictMode><RagChatPage /></StrictMode>)
    await userEvent.type(screen.getByLabelText('질문'), '정책 파일 찾아줘')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await userEvent.click(await screen.findByRole('button', { name: 'SDV 다운로드' }))
    expect(await screen.findByText('다운로드 중 알 수 없는 오류가 발생했습니다.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'SDV 다운로드' }))
    await waitFor(() => expect(getBlob).toHaveBeenCalledTimes(2))
  })

  it('does not trigger a browser save when a file download resolves after unmount', async () => {
    let resolve!: (value: { blob: Blob; filename: string }) => void
    getBlob.mockImplementation(() => new Promise((done) => { resolve = done }))
    post.mockResolvedValue({ ...base, answer: null, files: { items: [downloadableFile], hasMore: false, partial: false } })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    URL.createObjectURL = vi.fn(() => 'blob:file')
    URL.revokeObjectURL = vi.fn()
    const view = render(<RagChatPage />)
    await userEvent.type(screen.getByLabelText('질문'), '정책 파일 찾아줘')
    await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
    await userEvent.click(await screen.findByRole('button', { name: 'SDV 다운로드' }))
    view.unmount()
    resolve({ blob: new Blob(['late']), filename: 'late.pdf' })
    await Promise.resolve()
    expect(click).not.toHaveBeenCalled()
  })
})
