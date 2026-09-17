import type { Ref } from 'react'

interface TopBarProps {
  title: string
  onOpenMenu: () => void
  menuOpen: boolean
  menuButtonRef: Ref<HTMLButtonElement>
}

export function TopBar({ title, onOpenMenu, menuOpen, menuButtonRef }: TopBarProps) {
  return (
    <header className="topbar">
      <button
        type="button"
        className="btn topbar__menu-button"
        onClick={onOpenMenu}
        aria-label="메뉴 열기"
        aria-controls="primary-sidebar"
        aria-expanded={menuOpen}
        ref={menuButtonRef}
      >
        ☰
      </button>
      <span className="topbar__title">{title}</span>
      <span aria-hidden="true" />
    </header>
  )
}
