import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { MyDrivePage } from './MyDrivePage'
import { useAuth } from '../../auth/AuthContext'
import type { AuthState } from '../../auth/AuthContext'
import {
  authorizeGoogleSource,
  createMyGoogleDriveSource,
  disconnectMyDriveSource,
  listMyDriveFiles,
  listMyDriveSources,
  syncMyDriveSource,
} from '../../api/sources'
import type { SourceFileResponse, SourceFilesPageResponse, SourceResponse } from '../../api/sources'
import { createShare, listMyShares, unshare } from '../../api/shares'
import type { ShareResponse } from '../../api/shares'
import { ApiError } from '../../api/client'
import { navigateToGoogleAuthorization } from './googleAuthorizationNavigation'

vi.mock('../../auth/AuthContext')
vi.mock('../../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../../api/sources', async () => {
  const actual = await vi.importActual<typeof import('../../api/sources')>('../../api/sources')
  return {
    ...actual,
    listMyDriveSources: vi.fn(),
    createMyGoogleDriveSource: vi.fn(),
    disconnectMyDriveSource: vi.fn(),
    syncMyDriveSource: vi.fn(),
    listMyDriveFiles: vi.fn(),
    authorizeGoogleSource: vi.fn(),
  }
})
vi.mock('../../api/shares', async () => {
  const actual = await vi.importActual<typeof import('../../api/shares')>('../../api/shares')
  return { ...actual, listMyShares: vi.fn(), unshare: vi.fn(), createShare: vi.fn() }
})
vi.mock('./googleAuthorizationNavigation', () => ({ navigateToGoogleAuthorization: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)
const mockedListSources = vi.mocked(listMyDriveSources)
const mockedCreateSource = vi.mocked(createMyGoogleDriveSource)
const mockedDisconnect = vi.mocked(disconnectMyDriveSource)
const mockedSync = vi.mocked(syncMyDriveSource)
const mockedListFiles = vi.mocked(listMyDriveFiles)
const mockedAuthorize = vi.mocked(authorizeGoogleSource)
const mockedListShares = vi.mocked(listMyShares)
const mockedUnshare = vi.mocked(unshare)
const mockedCreateShare = vi.mocked(createShare)
const mockedNavigateToGoogle = vi.mocked(navigateToGoogleAuthorization)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function connectedSource(overrides: Partial<SourceResponse> = {}): SourceResponse {
  return { id: 1, type: 'GOOGLE_DRIVE', name: '내 드라이브', status: 'ACTIVE', lastSyncAt: null, credentialPresent: true, ...overrides }
}

function pickerFile(overrides: Partial<SourceFileResponse> = {}): SourceFileResponse {
  return { documentId: 100, name: '보고서.pdf', mimeType: 'application/pdf', modifiedAt: null, indexStatus: 'PENDING', ...overrides }
}

function filesPage(overrides: Partial<SourceFilesPageResponse> = {}): SourceFilesPageResponse {
  return { items: [], hasMore: false, ...overrides }
}

function shareResponse(overrides: Partial<ShareResponse> = {}): ShareResponse {
  return {
    id: 5,
    sourceId: 1,
    documentId: 100,
    classification: 'INTERNAL',
    allowedActions: ['VIEW'],
    recipients: ['recipient-b'],
    adminBlocked: false,
    adminBlockReason: null,
    generation: 1,
    active: true,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    revokedAt: null,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/my-drive']}>
      <MyDrivePage />
    </MemoryRouter>,
  )
}

function renderPageWithCallback(flag: 'success' | 'failed') {
  return render(
    <MemoryRouter initialEntries={[`/my-drive?googleConnect=${flag}`]}>
      <MyDrivePage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedListFiles.mockReset()
  mockedUseAuth.mockReturnValue(asAuth({ subject: 'user-a-subject', isAdmin: false }))
  mockedListShares.mockResolvedValue([])
})

describe('MyDrivePage access (M16C)', () => {
  it('is reachable and functional for an ordinary USER without ADMIN', async () => {
    mockedListSources.mockResolvedValue([])

    renderPage()

    await waitFor(() => expect(screen.getByText('등록된 연결이 없습니다. 위에서 먼저 등록해 주세요.')).toBeInTheDocument())
    expect(mockedListSources).toHaveBeenCalledTimes(1)
  })

  it('shows the current subject as the copyable SDV share id, never an email/token', async () => {
    mockedListSources.mockResolvedValue([])

    renderPage()

    await waitFor(() => expect(screen.getByText('user-a-subject')).toBeInTheDocument())
    expect(screen.getByText(/이메일이나 Google 계정이 아닙니다/)).toBeInTheDocument()
  })
})

describe('MyDrivePage connection management', () => {
  it('registers a new connection and refreshes the list', async () => {
    mockedListSources.mockResolvedValueOnce([]).mockResolvedValueOnce([connectedSource({ credentialPresent: false })])
    mockedCreateSource.mockResolvedValue(connectedSource({ credentialPresent: false }))

    renderPage()
    await waitFor(() => expect(screen.getByText(/등록된 연결이 없습니다/)).toBeInTheDocument())

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('새 Google Drive 연결 이름'), '내 드라이브')
    await user.click(screen.getByRole('button', { name: '연결 등록' }))

    await waitFor(() => expect(mockedCreateSource).toHaveBeenCalledWith(expect.anything(), '내 드라이브'))
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
  })

  it('starts the existing Google authorize flow reusing GET /api/admin/sources/google/authorize', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    // jsdom은 실제 Navigation을 수행하지 않는다("Not implemented" 경고만 남긴다) -
    // SourcesPage.test.tsx와 같은 이유로 실제 이동 자체는 검증하지 않는다.
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))

    await waitFor(() =>
      expect(mockedAuthorize).toHaveBeenCalledWith(expect.anything(), 1, expect.any(AbortSignal)),
    )
  })

  it('runs a manual sync through the reused SourceSyncPanel and refreshes the connection list', async () => {
    mockedListSources.mockResolvedValueOnce([connectedSource()]).mockResolvedValueOnce([connectedSource()])
    mockedSync.mockResolvedValue({
      runId: 1,
      sourceId: 1,
      mode: 'FULL',
      status: 'COMPLETED',
      total: 5,
      success: 5,
      failed: 0,
      startedAt: '2026-09-01T00:00:00Z',
      endedAt: '2026-09-01T00:00:05Z',
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '메타데이터 동기화' }))

    await waitFor(() => expect(mockedSync).toHaveBeenCalledWith(expect.anything(), 1))
    await waitFor(() => expect(screen.getByText(/동기화가 완료됐습니다/)).toBeInTheDocument())
  })

  it('disconnects only after a two-step confirmation and preserves the shares list untouched', async () => {
    mockedListSources.mockResolvedValueOnce([connectedSource()]).mockResolvedValueOnce([
      connectedSource({ status: 'DISABLED', credentialPresent: false }),
    ])
    mockedListShares.mockResolvedValue([shareResponse()])
    mockedDisconnect.mockResolvedValue(undefined)

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText(/문서 #100/)).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '연결 해제' }))
    expect(screen.getByText(/정말 연결을 해제할까요\? 공유 설정은 그대로 보존됩니다\./)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '연결 해제 확인' }))

    await waitFor(() => expect(mockedDisconnect).toHaveBeenCalledWith(expect.anything(), 1))
    // Disconnect 자체가 공유 목록을 다시 요청하지 않는다 - 이미 불러온 공유가 그대로 남아 있어야 한다.
    expect(mockedListShares).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/문서 #100/)).toBeInTheDocument()
  })

  it('offers reconnect for a known DISABLED source using the same sourceId, never a recreate prompt', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ status: 'DISABLED', credentialPresent: false })])
    mockedListShares.mockResolvedValue([shareResponse()])
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())

    // 예전의 오해를 부르는 안내("다시 연결할 수 없습니다. 새로 등록해 주세요")가 사라졌다.
    expect(screen.queryByText(/다시 연결할 수 없습니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/재인증해야만 다시 연결됩니다/)).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 재연결' }))

    // 같은 sourceId(1)로 기존 authorizeGoogleSource API를 그대로 재사용한다 - 새 Source를 만들지 않는다.
    await waitFor(() =>
      expect(mockedAuthorize).toHaveBeenCalledWith(expect.anything(), 1, expect.any(AbortSignal)),
    )
    expect(mockedCreateSource).not.toHaveBeenCalled()
    // 공유 설정은 그대로 보존된다 - 재연결 시도 자체가 공유 목록을 건드리지 않는다.
    expect(mockedListShares).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/문서 #100/)).toBeInTheDocument()
  })

  it('never offers reconnect for an unrecognized connection status, and does not claim it is unreconnectable either', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ status: 'ERROR', credentialPresent: false })])

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())

    expect(screen.queryByRole('button', { name: 'Google 재연결' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Google 연결' })).not.toBeInTheDocument()
    expect(screen.queryByText(/다시 연결할 수 없습니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/알 수 없는 연결 상태입니다/)).toBeInTheDocument()
  })

  it('reports an authorize-call failure honestly without claiming the reconnect succeeded', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ status: 'DISABLED', credentialPresent: false })])
    mockedListShares.mockResolvedValue([])
    mockedAuthorize.mockRejectedValue(new ApiError(404, { code: 'NOT_FOUND', message: 'x', traceId: null }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 재연결' }))

    await waitFor(() => expect(screen.getByText('해당 연결을 찾을 수 없습니다.')).toBeInTheDocument())
  })

  it.each([
    ['AUTHENTICATION_REQUIRED', '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'],
    ['ACCESS_DENIED', '이 작업을 수행할 권한이 없습니다.'],
    ['OAUTH_UNAVAILABLE', 'Google 연결 기능을 현재 사용할 수 없습니다. SDV 운영자에게 문의해 주세요.'],
    ['NETWORK_ERROR', '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'],
  ])('clears the busy state and keeps %s distinct when authorize is rejected', async (code, message) => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])
    mockedAuthorize.mockRejectedValue(new ApiError(code === 'NETWORK_ERROR' ? 0 : 503, { code, message: 'x', traceId: null }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))

    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled()
  })

  it('reconciles owner-scoped state and clears pending authorization after a simulated Back pageshow', async () => {
    mockedListSources
      .mockResolvedValueOnce([connectedSource({ credentialPresent: false })])
      .mockResolvedValueOnce([connectedSource({ credentialPresent: false })])
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '연결 중...' })).toBeDisabled())

    // jsdom의 pageshow 이벤트는 브라우저 Back/BFCache 복귀의 제어 가능한 근사치다.
    // 실제 BFCache 시각 검증은 별도 브라우저 증거로 구분한다.
    window.dispatchEvent(new Event('pageshow'))

    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByText(/연결 완료를 확인하지 못했습니다/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedAuthorize).toHaveBeenCalledTimes(2))
  })

  it('does not treat a forged success query as proof when the owner-scoped source is still unconnected', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])

    renderPageWithCallback('success')

    await waitFor(() => expect(screen.getByText(/연결 완료를 확인하지 못했습니다/)).toBeInTheDocument())
    expect(screen.queryByText(/연결 요청을 처리했습니다/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled()
  })

  it('shows a retryable status-check error when callback reconciliation cannot load owner state', async () => {
    mockedListSources
      .mockRejectedValueOnce(new ApiError(0, { code: 'NETWORK_ERROR', message: 'x', traceId: null }))
      .mockResolvedValueOnce([connectedSource({ credentialPresent: false })])

    renderPageWithCallback('success')

    await waitFor(() => expect(screen.getByText(/Google 연결 상태를 확인하지 못했습니다/)).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '연결 상태 다시 확인' }))

    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByText(/연결 완료를 확인하지 못했습니다/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled()
  })

  it('reports only stored server state after a callback success hint when a credential already existed', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: true })])

    renderPageWithCallback('success')

    await waitFor(() => expect(screen.getByText(/현재 SDV에 저장된 Google 연결 정보를 확인했습니다/)).toBeInTheDocument())
    expect(screen.getByText(/이번 요청이 새로 성공했다는 증거가 아니며/)).toBeInTheDocument()
  })

  it('does not show an authorization-return warning or request storm on a normal mount', async () => {
    mockedListSources.mockResolvedValue([])

    renderPage()

    await waitFor(() => expect(screen.getByText(/등록된 연결이 없습니다/)).toBeInTheDocument())
    expect(mockedListSources).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/연결 완료를 확인하지 못했습니다/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '연결 상태 다시 확인' })).not.toBeInTheDocument()

    window.dispatchEvent(new Event('focus'))
    await Promise.resolve()
    expect(mockedListSources).toHaveBeenCalledTimes(1)
  })

  it('deduplicates overlapping pageshow recovery events while the owner-state lookup is pending', async () => {
    let resolveRecovery!: (value: SourceResponse[]) => void
    mockedListSources
      .mockResolvedValueOnce([connectedSource({ credentialPresent: false })])
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRecovery = resolve }))
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedNavigateToGoogle).toHaveBeenCalledTimes(1))

    window.dispatchEvent(new Event('pageshow'))
    window.dispatchEvent(new Event('pageshow'))
    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(2))
    expect(mockedListSources).toHaveBeenCalledTimes(2)

    resolveRecovery([connectedSource({ credentialPresent: false })])
    await waitFor(() => expect(screen.getByText(/연결 완료를 확인하지 못했습니다/)).toBeInTheDocument())
  })

  it('installs only one effective pageshow recovery listener under StrictMode', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])
    mockedAuthorize.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/mock' })

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/my-drive']}>
          <MyDrivePage />
        </MemoryRouter>
      </StrictMode>,
    )
    await waitFor(() => expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled())
    const initialLookupCount = mockedListSources.mock.calls.length
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedNavigateToGoogle).toHaveBeenCalledTimes(1))

    window.dispatchEvent(new Event('pageshow'))

    await waitFor(() => expect(mockedListSources).toHaveBeenCalledTimes(initialLookupCount + 1))
  })

  it('does not redirect after an authorize response arrives following unmount', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])
    let resolveAuthorize!: (value: { authorizationUrl: string }) => void
    mockedAuthorize.mockImplementation(
      () => new Promise((resolve) => { resolveAuthorize = resolve }),
    )

    const view = renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedAuthorize).toHaveBeenCalledTimes(1))

    view.unmount()
    resolveAuthorize({ authorizationUrl: 'https://accounts.google.com/late' })
    await Promise.resolve()

    expect(mockedNavigateToGoogle).not.toHaveBeenCalled()
  })

  it('does not redirect an old authorization response after an account-keyed identity remount', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ credentialPresent: false })])
    let resolveAuthorize!: (value: { authorizationUrl: string }) => void
    mockedAuthorize.mockImplementation(
      () => new Promise((resolve) => { resolveAuthorize = resolve }),
    )

    const view = render(
      <MemoryRouter initialEntries={['/my-drive']}>
        <MyDrivePage key="sdv-user-a" />
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByRole('button', { name: 'Google 연결' })).toBeEnabled())
    await userEvent.setup().click(screen.getByRole('button', { name: 'Google 연결' }))
    await waitFor(() => expect(mockedAuthorize).toHaveBeenCalledTimes(1))

    mockedUseAuth.mockReturnValue(asAuth({ subject: 'sdv-user-b', isAdmin: false }))
    view.rerender(
      <MemoryRouter initialEntries={['/my-drive']}>
        <MyDrivePage key="sdv-user-b" />
      </MemoryRouter>,
    )
    resolveAuthorize({ authorizationUrl: 'https://accounts.google.com/late-user-a' })
    await Promise.resolve()

    expect(mockedNavigateToGoogle).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByText('sdv-user-b')).toBeInTheDocument())
  })

  it('shows the honest failed-callback banner for a rejected reconnect (e.g. a different Google account), never a success claim', async () => {
    mockedListSources.mockResolvedValue([connectedSource({ status: 'DISABLED', credentialPresent: false })])

    renderPageWithCallback('failed')

    await waitFor(() => expect(screen.getByText(/Google 연결에 실패했거나 취소되었습니다/)).toBeInTheDocument())
    expect(screen.queryByText(/처리했습니다/)).not.toBeInTheDocument()
  })
})

