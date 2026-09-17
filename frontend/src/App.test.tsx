import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { useAuth } from './auth/AuthContext'
import type { AuthState } from './auth/AuthContext'

const get = vi.fn()
const stableClient = { get }

vi.mock('./auth/AuthContext')
vi.mock('./api/useApiClient', () => ({ useApiClient: () => stableClient }))
vi.mock('./components/AppShell', () => ({ AppShell: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
vi.mock('./pages/HomePage', () => ({ HomePage: () => <div>default-home</div> }))
vi.mock('./pages/SourcesPage', () => ({ SourcesPage: () => null }))
vi.mock('./pages/SharedMaterialsAdminPage', () => ({ SharedMaterialsAdminPage: () => null }))
vi.mock('./features/rag/FileDiscoveryPage', () => ({ FileDiscoveryPage: () => null }))
vi.mock('./features/sources/MyDrivePage', () => ({ MyDrivePage: () => null }))
vi.mock('./features/audit/AuditLogPage', () => ({ AuditLogPage: () => null }))
vi.mock('./features/security/SecurityDashboardPage', () => ({ SecurityDashboardPage: () => null }))
vi.mock('./pages/UserAccessAdminPage', () => ({ UserAccessAdminPage: () => null }))

const mockedUseAuth = vi.mocked(useAuth)

function auth(subject: string): AuthState {
  return {
    status: 'authenticated',
    subject,
    email: null,
    roles: ['USER'],
    isAdmin: false,
    login: vi.fn(),
    logout: vi.fn(),
    getAccessToken: vi.fn(),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('authenticated identity bootstrap', () => {
  it('materializes the validated login from the default home without navigation, a question, or Google', async () => {
    mockedUseAuth.mockReturnValue(auth('user-b'))
    get.mockResolvedValue({ registryStatus: 'CLEARANCE_UNSET' })

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByText('default-home')).toBeInTheDocument()
    await waitFor(() => expect(get).toHaveBeenCalledWith('/me', expect.any(AbortSignal)))
    expect(await screen.findByRole('status')).toHaveTextContent(/clearance/i)
  })

  it('shows an honest failure and performs only a bounded explicit retry', async () => {
    mockedUseAuth.mockReturnValue(auth('user-b'))
    get.mockRejectedValueOnce(new Error('backend unavailable')).mockResolvedValueOnce({ registryStatus: 'READY' })

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/등록을 확인하지 못했습니다/)
    expect(get).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: '다시 확인' }))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('ignores an old account completion after the authenticated subject changes', async () => {
    let resolveOld!: (value: { registryStatus: 'CLEARANCE_UNSET' }) => void
    const oldRequest = new Promise<{ registryStatus: 'CLEARANCE_UNSET' }>((resolve) => {
      resolveOld = resolve
    })
    get.mockReturnValueOnce(oldRequest).mockResolvedValueOnce({ registryStatus: 'DISABLED' })
    mockedUseAuth.mockReturnValue(auth('user-a'))
    const view = render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    mockedUseAuth.mockReturnValue(auth('user-b'))
    view.rerender(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(/비활성화/)

    resolveOld({ registryStatus: 'CLEARANCE_UNSET' })
    await Promise.resolve()
    expect(screen.getByRole('alert')).toHaveTextContent(/비활성화/)
    expect(screen.queryByText(/clearance가 아직/)).not.toBeInTheDocument()
  })

  it('does not duplicate the bootstrap request under StrictMode effect replay', async () => {
    mockedUseAuth.mockReturnValue(auth('user-b'))
    get.mockResolvedValue({ registryStatus: 'CLEARANCE_UNSET' })

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )

    expect(await screen.findByRole('status')).toHaveTextContent(/clearance/i)
    expect(get).toHaveBeenCalledTimes(1)
  })
})
