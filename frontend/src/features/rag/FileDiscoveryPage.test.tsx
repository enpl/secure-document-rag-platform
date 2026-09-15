import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FileDiscoveryPage } from './FileDiscoveryPage'
import { useAuth } from '../../auth/AuthContext'
import type { AuthState } from '../../auth/AuthContext'
import { searchFiles } from '../../api/fileDiscovery'
import type { RagFileItem, RagFileSearchResponse } from '../../api/fileDiscovery'
import { listSources } from '../../api/sources'
import type { SourceResponse } from '../../api/sources'
import { ApiError } from '../../api/client'

vi.mock('../../auth/AuthContext')
// SourcesPage.test.tsx/HomePage.test.tsx와 같은 이유 - 항상 같은 참조를 반환해야
// `useEffect(..., [apiClient])`가 무한 Refetch Loop에 빠지지 않는다.
vi.mock('../../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../../api/fileDiscovery', async () => {
  const actual = await vi.importActual<typeof import('../../api/fileDiscovery')>('../../api/fileDiscovery')
  return { ...actual, searchFiles: vi.fn() }
})
vi.mock('../../api/sources', async () => {
  const actual = await vi.importActual<typeof import('../../api/sources')>('../../api/sources')
  return { ...actual, listSources: vi.fn() }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedSearchFiles = vi.mocked(searchFiles)
const mockedListSources = vi.mocked(listSources)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function fileItem(overrides: Partial<RagFileItem> = {}): RagFileItem {
  return {
    documentId: 1,
    sourceId: 1,
    name: '분기 보고서.pdf',
    mimeType: 'application/pdf',
    sourceVersion: 'v1',
    modifiedAt: '2026-09-01T00:00:00Z',
    indexStatus: 'INDEXED',
    sourceVersionCurrent: true,
    downloadable: true,
    viewUrl: 'https://drive.google.com/file/d/abc/view',
    ...overrides,
  }
}

function response(overrides: Partial<RagFileSearchResponse> = {}): RagFileSearchResponse {
  return { items: [], hasMore: false, partial: false, ...overrides }
}

function activeSource(id: number, name: string): SourceResponse {
  return { id, type: 'GOOGLE_DRIVE', name, status: 'ACTIVE', lastSyncAt: null, credentialPresent: true }
}

function renderPage() {
  return render(<FileDiscoveryPage />)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedSearchFiles.mockResolvedValue(response())
})

describe('FileDiscoveryPage role scoping', () => {
  it('never calls the admin-only Source list for a non-admin and omits the Source picker', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))

    renderPage()

    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalled())
    expect(mockedListSources).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Source')).not.toBeInTheDocument()
    expect(screen.getByText(/내가 접근할 수 있는 범위의 문서만 검색됩니다/)).toBeInTheDocument()
  })

  it('offers an owner-scoped Source picker to an admin, populated from the admin Source list', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedListSources.mockResolvedValue([activeSource(5, '팀 드라이브')])

    renderPage()

    await waitFor(() => expect(screen.getByRole('option', { name: '팀 드라이브' })).toBeInTheDocument())
  })
})

describe('FileDiscoveryPage query encoding', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))
  })

  it('sends only explicitly-typed/selected criteria, using an exact MIME value and local-date boundaries', async () => {
    renderPage()
    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(1))

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('파일 이름'), '보고서')
    await user.selectOptions(screen.getByLabelText('형식'), 'PDF')
    await user.type(screen.getByLabelText('수정일 시작'), '2026-01-01')
    await user.type(screen.getByLabelText('수정일 종료'), '2026-01-02')
    await user.selectOptions(screen.getByLabelText('정렬'), '이름 오름차순')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(2))
    expect(mockedSearchFiles).toHaveBeenLastCalledWith(expect.anything(), {
      q: '보고서',
      mimeType: 'application/pdf',
      sourceId: undefined,
      modifiedFrom: new Date('2026-01-01T00:00:00').toISOString(),
      modifiedTo: new Date('2026-01-02T23:59:59.999').toISOString(),
      sort: 'NAME_ASC',
      page: 0,
      size: 20,
    })
  })

  it('rejects a start date after the end date without calling the search API again', async () => {
    renderPage()
    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(1))

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('수정일 시작'), '2026-05-02')
    await user.type(screen.getByLabelText('수정일 종료'), '2026-05-01')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(screen.getByText('시작일이 종료일보다 늦을 수 없습니다.')).toBeInTheDocument()
    expect(mockedSearchFiles).toHaveBeenCalledTimes(1)
  })

  it('resets to page 0 when the submitted criteria change after paging forward', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [fileItem()], hasMore: true }))
    renderPage()
    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByText('분기 보고서.pdf')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '다음' }))
    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(2))
    expect(mockedSearchFiles).toHaveBeenLastCalledWith(expect.anything(), expect.objectContaining({ page: 1 }))

    await user.type(screen.getByLabelText('파일 이름'), '다른 조건')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(3))
    expect(mockedSearchFiles).toHaveBeenLastCalledWith(expect.anything(), expect.objectContaining({ page: 0 }))
  })
})

