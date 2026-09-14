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
  it('hides Connection management from a USER and disables Find documents for everyone', () => {
    renderSidebar({ isAdmin: false, email: 'user@example.com', subject: 'user-1', logout: vi.fn() })

    expect(screen.queryByRole('link', { name: '연결 관리' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /문서 찾기/ })).toBeDisabled()
  })

  it('shows Connection management for an ADMIN', () => {
    renderSidebar({ isAdmin: true, email: 'admin@example.com', subject: 'admin-1', logout: vi.fn() })

    expect(screen.getByRole('link', { name: '연결 관리' })).toBeInTheDocument()
  })

  it('calls logout when the logout button is clicked', async () => {
    const logout = vi.fn()
    renderSidebar({ isAdmin: false, email: 'user@example.com', subject: 'user-1', logout })

    await userEvent.setup().click(screen.getByRole('button', { name: '로그아웃' }))

    expect(logout).toHaveBeenCalledTimes(1)
  })
})
