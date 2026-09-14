interface TopBarProps {
  title: string
  onOpenMenu: () => void
}

export function TopBar({ title, onOpenMenu }: TopBarProps) {
  return (
    <header className="topbar">
      <button
        type="button"
        className="btn topbar__menu-button"
        onClick={onOpenMenu}
        aria-label="메뉴 열기"
      >
        ☰
      </button>
      <span className="topbar__title">{title}</span>
      <span aria-hidden="true" />
    </header>
  )
}
