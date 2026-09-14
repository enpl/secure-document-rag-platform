import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { HomePage } from './HomePage'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import { listSources } from '../api/sources'
import type { SourceResponse } from '../api/sources'

vi.mock('../auth/AuthContext')
// SourcesPage.test.tsx와 같은 이유 - 항상 같은 참조를 반환해야 무한 Refetch
// Loop을 피한다.
vi.mock('../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../api/sources', async () => {
  const actual = await vi.importActual<typeof import('../api/sources')>('../api/sources')
  return { ...actual, listSources: vi.fn() }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedListSources = vi.mocked(listSources)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function source(overrides: Partial<SourceResponse>): SourceResponse {
  return { id: 1, type: 'GOOGLE_DRIVE', name: 'x', status: 'ACTIVE', lastSyncAt: null, credentialPresent: false, ...overrides }
}

function renderHome() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('HomePage', () => {
  it('never implies a working chat and never calls the admin-only list API for a non-admin', () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))

    renderHome()

    expect(screen.getByText('업무 문서에 질문해 보세요.')).toBeInTheDocument()
    expect(screen.getByText(/문서 검색과 답변 기능은 아직 준비 중입니다/)).toBeInTheDocument()
    expect(screen.getByText('질문 기능은 아직 준비 중입니다.')).toBeInTheDocument()
    expect(mockedListSources).not.toHaveBeenCalled()
    expect(screen.queryByRole('link', { name: '연결 관리로 이동' })).not.toBeInTheDocument()
  })

  it('tells an admin with no active source to connect one first', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedListSources.mockResolvedValue([])

    renderHome()

    await waitFor(() => expect(screen.getByText(/아직 연결된 Source가 없습니다/)).toBeInTheDocument())
    expect(screen.getByRole('link', { name: '연결 관리로 이동' })).toBeInTheDocument()
  })

  it('distinguishes a registered-but-not-yet-connected source from a fully connected one', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedListSources.mockResolvedValue([source({ credentialPresent: false })])

    renderHome()

    await waitFor(() => expect(screen.getByText(/아직 Google 계정 연결이 끝나지 않았습니다/)).toBeInTheDocument())
  })

  it('shows the connected message without claiming search/answers are ready', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedListSources.mockResolvedValue([source({ credentialPresent: true })])

    renderHome()

    await waitFor(() => expect(screen.getByText(/Google Drive가 연결되어 있습니다/)).toBeInTheDocument())
    expect(screen.getByText(/아직 준비 중이며/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '연결 관리로 이동' })).not.toBeInTheDocument()
  })
})