describe('MyDrivePage private file picker never publishes on its own', () => {
  it('searches the synchronized owner catalog, resets to page zero, and preserves prior selections', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListFiles
      .mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 1, name: '첫 페이지.txt' })], hasMore: true }))
      .mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 2, name: '둘째 페이지.txt' })] }))
      .mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 3, name: '분기 보고서.txt' })] }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '파일 보기' }))
    await waitFor(() => expect(screen.getByLabelText('첫 페이지.txt 선택')).toBeInTheDocument())
    await user.click(screen.getByLabelText('첫 페이지.txt 선택'))
    await user.click(screen.getByRole('button', { name: '다음' }))
    await waitFor(() => expect(screen.getByText('둘째 페이지.txt')).toBeInTheDocument())

    await user.type(screen.getByLabelText('동기화된 파일 이름 검색'), '분기 보고서')
    await user.click(screen.getByRole('button', { name: '파일 검색' }))

    await waitFor(() =>
      expect(mockedListFiles).toHaveBeenLastCalledWith(
        expect.anything(), 1, 0, 50, '분기 보고서', expect.any(AbortSignal),
      ),
    )
    expect(screen.getByText('선택한 파일 1개')).toBeInTheDocument()
    expect(screen.getByText('1페이지')).toBeInTheDocument()
  })

  it('aborts an obsolete catalog request and clears search without selecting or sharing results', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    let firstSignal: AbortSignal | undefined
    mockedListFiles.mockImplementationOnce((_client, _sourceId, _page, _size, _query, signal) => {
      firstSignal = signal
      return new Promise<SourceFilesPageResponse>(() => undefined)
    })
    mockedListFiles.mockResolvedValueOnce(filesPage({ items: [pickerFile({ name: '검색 결과.txt' })] }))
    mockedListFiles.mockResolvedValueOnce(filesPage({ items: [pickerFile({ name: '전체 목록.txt' })] }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '파일 보기' }))
    await user.type(screen.getByLabelText('동기화된 파일 이름 검색'), '검색 결과')
    await user.click(screen.getByRole('button', { name: '파일 검색' }))

    await waitFor(() => expect(firstSignal?.aborted).toBe(true))
    await waitFor(() => expect(screen.getByText('검색 결과.txt')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '검색 지우기' }))

    await waitFor(() => expect(screen.getByText('전체 목록.txt')).toBeInTheDocument())
    expect(mockedCreateShare).not.toHaveBeenCalled()
    expect(screen.queryByText(/선택한 파일/)).not.toBeInTheDocument()
  })

  it('clears selections when the owner switches to another source', async () => {
    mockedListSources.mockResolvedValue([
      connectedSource({ id: 1, name: '첫 Drive' }),
      connectedSource({ id: 2, name: '둘째 Drive' }),
    ])
    mockedListFiles
      .mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 11, name: '첫 파일.txt' })] }))
      .mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 22, name: '둘째 파일.txt' })] }))

    renderPage()
    await waitFor(() => expect(screen.getByText('첫 Drive')).toBeInTheDocument())
    const user = userEvent.setup()
    await user.click(screen.getAllByRole('button', { name: '파일 보기' })[0])
    await waitFor(() => expect(screen.getByLabelText('첫 파일.txt 선택')).toBeInTheDocument())
    await user.click(screen.getByLabelText('첫 파일.txt 선택'))
    expect(screen.getByText('선택한 파일 1개')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '파일 보기' }))
    await waitFor(() => expect(screen.getByText('둘째 파일.txt')).toBeInTheDocument())
    expect(screen.queryByText(/선택한 파일/)).not.toBeInTheDocument()
  })

  it('lets the owner browse and check files without ever calling createShare', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListFiles.mockResolvedValue(filesPage({ items: [pickerFile()] }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '파일 보기' }))

    await waitFor(() => expect(mockedListFiles).toHaveBeenCalledWith(
      expect.anything(), 1, 0, 50, '', expect.any(AbortSignal),
    ))
    await waitFor(() => expect(screen.getByLabelText('보고서.pdf 선택')).toBeInTheDocument())
    expect(screen.getByRole('columnheader', { name: '파일 이름' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '형식' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '색인 상태' })).toBeInTheDocument()
    expect(screen.getByText('색인 대기 (선택 가능)')).toBeInTheDocument()

    await userEvent.setup().click(screen.getByLabelText('보고서.pdf 선택'))
    await userEvent.setup().click(screen.getByLabelText('보고서.pdf 선택')) // 체크 해제 - 빈 선택으로 되돌아온다.

    expect(mockedCreateShare).not.toHaveBeenCalled()
    expect(screen.queryByText(/선택한 파일/)).not.toBeInTheDocument()
  })

  it('paginates with an honest boolean hasMore, never a Tri-state, and never crawls folders', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListFiles.mockResolvedValueOnce(filesPage({ items: [pickerFile()], hasMore: true }))

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '파일 보기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())

    mockedListFiles.mockResolvedValueOnce(filesPage({ items: [pickerFile({ documentId: 101, name: '두번째.pdf' })] }))
    await userEvent.setup().click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => expect(mockedListFiles).toHaveBeenLastCalledWith(
      expect.anything(), 1, 1, 50, '', expect.any(AbortSignal),
    ))
    await waitFor(() => expect(screen.getByText('두번째.pdf')).toBeInTheDocument())
    expect(screen.getByText(/폴더 구조 없이 평면 목록으로/)).toBeInTheDocument()
  })

  it('opens the share dialog with exactly the checked files once the user explicitly confirms', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListFiles.mockResolvedValue(
      filesPage({ items: [pickerFile({ documentId: 1, name: 'A.pdf' }), pickerFile({ documentId: 2, name: 'B.pdf' })] }),
    )

    renderPage()
    await waitFor(() => expect(screen.getByText('내 드라이브')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '파일 보기' }))
    await waitFor(() => expect(screen.getByLabelText('A.pdf 선택')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByLabelText('A.pdf 선택'))
    // B.pdf는 의도적으로 체크하지 않는다 - 선택하지 않은 파일이 대화상자에 섞이지 않아야 한다.
    expect(screen.getByText('선택한 파일 1개')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '선택한 파일 공유하기' }))

    expect(screen.getByText(/선택한 파일 1개에 아래와 같이 공유합니다/)).toBeInTheDocument()
    expect(mockedCreateShare).not.toHaveBeenCalled() // 대화상자를 열기만 한 것 - 아직 확인하지 않았다.
  })
})

