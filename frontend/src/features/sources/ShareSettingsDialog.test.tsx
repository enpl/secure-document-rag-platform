import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShareSettingsDialog } from './ShareSettingsDialog'
import type { ShareTarget } from './ShareSettingsDialog'
import { useAuth } from '../../auth/AuthContext'
import type { AuthState } from '../../auth/AuthContext'
import { createShare, listMyShares, updateShare } from '../../api/shares'
import type { ShareResponse } from '../../api/shares'
import { ApiError } from '../../api/client'

vi.mock('../../auth/AuthContext')
vi.mock('../../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../../api/shares', async () => {
  const actual = await vi.importActual<typeof import('../../api/shares')>('../../api/shares')
  return { ...actual, createShare: vi.fn(), updateShare: vi.fn(), listMyShares: vi.fn() }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedCreateShare = vi.mocked(createShare)
const mockedUpdateShare = vi.mocked(updateShare)
const mockedListMyShares = vi.mocked(listMyShares)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function target(overrides: Partial<ShareTarget> = {}): ShareTarget {
  return { sourceId: 1, documentId: 100, name: '보고서.pdf', ...overrides }
}

function share(overrides: Partial<ShareResponse> = {}): ShareResponse {
  return {
    id: 5,
    sourceId: 1,
    documentId: 100,
    classification: 'INTERNAL',
    allowedActions: ['VIEW'],
    recipients: ['recipient-b'],
    adminBlocked: false,
    adminBlockReason: null,
    generation: 3,
    active: true,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    revokedAt: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseAuth.mockReturnValue(asAuth({ subject: 'publisher-a-subject' }))
})

describe('ShareSettingsDialog validation', () => {
  it('blocks submission with no recipients and never calls the create API', async () => {
    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={vi.fn()} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '공유하기' }))

    expect(screen.getByText(/수신자를 한 명 이상 입력해 주세요/)).toBeInTheDocument()
    expect(mockedCreateShare).not.toHaveBeenCalled()
  })

  it('blocks submission with no actions selected', async () => {
    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={vi.fn()} />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByLabelText(/찾기\(VIEW\)/)) // 기본으로 켜져 있던 VIEW를 끈다.

    await user.click(screen.getByRole('button', { name: '공유하기' }))

    expect(screen.getByText('행위를 하나 이상 선택해 주세요.')).toBeInTheDocument()
    expect(mockedCreateShare).not.toHaveBeenCalled()
  })

  it('rejects more than the maximum recipient count client-side', async () => {
    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={vi.fn()} />)
    const many = Array.from({ length: 21 }, (_, i) => `recipient-${i}`).join('\n')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), many)
    await user.click(screen.getByRole('button', { name: '공유하기' }))

    expect(screen.getByText(/최대 20명까지/)).toBeInTheDocument()
    expect(mockedCreateShare).not.toHaveBeenCalled()
  })
})

