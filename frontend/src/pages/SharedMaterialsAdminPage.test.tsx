import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SharedMaterialsAdminPage } from './SharedMaterialsAdminPage'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import { listAdminShares, setShareBlocked } from '../api/shares'
import type { AdminShareResponse } from '../api/shares'
import { ApiError } from '../api/client'

vi.mock('../auth/AuthContext')
vi.mock('../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../api/shares', async () => {
  const actual = await vi.importActual<typeof import('../api/shares')>('../api/shares')
  return { ...actual, listAdminShares: vi.fn(), setShareBlocked: vi.fn() }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedList = vi.mocked(listAdminShares)
const mockedSetBlocked = vi.mocked(setShareBlocked)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function adminShare(overrides: Partial<AdminShareResponse> = {}): AdminShareResponse {
  return {
    id: 1,
    publisherSubject: 'publisher-a',
    sourceId: 10,
    documentId: 20,
    classification: 'INTERNAL',
    allowedActions: ['VIEW'],
    recipients: ['recipient-b'],
    adminBlocked: false,
    adminBlockReason: null,
    generation: 1,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SharedMaterialsAdminPage role gating', () => {
  it('shows an honest limited message for a non-admin and never calls the admin-only list API', () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))

    render(<SharedMaterialsAdminPage />)

    expect(screen.getByText(/관리자만 사용할 수 있습니다/)).toBeInTheDocument()
    expect(mockedList).not.toHaveBeenCalled()
  })
})

describe('SharedMaterialsAdminPage admin management', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
  })

  it('shows the empty state when there are no published shares', async () => {
    mockedList.mockResolvedValue([])

    render(<SharedMaterialsAdminPage />)

    await waitFor(() => expect(screen.getByText('현재 게시된 공유가 없습니다.')).toBeInTheDocument())
  })

  it('never exposes another owner\'s private Drive listing or lets the admin widen recipients/actions', async () => {
    mockedList.mockResolvedValue([adminShare()])

    render(<SharedMaterialsAdminPage />)

    await waitFor(() => expect(screen.getByText('게시자: publisher-a')).toBeInTheDocument())
    // 이 화면에는 등급/행위/수신자를 편집할 어떤 입력 요소도 없다 - 차단/해제 Action만 있다.
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/수신자/)).not.toBeInTheDocument()
  })

  it('blocks a published share with an optional reason', async () => {
    mockedList.mockResolvedValue([adminShare()])
    mockedSetBlocked.mockResolvedValue(adminShare({ adminBlocked: true, adminBlockReason: '정책 위반' }))

    render(<SharedMaterialsAdminPage />)
    await waitFor(() => expect(screen.getByText('게시자: publisher-a')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '차단' }))
    await user.type(screen.getByPlaceholderText('차단 사유(선택)'), '정책 위반')
    mockedList.mockResolvedValueOnce([adminShare({ adminBlocked: true, adminBlockReason: '정책 위반' })])
    await user.click(screen.getByRole('button', { name: '차단 확인' }))

    await waitFor(() => expect(mockedSetBlocked).toHaveBeenCalledWith(expect.anything(), 1, true, '정책 위반'))
    await waitFor(() => expect(screen.getByText(/차단됨 - 정책 위반/)).toBeInTheDocument())
  })

  it('unblocks a share without reviving recipients/actions the publisher never set', async () => {
    mockedList.mockResolvedValue([adminShare({ adminBlocked: true, adminBlockReason: '검토 중' })])
    mockedSetBlocked.mockResolvedValue(adminShare({ adminBlocked: false }))

    render(<SharedMaterialsAdminPage />)
    await waitFor(() => expect(screen.getByText(/차단됨 - 검토 중/)).toBeInTheDocument())

    mockedList.mockResolvedValueOnce([adminShare({ adminBlocked: false })])
    await userEvent.setup().click(screen.getByRole('button', { name: '차단 해제' }))

    await waitFor(() => expect(mockedSetBlocked).toHaveBeenCalledWith(expect.anything(), 1, false))
    await waitFor(() => expect(screen.getByText('정상 게시 중')).toBeInTheDocument())
  })

  it('reports a stale/revoked target honestly instead of pretending the block succeeded', async () => {
    mockedList.mockResolvedValue([adminShare()])
    mockedSetBlocked.mockRejectedValue(new ApiError(404, { code: 'NOT_FOUND', message: 'x', traceId: null }))

    render(<SharedMaterialsAdminPage />)
    await waitFor(() => expect(screen.getByText('게시자: publisher-a')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '차단' }))
    await user.click(screen.getByRole('button', { name: '차단 확인' }))

    await waitFor(() =>
      expect(screen.getByText(/이미 철회됐을 수 있습니다\. 새로고침해 주세요\./)).toBeInTheDocument(),
    )
  })
})
