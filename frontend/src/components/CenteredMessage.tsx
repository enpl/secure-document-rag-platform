import type { ReactNode } from 'react'

/** Full-page centered state used before the authenticated shell is available (init/error/login). */
export function CenteredMessage({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        minHeight: '100svh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: '24px 16px',
        textAlign: 'center',
      }}
    >
      {children}
    </div>
  )
}