describe('ShareSettingsDialog bounded per-file create', () => {
  it('sends the exact selected-file payload for a single file and reports success honestly', async () => {
    mockedCreateShare.mockResolvedValue(share())

    render(<ShareSettingsDialog mode="create" files={[target({ documentId: 42, sourceId: 7 })]} onClose={vi.fn()} />)
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('보안 등급'), 'CONFIDENTIAL')
    await user.click(screen.getByLabelText(/다운로드\(DOWNLOAD\)/))
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b, recipient-c')
    await user.click(screen.getByRole('button', { name: '공유하기' }))

    await waitFor(() =>
      expect(mockedCreateShare).toHaveBeenCalledWith(
        expect.anything(),
        {
          sourceId: 7,
          documentId: 42,
          classification: 'CONFIDENTIAL',
          actions: ['VIEW', 'DOWNLOAD'],
          recipients: ['recipient-b', 'recipient-c'],
        },
        expect.any(AbortSignal),
      ),
    )
    await waitFor(() => expect(screen.getByText('보고서.pdf - 공유 완료')).toBeInTheDocument())
  })

  it('processes multiple files as independent bounded requests and never claims atomic bulk success', async () => {
    mockedCreateShare
      .mockResolvedValueOnce(share({ documentId: 1 }))
      .mockRejectedValueOnce(new ApiError(400, { code: 'VALIDATION_ERROR', message: 'x', traceId: null }))

    render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: '성공.pdf' }), target({ documentId: 2, name: '실패.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))

    await waitFor(() => expect(screen.getByText('성공.pdf - 공유 완료')).toBeInTheDocument())
    expect(screen.getByText(/실패\.pdf - 입력값을 확인해 주세요/)).toBeInTheDocument()
    expect(mockedCreateShare).toHaveBeenCalledTimes(2)
    // 다시 전체를 Submit할 수 없다 - 이미 성공한 파일까지 다시 건드리는 것을 막는다.
    expect(screen.queryByRole('button', { name: '공유하기' })).not.toBeInTheDocument()
  })

  it('retries only the failed file, leaving the already-succeeded one untouched', async () => {
    mockedCreateShare
      .mockResolvedValueOnce(share({ documentId: 1 }))
      .mockRejectedValueOnce(new ApiError(400, { code: 'VALIDATION_ERROR', message: 'x', traceId: null }))
      .mockResolvedValueOnce(share({ documentId: 2 }))

    render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: '성공.pdf' }), target({ documentId: 2, name: '실패.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(screen.getByText(/실패\.pdf - /)).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '실패한 항목만 다시 시도' }))

    await waitFor(() => expect(screen.getByText('실패.pdf - 공유 완료')).toBeInTheDocument())
    // 성공했던 파일에 대해 createShare가 다시 호출되지 않았다(총 3회 = 최초 2회 + 재시도 1회).
    expect(mockedCreateShare).toHaveBeenCalledTimes(3)
  })

  it('reconciles an uncertain (network) outcome by checking current shares before blindly retrying', async () => {
    mockedCreateShare.mockRejectedValueOnce(new ApiError(0, { code: 'NETWORK_ERROR', message: 'x', traceId: null }))
    mockedListMyShares.mockResolvedValue([share({ documentId: 1, sourceId: 1, active: true })])

    render(<ShareSettingsDialog mode="create" files={[target({ documentId: 1, sourceId: 1 })]} onClose={vi.fn()} />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(screen.getByText(/서버 응답을 확인하지 못했습니다/)).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '실패한 항목만 다시 시도' }))

    await waitFor(() => expect(mockedListMyShares).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByText('보고서.pdf - 공유 완료')).toBeInTheDocument())
    // 이미 성공한 것으로 판정됐으므로 createShare를 다시 호출하지 않는다(최초 1회뿐).
    expect(mockedCreateShare).toHaveBeenCalledTimes(1)
  })
})

describe('ShareSettingsDialog edit mode', () => {
  it('submits the expected generation and never exposes an admin-block-clearing control', async () => {
    mockedUpdateShare.mockResolvedValue(share({ generation: 4 }))
    const onClose = vi.fn()

    render(
      <ShareSettingsDialog
        mode="edit"
        share={share({ id: 9, generation: 3, adminBlocked: true, adminBlockReason: '검토 중' })}
        fileLabel="보고서.pdf"
        onClose={onClose}
      />,
    )

    expect(screen.getByText(/관리자가 이 공유를 차단했습니다 \(사유: 검토 중\)/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /차단 해제/ })).not.toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() =>
      expect(mockedUpdateShare).toHaveBeenCalledWith(
        expect.anything(),
        9,
        {
          expectedGeneration: 3,
          classification: 'INTERNAL',
          actions: ['VIEW'],
          recipients: ['recipient-b'],
        },
        expect.any(AbortSignal),
      ),
    )
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('on a generation conflict, reloads the current share and requires a fresh confirmation instead of overwriting', async () => {
    mockedUpdateShare.mockRejectedValue(
      new ApiError(409, { code: 'SHARE_GENERATION_CONFLICT', message: 'x', traceId: null }),
    )
    mockedListMyShares.mockResolvedValue([
      share({ id: 9, generation: 4, classification: 'CONFIDENTIAL', recipients: ['recipient-b', 'recipient-c'] }),
    ])
    const onClose = vi.fn()

    render(
      <ShareSettingsDialog mode="edit" share={share({ id: 9, generation: 3 })} fileLabel="보고서.pdf" onClose={onClose} />,
    )
    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() =>
      expect(screen.getByText(/다른 곳에서 이미 바뀐 내용이 있어 최신 내용을 다시 불러왔습니다/)).toBeInTheDocument(),
    )
    // 새로 불러온 값으로 Form이 갱신된다 - 낡은 값으로 자동 재제출하지 않는다(다시 저장을 눌러야 한다).
    expect(screen.getByLabelText('보안 등급')).toHaveValue('CONFIDENTIAL')
    expect(onClose).not.toHaveBeenCalled()
    expect(mockedUpdateShare).toHaveBeenCalledTimes(1)
  })
})

