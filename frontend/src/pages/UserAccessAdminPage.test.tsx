import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserAccessAdminPage } from './UserAccessAdminPage'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import { searchAdminUsers, updateUserAccess } from '../api/users'
import type { AdminUser } from '../api/users'
import { ApiError } from '../api/client'

vi.mock('../auth/AuthContext')
vi.mock('../api/useApiClient', () => {
  const stableClient = {}
  return { useApiClient: () => stableClient }
})
vi.mock('../api/users', () => ({ searchAdminUsers: vi.fn(), updateUserAccess: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSearch = vi.mocked(searchAdminUsers)
const mockedUpdate = vi.mocked(updateUserAccess)

const knownUser: AdminUser = {
  id: 7,
  loginId: 'sdv-user-b',
  displayName: '사용자 B',
  maximumClassification: 'INTERNAL',
  active: true,
  authorizationRevision: 3,
  version: 4,
}

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('UserAccessAdminPage', () => {
  it('does not call the ADMIN directory for an ordinary user', () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: false }))

    render(<UserAccessAdminPage />)

    expect(screen.getByText(/관리자만 사용할 수 있습니다/)).toBeInTheDocument()
    expect(mockedSearch).not.toHaveBeenCalled()
  })

  it('loads known login identities and saves a clearance reset with the current version', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedSearch.mockResolvedValue({ items: [knownUser], hasMore: false })
    mockedUpdate.mockResolvedValue({
      ...knownUser,
      maximumClassification: null,
      authorizationRevision: 4,
      version: 5,
    })

    render(<UserAccessAdminPage />)

    await waitFor(() => expect(screen.getByText('sdv-user-b')).toBeInTheDocument())
    expect(screen.getByText('사용자 B')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '등급 초기화' }))

    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledWith(expect.anything(), knownUser, null, true))
    expect(await screen.findByRole('status')).toHaveTextContent('저장됨')
  })

  it('reports an optimistic-lock conflict instead of claiming the stale update succeeded', async () => {
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true }))
    mockedSearch.mockResolvedValue({ items: [knownUser], hasMore: false })
    mockedUpdate.mockRejectedValue(
      new ApiError(409, { code: 'USER_ACCESS_CONFLICT', message: 'conflict', traceId: null }),
    )

    render(<UserAccessAdminPage />)
    await waitFor(() => expect(screen.getByText('sdv-user-b')).toBeInTheDocument())
    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText(/다른 관리자가 먼저 변경했습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('rebinds the draft to the newer reviewed snapshot after conflict and reload', async () => {
    const newer: AdminUser = {
      ...knownUser,
      maximumClassification: 'PUBLIC',
      active: false,
      authorizationRevision: 4,
      version: 5,
    }
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true, subject: 'admin-a' }))
    mockedSearch
      .mockResolvedValueOnce({ items: [knownUser], hasMore: false })
      .mockResolvedValueOnce({ items: [newer], hasMore: false })
    mockedUpdate
      .mockRejectedValueOnce(new ApiError(409, { code: 'USER_ACCESS_CONFLICT', message: 'conflict', traceId: null }))
      .mockResolvedValueOnce({ ...newer, version: 6 })

    render(<UserAccessAdminPage />)
    let row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    await userEvent.selectOptions(within(row).getByRole('combobox'), 'SECRET')
    await userEvent.click(within(row).getAllByRole('button')[0])
    expect(await screen.findByText(/다른 관리자가 먼저 변경했습니다/)).toBeInTheDocument()

    const reload = screen.getByRole('button', { name: '검색' })
    await waitFor(() => expect(reload).toBeEnabled())
    await userEvent.click(reload)
    row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    await waitFor(() => expect(within(row).getByRole('combobox')).toHaveValue('PUBLIC'))
    expect(within(row).getByRole('checkbox')).not.toBeChecked()

    await userEvent.click(within(row).getAllByRole('button')[0])
    await waitFor(() => expect(mockedUpdate).toHaveBeenLastCalledWith(expect.anything(), newer, 'PUBLIC', false))
  })

  it('rebinds an edited draft when an ordinary reload returns a newer version of the same id', async () => {
    const newer: AdminUser = { ...knownUser, maximumClassification: 'PUBLIC', active: false, version: 5 }
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true, subject: 'admin-a' }))
    mockedSearch
      .mockResolvedValueOnce({ items: [knownUser], hasMore: false })
      .mockResolvedValueOnce({ items: [newer], hasMore: false })

    render(<UserAccessAdminPage />)
    let row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    await userEvent.selectOptions(within(row).getByRole('combobox'), 'SECRET')
    const reload = screen.getByRole('button', { name: '검색' })
    await waitFor(() => expect(reload).toBeEnabled())
    await userEvent.click(reload)

    row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    await waitFor(() => expect(within(row).getByRole('combobox')).toHaveValue('PUBLIC'))
    expect(within(row).getByRole('checkbox')).not.toBeChecked()
  })

  it('does not regress a saved row when an older list response resolves afterwards', async () => {
    let resolveOldList!: (value: { items: AdminUser[]; hasMore: boolean }) => void
    const oldList = new Promise<{ items: AdminUser[]; hasMore: boolean }>((resolve) => {
      resolveOldList = resolve
    })
    const saved: AdminUser = { ...knownUser, maximumClassification: 'PUBLIC', version: 5 }
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true, subject: 'admin-a' }))
    mockedSearch.mockResolvedValueOnce({ items: [knownUser], hasMore: false }).mockReturnValueOnce(oldList)
    mockedUpdate.mockResolvedValue(saved)

    render(<UserAccessAdminPage />)
    const row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    await userEvent.click(screen.getAllByRole('button')[0])
    await userEvent.selectOptions(within(row).getByRole('combobox'), 'PUBLIC')
    await userEvent.click(within(row).getAllByRole('button')[0])
    await waitFor(() => expect(within(row).getByRole('combobox')).toHaveValue('PUBLIC'))

    resolveOldList({ items: [knownUser], hasMore: false })
    await Promise.resolve()
    expect(within(row).getByRole('combobox')).toHaveValue('PUBLIC')
  })

  it('suppresses duplicate row writes and does not show success for a failed reset', async () => {
    let rejectUpdate!: (reason: unknown) => void
    const pending = new Promise<AdminUser>((_resolve, reject) => {
      rejectUpdate = reject
    })
    mockedUseAuth.mockReturnValue(asAuth({ isAdmin: true, subject: 'admin-a' }))
    mockedSearch.mockResolvedValue({ items: [knownUser], hasMore: false })
    mockedUpdate.mockReturnValue(pending)

    render(<UserAccessAdminPage />)
    const row = await screen.findByText('sdv-user-b').then((label) => label.closest('li') as HTMLElement)
    const reset = within(row).getAllByRole('button')[1]
    await userEvent.click(reset)
    await userEvent.click(reset)
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(within(row).getAllByRole('button')[0]).toBeDisabled()
    expect(reset).toBeDisabled()

    rejectUpdate(new Error('network'))
    expect(await screen.findByText(/사용자 접근 설정을 저장하지 못했습니다/)).toBeInTheDocument()
    expect(within(row).getByRole('combobox')).toHaveValue('INTERNAL')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
