import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'

vi.mock('../auth/AuthContext')

const mockedUseAuth = vi.mocked(useAuth)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

function renderSidebar(partial: Partial<AuthState>) {
  mockedUseAuth.mockReturnValue(asAuth(partial))
  return render(
    <MemoryRouter>
      <Sidebar open={false} onNavigate={vi.fn()} onClose={vi.fn()} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('Sidebar', () => {
  it('hides Connection management from a USER but still offers Find documents (M16B)', () => {
    renderSidebar({ isAdmin: false, email: 'user@example.com', subject: 'user-1', logout: vi.fn() })

    expect(screen.queryByRole('link', { name: '연결 관리' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '문서 찾기' })).toBeInTheDocument()
  })

  it('offers Find documents to an ADMIN too (M16B)', () => {
    renderSidebar({ isAdmin: true, email: 'admin@example.com', subject: 'admin-1', logout: vi.fn() })

    expect(screen.getByRole('link', { name: '문서 찾기' })).toBeInTheDocument()
  })

  it('shows Connection management for an ADMIN', () => {
    renderSidebar({ isAdmin: true, email: 'admin@example.com', subject: 'admin-1', logout: vi.fn() })

    expect(screen.getByRole('link', { name: '연결 관리' })).toBeInTheDocument()
  })

  it('offers My Drive to an ordinary USER, but hides Shared-material management (M16C)', () => {
    renderSidebar({ isAdmin: false, email: 'user@example.com', subject: 'user-1', logout: vi.fn() })

    expect(screen.getByRole('link', { name: '내 Drive' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '공유 자료 관리' })).not.toBeInTheDocument()
  })

  it('offers My Drive and Shared-material management to an ADMIN (M16C)', () => {
    renderSidebar({ isAdmin: true, email: 'admin@example.com', subject: 'admin-1', logout: vi.fn() })

    expect(screen.getByRole('link', { name: '내 Drive' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공유 자료 관리' })).toBeInTheDocument()
  })

  it('calls logout when the logout button is clicked', async () => {
    const logout = vi.fn()
    renderSidebar({ isAdmin: false, email: 'user@example.com', subject: 'user-1', logout })

    await userEvent.setup().click(screen.getByRole('button', { name: '로그아웃' }))

    expect(logout).toHaveBeenCalledTimes(1)
  })
})