describe('ShareSettingsDialog lifecycle/session invalidation (M16C 후속 교정)', () => {
  it('holds the first request, unmounts, then resolves it - the second file is never submitted', async () => {
    let resolveFirst: (value: ShareResponse) => void = () => {}
    mockedCreateShare.mockImplementationOnce(
      () =>
        new Promise<ShareResponse>((resolve) => {
          resolveFirst = resolve
        }),
    )

    const view = render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(mockedCreateShare).toHaveBeenCalledTimes(1))

    view.unmount()
    resolveFirst(share({ documentId: 1 }))
    await new Promise((resolve) => setTimeout(resolve, 0)) // 남은 Microtask/Await를 흘려보낸다.

    // 두 번째 파일은 절대 Dispatch되지 않았다 - 이미 나간 첫 번째 요청만 존재한다.
    expect(mockedCreateShare).toHaveBeenCalledTimes(1)
  })

  it('holds the first request, then an account change resolves it - the second file is never submitted', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ subject: 'publisher-a-subject' }))
    let resolveFirst: (value: ShareResponse) => void = () => {}
    mockedCreateShare.mockImplementationOnce(
      () =>
        new Promise<ShareResponse>((resolve) => {
          resolveFirst = resolve
        }),
    )

    const { rerender } = render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(mockedCreateShare).toHaveBeenCalledTimes(1))

    // 계정이 바뀌었다(실제 앱에서는 MyDrivePage의 key={subject} 재마운트가 이를
    // 동반하지만, 이 Component 자체의 Session Guard가 별도로도 이를 잡아내는지
    // 직접 검증하기 위해 Unmount 없이 새 subject로만 다시 Render한다).
    mockedUseAuth.mockReturnValue(asAuth({ subject: 'different-account-subject' }))
    rerender(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )

    resolveFirst(share({ documentId: 1 }))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mockedCreateShare).toHaveBeenCalledTimes(1)
  })

  it('ending the session (logout) while the first request is pending prevents the next dispatch', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ subject: 'publisher-a-subject' }))
    let resolveFirst: (value: ShareResponse) => void = () => {}
    mockedCreateShare.mockImplementationOnce(
      () =>
        new Promise<ShareResponse>((resolve) => {
          resolveFirst = resolve
        }),
    )

    const { rerender } = render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(mockedCreateShare).toHaveBeenCalledTimes(1))

    // 로그아웃 - AuthContext.subject가 null이 된다(Token 재조달이 진행 중이던
    // 도중에 Session 자체가 끝난 경우와 동일한 신호).
    mockedUseAuth.mockReturnValue(asAuth({ subject: null }))
    rerender(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )

    resolveFirst(share({ documentId: 1 }))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mockedCreateShare).toHaveBeenCalledTimes(1)
  })

  it('never issues a rollback/undo call for an already-succeeded file once the session ends mid-batch', async () => {
    let resolveFirst: (value: ShareResponse) => void = () => {}
    mockedCreateShare.mockImplementationOnce(
      () =>
        new Promise<ShareResponse>((resolve) => {
          resolveFirst = resolve
        }),
    )

    const view = render(
      <ShareSettingsDialog
        mode="create"
        files={[target({ documentId: 1, name: 'A.pdf' }), target({ documentId: 2, name: 'B.pdf' })]}
        onClose={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(mockedCreateShare).toHaveBeenCalledTimes(1))

    view.unmount()
    resolveFirst(share({ documentId: 1 })) // 서버는 이미 이 공유를 성공적으로 만들었다.
    await new Promise((resolve) => setTimeout(resolve, 0))

    // 이 Module에는애초에 "되돌리기" API 호출이 전혀 없다 - createShare가 그
    // 파일에 대해 정확히 한 번만 호출됐고(재시도/중복 없음), unshare 같은 어떤
    // 취소 API도 호출되지 않았음을 직접 확인한다("Keep completed shares
    // intact").
    expect(mockedCreateShare).toHaveBeenCalledTimes(1)
    expect(mockedCreateShare).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ documentId: 1 }),
      expect.any(AbortSignal),
    )
  })

  it('does not close on Escape while a submission is in flight, matching the disabled Close button', async () => {
    mockedCreateShare.mockImplementation(() => new Promise<ShareResponse>(() => {})) // 절대 resolve되지 않음.
    const onClose = vi.fn()

    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={onClose} />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    await user.click(screen.getByRole('button', { name: '공유하기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '닫기' })).toBeDisabled())

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on Escape when not submitting, unchanged from before', async () => {
    const onClose = vi.fn()
    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={onClose} />)

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('prevents a duplicate batch dispatch from two rapid submits before the first re-render lands', async () => {
    mockedCreateShare.mockImplementation(() => new Promise<ShareResponse>(() => {}))

    render(<ShareSettingsDialog mode="create" files={[target()]} onClose={vi.fn()} />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/수신자/), 'recipient-b')
    const submitButton = screen.getByRole('button', { name: '공유하기' })
    // 동기적으로 두 번 Click(React가 아직 disabled를 반영하기 전의 경쟁을 흉내낸다).
    fireEvent.click(submitButton)
    fireEvent.click(submitButton)

    await waitFor(() => expect(mockedCreateShare).toHaveBeenCalledTimes(1))
  })
})
