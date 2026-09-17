/// <reference types="node" />
import { StrictMode } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import { AppShell } from './AppShell'

vi.mock('../auth/AuthContext')

const mockedUseAuth = vi.mocked(useAuth)

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AppShell shared logout reachability', () => {
  it('renders one common logout action with long content and remains usable in StrictMode', async () => {
    const logout = vi.fn()
    mockedUseAuth.mockReturnValue(
      asAuth({ isAdmin: false, email: 'sdv-user-a@example.invalid', subject: 'sdv-user-a', logout }),
    )

    render(
      <StrictMode>
        <MemoryRouter>
          <AppShell title="긴 화면">
            {Array.from({ length: 100 }, (_, index) => <p key={index}>합성 행 {index}</p>)}
          </AppShell>
        </MemoryRouter>
      </StrictMode>,
    )

    const logoutButton = screen.getByRole('button', { name: '로그아웃' })
    expect(logoutButton).toBeVisible()
    expect(screen.getAllByRole('button', { name: '로그아웃' })).toHaveLength(1)
    await userEvent.setup().click(logoutButton)
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('keeps the same logout action in the existing mobile drawer', async () => {
    const logout = vi.fn()
    mockedUseAuth.mockReturnValue(
      asAuth({ isAdmin: true, email: 'sdv-admin@example.invalid', subject: 'sdv-admin', logout }),
    )

    const { container } = render(
      <MemoryRouter>
        <AppShell title="관리 화면"><div>합성 관리자 내용</div></AppShell>
      </MemoryRouter>,
    )

    await userEvent.setup().click(screen.getByRole('button', { name: '메뉴 열기' }))
    expect(container.querySelector('.sidebar')).toHaveClass('sidebar--open')
    await userEvent.setup().click(screen.getByRole('button', { name: '로그아웃' }))
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('pins the shell to the viewport and gives navigation its own bounded scroll area', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

    expect(css).toMatch(/\.app-shell\s*\{[^}]*height:\s*100svh;[^}]*overflow:\s*hidden;/s)
    expect(css).toMatch(/\.sidebar\s*\{[^}]*height:\s*100svh;/s)
    expect(css).toMatch(/\.sidebar__nav\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;/s)
    expect(css).toMatch(/\.sidebar__footer\s*\{[^}]*flex-shrink:\s*0;/s)
    expect(css).toMatch(/\.main\s*\{[^}]*min-height:\s*0;/s)
  })
})
