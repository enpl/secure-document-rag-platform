import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SourcesPage } from './SourcesPage'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import {
  authorizeGoogleSource,
  createGoogleDriveSource,
  disconnectSource,
  listSources,
  syncSource,
} from '../api/sources'
import type { SourceResponse, SyncRunResponse } from '../api/sources'
import { ApiError } from '../api/client'

vi.mock('../auth/AuthContext')
// 매 Render마다 새 객체를 반환하면 이 객체가 바뀔 때마다 SourcesPage의
// `useEffect(refresh, [apiClient])`가 다시 실행되어 무한 Refetch Loop에
// 빠진다 - 실제 useApiClient(useMemo)처럼 항상 같은 참조를 반환해야 한다.
vi.mock('../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../api/sources', async () => {
  const actual = await vi.importActual<typeof import('../api/sources')>('../api/sources')
  return {
    ...actual,
    listSources: vi.fn(),
    createGoogleDriveSource: vi.fn(),
    disconnectSource: vi.fn(),
    authorizeGoogleSource: vi.fn(),
    syncSource: vi.fn(),
  }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedListSources = vi.mocked(listSources)
const mockedCreate = vi.mocked(createGoogleDriveSource)
const mockedDisconnect = vi.mocked(disconnectSource)
const mockedAuthorize = vi.mocked(authorizeGoogleSource)
const mockedSync = vi.mocked(syncSource)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/admin/sources']}>
      <SourcesPage />
    </MemoryRouter>,
  )
}

function renderPageWithCallback(flag: 'success' | 'failed') {
  return render(
    <MemoryRouter initialEntries={[`/admin/sources?googleConnect=${flag}`]}>
      <SourcesPage />
    </MemoryRouter>,
  )
}

function activeUnconnectedSource(id: number, name: string): SourceResponse {
  return { id, type: 'GOOGLE_DRIVE', name, status: 'ACTIVE', lastSyncAt: null, credentialPresent: false }
}

function activeConnectedSource(id: number, name: string): SourceResponse {
  return { id, type: 'GOOGLE_DRIVE', name, status: 'ACTIVE', lastSyncAt: null, credentialPresent: true }
}

function syncRun(overrides: Partial<SyncRunResponse> = {}): SyncRunResponse {
  return {
    runId: 1,
    sourceId: 1,
    mode: 'FULL',
    status: 'COMPLETED',
    total: 10,
    success: 10,
    failed: 0,
    startedAt: '2026-09-15T00:00:00Z',
    endedAt: '2026-09-15T00:00:05Z',
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SourcesPage role gating', () => {
  it('shows an honest limited message for a non-admin and never calls the admin-only list API', () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))

    renderPage()

    expect(screen.getByText(/관리자만 사용할 수 있습니다/)).toBeInTheDocument()
    expect(mockedListSources).not.toHaveBeenCalled()
  })
})

