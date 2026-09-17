/// <reference types="node" />
import { StrictMode } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import type { AuthState } from '../auth/AuthContext'
import { AppShell } from './AppShell'

vi.mock('../auth/AuthContext')

const mockedUseAuth = vi.mocked(useAuth)
const originalMatchMedia = window.matchMedia

function asAuth(partial: Partial<AuthState>): AuthState {
  return partial as unknown as AuthState
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: originalMatchMedia })
  document.head.querySelectorAll('[data-app-shell-test]').forEach((element) => element.remove())
})

describe('AppShell shared logout reachability', () => {
  it('computes both mobile-only controls as hidden in the desktop cascade', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    const style = document.createElement('style')
    style.dataset.appShellTest = 'true'
    style.textContent = css
    document.head.append(style)
    mockedUseAuth.mockReturnValue(
      asAuth({ isAdmin: false, email: 'sdv-user-a@example.invalid', subject: 'opaque-subject', logout: vi.fn() }),
    )

    const { container } = render(
      <MemoryRouter>
        <AppShell title="데스크톱"><div>내용</div></AppShell>
      </MemoryRouter>,
    )

    expect(getComputedStyle(container.querySelector('.topbar__menu-button') as HTMLElement).display).toBe('none')
    expect(getComputedStyle(container.querySelector('.sidebar__close') as HTMLElement).display).toBe('none')
    style.remove()
  })

  it('closes the mobile drawer with Escape and makes the closed drawer inert', async () => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockReturnValue({
        matches: true,
        media: '(max-width: 760px)',
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    })
    mockedUseAuth.mockReturnValue(
      asAuth({ isAdmin: false, email: 'user@example.invalid', subject: 'opaque-subject', logout: vi.fn() }),
    )

    const { container } = render(
      <MemoryRouter>
        <AppShell title="모바일"><div>내용</div></AppShell>
      </MemoryRouter>,
    )
    const opener = screen.getByRole('button', { name: '메뉴 열기' })
    const sidebar = container.querySelector('aside')
    expect(sidebar).toHaveAttribute('inert')

    await userEvent.setup().click(opener)
    expect(sidebar).not.toHaveAttribute('inert')
    expect(screen.getByRole('button', { name: '메뉴 닫기' })).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(sidebar).toHaveAttribute('inert')
    expect(opener).toHaveFocus()
    expect(document.body.style.overflow).toBe('')

    await userEvent.setup().click(opener)
    await userEvent.setup().click(container.querySelector('.overlay') as HTMLElement)
    expect(sidebar).toHaveAttribute('inert')

    await userEvent.setup().click(opener)
    await userEvent.setup().click(screen.getByRole('link', { name: '문서 찾기' }))
    expect(sidebar).toHaveAttribute('inert')
  })

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