describe('MyDrivePage own shares list', () => {
  it('shows the exact file name when it is loaded in the current picker, otherwise an honest identifier', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListShares.mockResolvedValue([shareResponse({ sourceId: 9, documentId: 999 })])

    renderPage()

    await waitFor(() => expect(screen.getByText('문서 #999 (연결 #9)')).toBeInTheDocument())
  })

  it('unshares only after the row action and reports the file as revoked afterward', async () => {
    mockedListSources.mockResolvedValue([connectedSource()])
    mockedListShares
      .mockResolvedValueOnce([shareResponse()])
      .mockResolvedValueOnce([shareResponse({ active: false, revokedAt: '2026-09-16T00:00:00Z' })])
    mockedUnshare.mockResolvedValue(undefined)

    renderPage()
    await waitFor(() => expect(screen.getByText(/문서 #100/)).toBeInTheDocument())

    await userEvent.setup().click(screen.getByRole('button', { name: '공유 해제' }))

    await waitFor(() => expect(mockedUnshare).toHaveBeenCalledWith(expect.anything(), 5))
    await waitFor(() => expect(screen.getByText('철회됨')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '공유 해제' })).not.toBeInTheDocument()
  })

  it('shows an admin block honestly and offers no way for the publisher to clear it', async () => {
    mockedListSources.mockResolvedValue([])
    mockedListShares.mockResolvedValue([shareResponse({ adminBlocked: true, adminBlockReason: '정책 검토' })])

    renderPage()

    await waitFor(() => expect(screen.getByText(/관리자 차단됨 \(정책 검토\)/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /차단 해제/ })).not.toBeInTheDocument()
  })
})