describe('SourcesPage admin states', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
  })

  it('shows loading then the empty state when there are no sources', async () => {
    mockedListSources.mockResolvedValue([])

    renderPage()

    expect(screen.getByText(/불러오는 중/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText(/등록된 Source가 없습니다/)).toBeInTheDocument())
  });

  it('hides the testbed-only diagnostic panel outside testbed mode (M16A follow-up)', async () => {
    mockedListSources.mockResolvedValue([activeUnconnectedSource(1, '팀 드라이브')])

    renderPage()

    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    // VITE_TESTBED_MODE는 이 Test 환경에서 설정돼 있지 않다 - 패널이 아예 렌더링되면 안 된다.
    expect(screen.queryByText(/테스트베드 진단/)).not.toBeInTheDocument()
  })

  it('shows a neutral authentication message, never claiming expiry, for AUTHENTICATION_REQUIRED (M16A follow-up)', async () => {
    // 실제 관찰된 문제: issuer/audience/ADMIN Role은 정상이지만 sub가 없는 Token도
    // AUTHENTICATION_REQUIRED로 거부될 수 있다 - 이는 "만료"가 아니다. 그러니 이
    // 코드에 대해 만료를 단정하는 문구를 보여주면 안 된다.
    mockedListSources.mockRejectedValue(
      new ApiError(401, { code: 'AUTHENTICATION_REQUIRED', message: 'x', traceId: null }),
    )

    renderPage()

    await waitFor(() =>
      expect(screen.getByText(/로그인 인증을 확인하지 못했습니다\. 다시 로그인해 주세요\./)).toBeInTheDocument(),
    )
    expect(screen.queryByText(/만료/)).not.toBeInTheDocument()
  })

  it('shows an error state with a retry action that calls the list API again', async () => {
    mockedListSources
      .mockRejectedValueOnce(new ApiError(500, { code: 'INTERNAL_ERROR', message: 'boom', traceId: 't-1' }))
      .mockResolvedValueOnce([]);

    renderPage()

    await waitFor(() => expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(screen.getByText(/등록된 Source가 없습니다/)).toBeInTheDocument())
    expect(mockedListSources).toHaveBeenCalledTimes(2)
  })

  it('prevents a duplicate registration submit while one is already in flight', async () => {
    mockedListSources.mockResolvedValue([])
    let resolveCreate: (value: SourceResponse) => void = () => {}
    mockedCreate.mockImplementation(
      () =>
        new Promise<SourceResponse>((resolve) => {
          resolveCreate = resolve
        }),
    )

    renderPage()
    await waitFor(() => expect(screen.getByText(/등록된 Source가 없습니다/)).toBeInTheDocument())

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('새 Google Drive Source 이름'), '팀 드라이브')
    await user.click(screen.getByRole('button', { name: 'Source 등록' }))

    const submittingButton = screen.getByRole('button', { name: '등록 중...' })
    expect(submittingButton).toBeDisabled()
    await user.click(submittingButton)
    expect(mockedCreate).toHaveBeenCalledTimes(1)

    // 성공 후 입력값이 비워지므로 버튼은 다시 활성화되지 않고(빈 값) "Source 등록"
    // 라벨로만 돌아온다 - "등록 중..."에서 벗어났는지가 여기서 확인할 사실이다.
    resolveCreate(activeUnconnectedSource(1, '팀 드라이브'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Source 등록' })).toBeInTheDocument())
  })

  it('starts Google authorize exactly once per click and shows a two-step disconnect confirmation', async () => {
    mockedListSources.mockResolvedValueOnce([activeUnconnectedSource(7, '팀 드라이브')])
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    expect(screen.getByText('Google 계정 연결 필요')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedAuthorize).toHaveBeenCalledWith(expect.anything(), 7))
    expect(mockedAuthorize).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    expect(screen.getByText('정말 연결을 해제할까요?')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByText('정말 연결을 해제할까요?')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    mockedListSources.mockResolvedValueOnce([])
    await user.click(screen.getByRole('button', { name: '연결 해제 확인' }))

    await waitFor(() => expect(mockedDisconnect).toHaveBeenCalledWith(expect.anything(), 7))
    await waitFor(() => expect(screen.getByText(/등록된 Source가 없습니다/)).toBeInTheDocument())
  })

  it('marks a disabled source as not reconnectable and offers no connect/disconnect action', async () => {
    mockedListSources.mockResolvedValue([
      { id: 9, type: 'GOOGLE_DRIVE', name: '옛 드라이브', status: 'DISABLED', lastSyncAt: null, credentialPresent: false },
    ])

    renderPage()

    await waitFor(() => expect(screen.getByText('옛 드라이브')).toBeInTheDocument())
    expect(screen.getByText(/다시 연결할 수 없습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Google 연결' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '연결 해제' })).not.toBeInTheDocument()
  })
})

describe('SourcesPage Google callback return banner (M16A follow-up)', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
  })

  it('keeps the success banner visible after the URL is cleaned up, and still refetches the list', async () => {
    mockedListSources.mockResolvedValue([])

    renderPageWithCallback('success')

    await waitFor(() => expect(screen.getByText(/Google 연결 요청을 처리했습니다/)).toBeInTheDocument())
    // Mount 시점의 목록 Effect + Callback 정리 Effect, 둘 다 목록을 다시 불러온다.
    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))

    // URL 정리(Effect의 setSearchParams)가 이미 반영된 뒤에도(위 두 Effect가 모두 끝난
    // 뒤) 배너가 여전히 보여야 한다 - 이전 버그는 여기서 배너가 사라졌다.
    expect(screen.getByText(/Google 연결 요청을 처리했습니다/)).toBeInTheDocument()
  })

  it('keeps the failed banner visible after the URL is cleaned up', async () => {
    mockedListSources.mockResolvedValue([])

    renderPageWithCallback('failed')

    await waitFor(() => expect(screen.getByText(/Google 연결에 실패했거나 취소되었습니다/)).toBeInTheDocument())
    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))

    expect(screen.getByText(/Google 연결에 실패했거나 취소되었습니다/)).toBeInTheDocument()
  })

  it('does not let a slower initial list response overwrite a newer one', async () => {
    let resolveFirst: (value: SourceResponse[]) => void = () => {}
    mockedListSources
      .mockImplementationOnce(
        () =>
          new Promise<SourceResponse[]>((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockResolvedValueOnce([activeUnconnectedSource(1, '두 번째 응답이 최신')])

    renderPageWithCallback('success')

    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
    // 두 번째(더 최신) 요청이 먼저 응답한다.
    await waitFor(() => expect(screen.getByText('두 번째 응답이 최신')).toBeInTheDocument())

    // 첫 번째(더 오래된) 요청이 이제야 응답한다 - 이미 최신 State를 덮어써서는 안 된다.
    resolveFirst([])
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.getByText('두 번째 응답이 최신')).toBeInTheDocument()
  })
})

