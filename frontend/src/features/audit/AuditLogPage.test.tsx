import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditLogPage } from './AuditLogPage'

const get = vi.fn()
const client = { get }
vi.mock('../../api/useApiClient', () => ({ useApiClient: () => client }))
beforeEach(() => get.mockReset())

describe('AuditLogPage', () => {
  it('renders bounded audit DTO fields without exposing metadata canary content', async () => {
    get.mockResolvedValue({ items: [{ id: 1, actor: 'user-b', action: 'RAG_FINAL_OUTCOME', targetType: 'RAG_REQUEST', targetId: 'ask', result: 'PARTIAL', reasonCode: 'REQUEST_TIMEOUT', traceId: 'trace-1', metadata: { forbidden: 'CONTENT_CANARY' }, createdAt: '2026-09-16T00:00:00Z' }], page: 0, size: 50, hasMore: false })
    render(<AuditLogPage />)
    expect(await screen.findByText('RAG_FINAL_OUTCOME')).toBeInTheDocument()
    expect(screen.getByText(/PARTIAL/)).toBeInTheDocument()
    expect(screen.queryByText(/CONTENT_CANARY/)).not.toBeInTheDocument()
  })

  it('ignores an older pagination response after the user has returned to the newer page', async () => {
    const row = (id: number, action: string) => ({ id, actor: 'admin', action, targetType: 'SOURCE', targetId: '1', result: 'SUCCESS', reasonCode: 'OK', traceId: null, metadata: {}, createdAt: '2026-09-16T00:00:00Z' })
    let resolveOldNext!: (value: unknown) => void
    let resolveNewest!: (value: unknown) => void
    get.mockResolvedValueOnce({ items: [row(1, 'INITIAL')], page: 0, size: 50, hasMore: true })
      .mockImplementationOnce(() => new Promise((done) => { resolveOldNext = done }))
      .mockImplementationOnce(() => new Promise((done) => { resolveNewest = done }))
    render(<AuditLogPage />)
    await userEvent.click(await screen.findByRole('button', { name: '다음' }))
    await userEvent.click(screen.getByRole('button', { name: '이전' }))
    resolveNewest({ items: [row(3, 'NEWEST_PAGE_ZERO')], page: 0, size: 50, hasMore: false })
    expect(await screen.findByText('NEWEST_PAGE_ZERO')).toBeInTheDocument()
    resolveOldNext({ items: [row(2, 'OLD_NEXT')], page: 1, size: 50, hasMore: false })
    await waitFor(() => expect(screen.queryByText('OLD_NEXT')).not.toBeInTheDocument())
  })
})
