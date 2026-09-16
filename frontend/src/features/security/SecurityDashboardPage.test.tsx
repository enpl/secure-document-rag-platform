import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SecurityDashboardPage } from './SecurityDashboardPage'

const get = vi.fn()
const patch = vi.fn()
const client = { get, patch }
vi.mock('../../api/useApiClient', () => ({ useApiClient: () => client }))
beforeEach(() => { get.mockReset(); patch.mockReset() })

describe('SecurityDashboardPage', () => {
  it('lists a finding and updates only its SDV finding state', async () => {
    const finding = { id: 3, type: 'BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION', severity: 'HIGH', status: 'OPEN', sourceId: 1, documentId: 2, evidence: { classification: 'SECRET', principalType: 'ANYONE' }, detectedAt: '2026-09-16T00:00:00Z' }
    get.mockResolvedValue({ items: [finding], page: 0, size: 50, hasMore: false })
    patch.mockResolvedValue({ ...finding, status: 'ACKNOWLEDGED' })
    render(<SecurityDashboardPage />)
    await userEvent.click(await screen.findByRole('button', { name: '확인' }))
    expect(patch).toHaveBeenCalledWith('/admin/security/findings/3', { status: 'ACKNOWLEDGED' }, expect.any(AbortSignal))
    expect(await screen.findByText(/ACKNOWLEDGED/)).toBeInTheDocument()
  })

  it('aborts a finding update on unmount and ignores its late completion', async () => {
    const finding = { id: 3, type: 'BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION', severity: 'HIGH', status: 'OPEN', sourceId: 1, documentId: 2, evidence: {}, detectedAt: '2026-09-16T00:00:00Z' }
    let resolve!: (value: typeof finding) => void
    get.mockResolvedValue({ items: [finding], page: 0, size: 50, hasMore: false })
    patch.mockImplementation(() => new Promise((done) => { resolve = done }))
    const view = render(<SecurityDashboardPage />)
    await userEvent.click(await screen.findByRole('button', { name: '확인' }))
    const signal = patch.mock.calls[0][2] as AbortSignal
    view.unmount()
    expect(signal.aborted).toBe(true)
    resolve({ ...finding, status: 'ACKNOWLEDGED' })
    await Promise.resolve()
  })

  it('keeps the newest requested page when an older page resolves later', async () => {
    const first = { id: 1, type: 'FIRST', severity: 'MEDIUM', status: 'OPEN', sourceId: 1, documentId: 1, evidence: {}, detectedAt: '2026-09-16T00:00:00Z' }
    const oldNext = { id: 2, type: 'OLD_NEXT', severity: 'HIGH', status: 'OPEN', sourceId: 1, documentId: 2, evidence: {}, detectedAt: '2026-09-16T00:00:00Z' }
    const newest = { id: 3, type: 'NEWEST_PAGE_ZERO', severity: 'MEDIUM', status: 'OPEN', sourceId: 1, documentId: 3, evidence: {}, detectedAt: '2026-09-16T00:00:00Z' }
    let resolveOldNext!: (value: unknown) => void
    let resolveNewest!: (value: unknown) => void
    get.mockResolvedValueOnce({ items: [first], page: 0, size: 50, hasMore: true })
      .mockImplementationOnce(() => new Promise((done) => { resolveOldNext = done }))
      .mockImplementationOnce(() => new Promise((done) => { resolveNewest = done }))
    render(<SecurityDashboardPage />)
    await userEvent.click(await screen.findByRole('button', { name: '다음' }))
    await userEvent.click(screen.getByRole('button', { name: '이전' }))
    resolveNewest({ items: [newest], page: 0, size: 50, hasMore: false })
    expect(await screen.findByText(/NEWEST_PAGE_ZERO/)).toBeInTheDocument()
    resolveOldNext({ items: [oldNext], page: 1, size: 50, hasMore: false })
    await waitFor(() => expect(screen.queryByText(/OLD_NEXT/)).not.toBeInTheDocument())
  })
})