describe('SourcesPage manual sync (M16B)', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
  })

  it('offers no sync action for a source without a stored credential', async () => {
    mockedListSources.mockResolvedValue([activeUnconnectedSource(1, '팀 드라이브')])

    renderPage()

    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /메타데이터 동기화/ })).not.toBeInTheDocument()
  })

  it('runs a sync, shows a single indeterminate waiting state, then a completed result', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    let resolveSync: (value: SyncRunResponse) => void = () => {}
    mockedSync.mockImplementation(
      () =>
        new Promise<SyncRunResponse>((resolve) => {
          resolveSync = resolve
        }),
    )

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    const busyButton = screen.getByRole('button', { name: '동기화 중...' })
    expect(busyButton).toBeDisabled()
    expect(busyButton).toHaveAttribute('aria-busy', 'true')
    // 가짜 진행률 표시(%)를 절대 만들지 않는다 - 부정형 상태 문구 하나만 있어야 한다.
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()

    mockedListSources.mockResolvedValueOnce([activeConnectedSource(1, '팀 드라이브')])
    resolveSync(syncRun({ status: 'COMPLETED', total: 12, success: 12, failed: 0 }))

    await waitFor(() => expect(screen.getByText(/동기화가 완료됐습니다/)).toBeInTheDocument())
    expect(screen.getByText(/처리 12건 중 성공 12건 \/ 실패 0건/)).toBeInTheDocument()
    // Source 목록을 새로고침한다(lastSyncAt 등 반영) - Mount 1회 + Sync 이후 1회.
    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
  })

  it('prevents a duplicate sync submit while one is already in flight', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockImplementation(() => new Promise<SyncRunResponse>(() => {}))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '메타데이터 동기화' }))
    const busyButton = screen.getByRole('button', { name: '동기화 중...' })
    await user.click(busyButton) // Disabled 버튼 클릭 - 아무 효과가 없어야 한다.

    expect(mockedSync).toHaveBeenCalledTimes(1)
  })

  it('disables connect/disconnect for the row while its sync is pending', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockImplementation(() => new Promise<SyncRunResponse>(() => {}))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())

    await userEvent.setup().click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    expect(screen.getByRole('button', { name: 'Google 재연결' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '연결 해제' })).toBeDisabled()
  })

  it('shows a real error for a partial/failed run even though the HTTP call itself succeeded', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockResolvedValue(syncRun({ status: 'PARTIAL_FAILURE', total: 10, success: 7, failed: 3 }))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    await waitFor(() => expect(screen.getByText(/완전히 끝나지 않았습니다.*일부 실패/)).toBeInTheDocument())
    expect(screen.getByText(/처리 10건 중 성공 7건 \/ 실패 3건/)).toBeInTheDocument()
  })

  it.each([
    ['SYNC_ALREADY_RUNNING' as const, /이미 이 Source에 대한 동기화가 진행 중입니다/],
    ['CREDENTIAL_UNAVAILABLE' as const, /Google 계정 연결 정보를 사용할 수 없습니다/],
    ['AUTHENTICATION_REQUIRED' as const, /로그인 인증을 확인하지 못했습니다/],
    ['NOT_FOUND' as const, /해당 Source를 찾을 수 없습니다/],
    ['NETWORK_ERROR' as const, /서버에 연결할 수 없습니다/],
  ])('reports %s honestly instead of a generic message', async (code, expectedText) => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockRejectedValue(new ApiError(409, { code, message: 'x', traceId: null }))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    await waitFor(() => expect(screen.getByText(expectedText)).toBeInTheDocument())
  })

  it('keeps the last sync result message visible across the list refresh it triggers', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockResolvedValue(syncRun({ status: 'COMPLETED' }))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    await waitFor(() => expect(screen.getByText(/동기화가 완료됐습니다/)).toBeInTheDocument())
    // refresh()가 목록을 다시 불러온 뒤에도(별도 State이므로) 결과 메시지가 남아 있어야 한다.
    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
    expect(screen.getByText(/동기화가 완료됐습니다/)).toBeInTheDocument()
  })

  it('clears the previous outcome banner once a new sync attempt starts', async () => {
    mockedListSources.mockResolvedValue([activeConnectedSource(1, '팀 드라이브')])
    mockedSync.mockResolvedValueOnce(syncRun({ status: 'FAILED' }))

    renderPage()
    await waitFor(() => expect(screen.getByText('팀 드라이브')).toBeInTheDocument())
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '메타데이터 동기화' }))
    await waitFor(() => expect(screen.getByText(/완전히 끝나지 않았습니다.*실패\)/)).toBeInTheDocument())

    let resolveSecond: (value: SyncRunResponse) => void = () => {}
    mockedSync.mockImplementationOnce(
      () =>
        new Promise<SyncRunResponse>((resolve) => {
          resolveSecond = resolve
        }),
    )
    await user.click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    expect(screen.queryByText(/완전히 끝나지 않았습니다/)).not.toBeInTheDocument()

    resolveSecond(syncRun({ status: 'COMPLETED' }))
    await waitFor(() => expect(screen.getByText(/동기화가 완료됐습니다/)).toBeInTheDocument())
  })
})
