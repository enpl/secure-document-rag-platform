import { NavLink } from 'react-router-dom'
import type { Ref } from 'react'
import { useAuth } from '../auth/AuthContext'

interface SidebarProps {
  /** Whether the mobile off-canvas drawer is open (ignored above the 760px breakpoint - CSS-gated). */
  open: boolean
  /** Called after any navigating click - lets the mobile shell close the drawer. */
  onNavigate: () => void
  /** Explicit close control for the mobile off-canvas drawer (visible only under 760px, CSS-gated). */
  onClose: () => void
  mobileHidden: boolean
  closeButtonRef: Ref<HTMLButtonElement>
}

/**
 * Approved sidebar layout: branding, "새 질문", "문서 찾기"(M16B - Metadata
 * Discovery, `GET /api/rag/files`), "내 Drive"(M16C - 소유자 전용 개인 연결/
 * 선택 공유, 누구나), ADMIN 전용 "연결 관리"/"공유 자료 관리", 계정/로그아웃.
 * 영구 대화 기록, 업로드/Vault, 임의 Provider 선택기, Admin 대시보드는
 * 의도적으로 없다(CLAUDE.md 범위 제한).
 */
export function Sidebar({ open, onNavigate, onClose, mobileHidden, closeButtonRef }: SidebarProps) {
  const { isAdmin, email, logout } = useAuth()

  return (
    <aside
      id="primary-sidebar"
      className={`sidebar${open ? ' sidebar--open' : ''}`}
      aria-label="주요 메뉴"
      aria-hidden={mobileHidden || undefined}
      inert={mobileHidden || undefined}
    >
      <div className="sidebar__brand-row">
        <span className="sidebar__brand">SDV</span>
        <button
          type="button"
          className="btn sidebar__close"
          onClick={onClose}
          aria-label="메뉴 닫기"
          ref={closeButtonRef}
        >
          닫기
        </button>
      </div>

      <nav className="sidebar__nav" aria-label="탐색">
        <NavLink to="/" end className="sidebar__link" onClick={onNavigate}>
          새 질문
        </NavLink>

        <NavLink to="/files" className="sidebar__link" onClick={onNavigate}>
          문서 찾기
        </NavLink>

        <NavLink to="/my-drive" className="sidebar__link" onClick={onNavigate}>
          내 Drive
        </NavLink>

        {isAdmin && (
          <NavLink to="/admin/users" className="sidebar__link" onClick={onNavigate}>
            사용자 접근
          </NavLink>
        )}

        {isAdmin && (
          <NavLink to="/admin/sources" className="sidebar__link" onClick={onNavigate}>
            연결 관리
          </NavLink>
        )}

        {isAdmin && (
          <NavLink to="/admin/shares" className="sidebar__link" onClick={onNavigate}>
            공유 자료 관리
          </NavLink>
        )}
        {isAdmin && (
          <NavLink to="/admin/audits" className="sidebar__link" onClick={onNavigate}>
            감사 로그
          </NavLink>
        )}
        {isAdmin && (
          <NavLink to="/admin/security" className="sidebar__link" onClick={onNavigate}>
            보안 발견
          </NavLink>
        )}
      </nav>

      <div className="sidebar__footer">
        <div className="sidebar__account">{email ?? '로그인된 사용자'}</div>
        <button type="button" className="btn" onClick={logout}>
          로그아웃
        </button>
      </div>
    </aside>
  )
}