describe('FileDiscoveryPage hasMore tri-state', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))
  })

  it('enables Next only when hasMore is true, and never fabricates a total count', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [fileItem()], hasMore: true }))
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
    expect(screen.queryByText(/건/)).not.toBeInTheDocument()
  })

  it('disables Next and shows an ordinary empty-complete message when there are no matches', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [], hasMore: false, partial: false }))
    renderPage()

    await waitFor(() => expect(screen.getByText('조건에 맞는 파일을 찾지 못했습니다.')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  })

  it('distinguishes an incomplete empty result from "no files" and disables Next without guessing', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [], hasMore: null, partial: true }))
    renderPage()

    await waitFor(() =>
      expect(screen.getByText(/일부 결과를 확인하지 못해 지금은 보여드릴 파일이 없습니다/)).toBeInTheDocument(),
    )
    expect(screen.queryByText('조건에 맞는 파일을 찾지 못했습니다.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  })

  it('shows returned items plus a neutral incomplete notice when hasMore is null with results present', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [fileItem()], hasMore: null, partial: true }))
    renderPage()

    await waitFor(() => expect(screen.getByText('분기 보고서.pdf')).toBeInTheDocument())
    expect(screen.getByText(/다음 Page가 더 있는지 확인하지 못했습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  })
})

describe('FileDiscoveryPage stale-response handling', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))
  })

  it('does not let a slower earlier search response overwrite a newer submitted query', async () => {
    let resolveFirst: (value: RagFileSearchResponse) => void = () => {}
    mockedSearchFiles
      .mockImplementationOnce(
        () =>
          new Promise<RagFileSearchResponse>((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockResolvedValueOnce(response({ items: [fileItem({ documentId: 2, name: '최신 검색 결과.txt' })] }))

    renderPage()
    await waitFor(() => expect(mockedSearchFiles).toHaveBeenCalledTimes(1))

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('파일 이름'), '최신')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => expect(screen.getByText('최신 검색 결과.txt')).toBeInTheDocument())

    resolveFirst(response({ items: [fileItem({ documentId: 1, name: '낡은 결과.txt' })] }))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.getByText('최신 검색 결과.txt')).toBeInTheDocument()
    expect(screen.queryByText('낡은 결과.txt')).not.toBeInTheDocument()
  })

  it('replaces stale results with an honest error banner on an auth/permission failure', async () => {
    mockedSearchFiles
      .mockResolvedValueOnce(response({ items: [fileItem()] }))
      .mockRejectedValueOnce(new ApiError(401, { code: 'AUTHENTICATION_REQUIRED', message: 'x', traceId: null }))

    renderPage()
    await waitFor(() => expect(screen.getByText('분기 보고서.pdf')).toBeInTheDocument())

    await userEvent.setup().click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() =>
      expect(screen.getByText('로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.')).toBeInTheDocument(),
    )
    expect(screen.queryByText('분기 보고서.pdf')).not.toBeInTheDocument()
  })
})

describe('FileDiscoveryPage safe rendering', () => {
  beforeEach(() => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))
  })

  it('renders the file name as plain text, never as HTML', async () => {
    mockedSearchFiles.mockResolvedValue(
      response({ items: [fileItem({ name: '<img src=x onerror=alert(1)>가짜.txt' })] }),
    )
    renderPage()

    await waitFor(() =>
      expect(screen.getByText('<img src=x onerror=alert(1)>가짜.txt')).toBeInTheDocument(),
    )
    expect(document.querySelector('img[src="x"]')).not.toBeInTheDocument()
  })

  it('renders a clickable link only for a validated https://drive.google.com view URL', async () => {
    mockedSearchFiles.mockResolvedValue(response({ items: [fileItem()] }))
    renderPage()

    await waitFor(() => expect(screen.getByRole('link', { name: '원본 열기' })).toBeInTheDocument())
    const link = screen.getByRole('link', { name: '원본 열기' })
    expect(link).toHaveAttribute('href', 'https://drive.google.com/file/d/abc/view')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('never renders a link for an unsafe or non-Drive view URL', async () => {
    mockedSearchFiles.mockResolvedValue(
      response({ items: [fileItem({ viewUrl: 'javascript:alert(1)' })] }),
    )
    renderPage()

    await waitFor(() => expect(screen.getByText('분기 보고서.pdf')).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: '원본 열기' })).not.toBeInTheDocument()
  })

  it('never shows an "Ask AI" action and explains an outdated indexed version honestly', async () => {
    mockedSearchFiles.mockResolvedValue(
      response({ items: [fileItem({ indexStatus: 'INDEXED', sourceVersionCurrent: false })] }),
    )
    renderPage()

    await waitFor(() => expect(screen.getByText(/오래된 버전일 수 있음/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /AI/ })).not.toBeInTheDocument()
    expect(screen.queryByText('AI에게 물어보기')).not.toBeInTheDocument()
  })
})
