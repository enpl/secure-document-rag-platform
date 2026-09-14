import { useEffect, useState } from 'react'
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

  useEffect(() => {
    if (!menuOpen) return
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [menuOpen])

  return (
    <div className="app-shell">
      <Sidebar open={menuOpen} onNavigate={() => setMenuOpen(false)} onClose={() => setMenuOpen(false)} />
      <button
        type="button"
        className={`overlay${menuOpen ? ' overlay--visible' : ''}`}
        aria-hidden={!menuOpen}
        tabIndex={-1}
        onClick={() => setMenuOpen(false)}
      />
      <div className="main">
        <TopBar title={title} onOpenMenu={() => setMenuOpen(true)} />
        <main className="content">
          <div className="content-inner">{children}</div>
        </main>
      </div>
    </div>
  )
}
