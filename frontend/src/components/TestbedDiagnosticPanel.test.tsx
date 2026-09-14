import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TestbedDiagnosticPanel } from './TestbedDiagnosticPanel'
import { checkTestbedDiagnosticsStatus, checkTestbedRead } from '../api/testbedDiagnostics'
import type { TestbedReadDiagnosticResponse } from '../api/testbedDiagnostics'
import { ApiError } from '../api/client'
import type { SourceResponse } from '../api/sources'

vi.mock('../api/useApiClient', () => ({
  useApiClient: () => ({}),
}))
vi.mock('../api/testbedDiagnostics', () => ({
  checkTestbedRead: vi.fn(),
  checkTestbedDiagnosticsStatus: vi.fn(),
}))

const mockedCheck = vi.mocked(checkTestbedRead)
const mockedStatus = vi.mocked(checkTestbedDiagnosticsStatus)

const activeSource: SourceResponse = {
  id: 42,
  type: 'GOOGLE_DRIVE',
  name: '테스트베드 Source',
  status: 'ACTIVE',
  lastSyncAt: null,
  credentialPresent: true,
}

const disabledSource: SourceResponse = {
  id: 43,
  type: 'GOOGLE_DRIVE',
  name: '연결 해제된 Source',
  status: 'DISABLED',
  lastSyncAt: null,
  credentialPresent: false,
}

beforeEach(() => {
  vi.clearAllMocks()
})

async function renderAvailableAndOpen(sources: SourceResponse[] = [activeSource]) {
  mockedStatus.mockResolvedValue({ enabled: true })
  render(<TestbedDiagnosticPanel sources={sources} />)
  await waitFor(() => expect(screen.getByLabelText('대상 Source')).toBeInTheDocument())
}

async function fillAndSubmit(sourceId: number, fileId = 'allowed-file-1') {
  const user = userEvent.setup()
  await user.selectOptions(screen.getByLabelText('대상 Source'), String(sourceId))
  await user.type(screen.getByLabelText('허용된 Google 파일 ID'), fileId)
  await user.click(screen.getByRole('button', { name: '읽기 확인' }))
}

describe('TestbedDiagnosticPanel availability check (safety correction)', () => {
  it('checks a dedicated status endpoint before showing the form, and shows honest "unavailable" only when THAT call fails', async () => {
    mockedStatus.mockRejectedValue(new ApiError(500, { code: 'INTERNAL_ERROR', message: 'x', traceId: null }))

    render(<TestbedDiagnosticPanel sources={[activeSource]} />)

    await waitFor(() => expect(screen.getByText(/사용할 수 없습니다/)).toBeInTheDocument())
    expect(screen.queryByLabelText('대상 Source')).not.toBeInTheDocument()
    expect(mockedCheck).not.toHaveBeenCalled()
  })

  it('shows the form once the status check succeeds, and treats a later read-check 500 as a REAL error, not "disabled"', async () => {
    await renderAvailableAndOpen()
    mockedCheck.mockRejectedValue(new ApiError(500, { code: 'INTERNAL_ERROR', message: 'boom', traceId: null }))

    await fillAndSubmit(activeSource.id)

    await waitFor(() => expect(screen.getByText(/실제 오류가 발생했습니다/)).toBeInTheDocument())
    // 예전 결함: 이 500을 "비활성화"로 표시했다 - 지금은 실제 오류로 표시해야 한다.
    expect(screen.queryByText(/사용할 수 없습니다/)).not.toBeInTheDocument()
  })
})

describe('TestbedDiagnosticPanel result reporting', () => {
  it('reports an actual verified read result, not a canned success', async () => {
    await renderAvailableAndOpen()
    const response: TestbedReadDiagnosticResponse = {
      success: true,
      outcome: 'VERIFIED',
      bytesRead: 13,
      versionVerified: true,
      reason: null,
    }
    mockedCheck.mockResolvedValue(response)

    await fillAndSubmit(activeSource.id)

    await waitFor(() => expect(screen.getByText(/읽기 성공/)).toBeInTheDocument())
    expect(screen.getByText(/outcome: VERIFIED/)).toBeInTheDocument()
    expect(screen.getByText(/읽은 바이트 수: 13/)).toBeInTheDocument()
    expect(mockedCheck).toHaveBeenCalledWith(expect.anything(), activeSource.id, 'allowed-file-1')
  })

  it('shows a denial outcome as a failure, never as success', async () => {
    await renderAvailableAndOpen()
    mockedCheck.mockResolvedValue({
      success: false,
      outcome: 'FILE_NOT_ALLOWLISTED',
      bytesRead: 0,
      versionVerified: false,
      reason: '요청한 File ID가 허용 목록에 없습니다.',
    })

    await fillAndSubmit(activeSource.id, 'some-other-file')

    await waitFor(() => expect(screen.getByText(/읽기 거부\/실패/)).toBeInTheDocument())
    expect(screen.getByText(/FILE_NOT_ALLOWLISTED/)).toBeInTheDocument()
  })

  it('prevents a duplicate submit while a check is already in flight', async () => {
    await renderAvailableAndOpen()
    let resolveCheck: (value: TestbedReadDiagnosticResponse) => void = () => {}
    mockedCheck.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCheck = resolve
        }),
    )

    await fillAndSubmit(activeSource.id)

    const checkingButton = screen.getByRole('button', { name: '확인 중...' })
    expect(checkingButton).toBeDisabled()
    expect(mockedCheck).toHaveBeenCalledTimes(1)

    resolveCheck({ success: true, outcome: 'VERIFIED', bytesRead: 1, versionVerified: true, reason: null })
    await waitFor(() => expect(screen.getByRole('button', { name: '읽기 확인' })).toBeInTheDocument())
  })
})

describe('TestbedDiagnosticPanel negative-test usability (runbook read-denial step)', () => {
  it('offers a DISABLED source in the dropdown so the read-denial checklist step is actually usable', async () => {
    await renderAvailableAndOpen([activeSource, disabledSource])

    const option = screen.getByRole('option', { name: /연결 해제된 Source.*연결 해제됨/ })
    expect(option).toBeInTheDocument()
  })

  it('reports the backend denial for a DISABLED source instead of pretending it cannot be selected', async () => {
    await renderAvailableAndOpen([activeSource, disabledSource])
    mockedCheck.mockResolvedValue({
      success: false,
      outcome: 'SOURCE_NOT_ACTIVE',
      bytesRead: 0,
      versionVerified: false,
      reason: '이 Source는 Google Drive 종류가 아니거나 현재 ACTIVE 상태가 아닙니다.',
    })

    await fillAndSubmit(disabledSource.id)

    await waitFor(() => expect(screen.getByText(/SOURCE_NOT_ACTIVE/)).toBeInTheDocument())
  })
})
