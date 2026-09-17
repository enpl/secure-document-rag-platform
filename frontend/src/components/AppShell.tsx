import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

interface AppShellProps {
  title: string
  children: ReactNode
}

/**
 * Shared shell for every authenticated page - sidebar + top bar + scrollable
 * content column, kept identical across Home and Connection management (task
 * requirement: "Maintain the same shell across pages").
 *
 * Mobile (<=760px, see index.css): the sidebar becomes an off-canvas drawer.
 * It closes on navigation, on Escape, and by clicking the overlay or the
 * sidebar's own close button - always more than one way out.
 */
export function AppShell({ title, children }: AppShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [mobile, setMobile] = useState(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(max-width: 760px)').matches
      : false,
  )
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const wasOpenRef = useRef(false)

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const media = window.matchMedia('(max-width: 760px)')
    const apply = () => {
      setMobile(media.matches)
      if (!media.matches) setMenuOpen(false)
    }
    apply()
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [])

  useEffect(() => {
    if (!mobile || !menuOpen) return
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [mobile, menuOpen])

  useEffect(() => {
    if (!mobile) {
      wasOpenRef.current = false
      return
    }
    if (menuOpen) {
      closeButtonRef.current?.focus()
    } else if (wasOpenRef.current) {
      menuButtonRef.current?.focus()
    }
    wasOpenRef.current = menuOpen
  }, [mobile, menuOpen])

  const closeMenu = () => setMenuOpen(false)
  const mobileHidden = mobile && !menuOpen

  return (
    <div className="app-shell">
      <Sidebar
        open={menuOpen}
        onNavigate={closeMenu}
        onClose={closeMenu}
        mobileHidden={mobileHidden}
        closeButtonRef={closeButtonRef}
      />
      <button
        type="button"
        className={`overlay${menuOpen ? ' overlay--visible' : ''}`}
        aria-label="메뉴 바깥 영역 닫기"
        aria-hidden={!menuOpen}
        tabIndex={-1}
        disabled={!mobile || !menuOpen}
        onClick={closeMenu}
      />
      <div className="main">
        <TopBar
          title={title}
          onOpenMenu={() => setMenuOpen(true)}
          menuOpen={mobile && menuOpen}
          menuButtonRef={menuButtonRef}
        />
        <main className="content">
          <div className="content-inner">{children}</div>
        </main>
      </div>
    </div>
  )
}
