import { createContext, useContext, useMemo } from 'react'
import type { ReactNode } from 'react'

/**
 * Test-only double for `./AuthContext`, used ONLY by the Playwright harness
 * (frontend/e2e). vite.config.ts aliases every import path ending in
 * "auth/AuthContext" to this file exclusively under `--mode e2e` - a normal
 * `npm run dev`/`npm run build`/`npm test` never resolves here and keeps
 * using the real `AuthContext.tsx`/`keycloak.ts` unchanged.
 *
 * This never talks to Keycloak/Google/the backend. The harness sets the
 * simulated identity via `window.__SDV_E2E_AUTH__` (through Playwright's
 * `page.addInitScript`, so it exists before this module ever runs) and every
 * backend call is separately intercepted with `page.route()` - this double
 * only has to make `useAuth()` resolve immediately, not implement OIDC.
 */

export type Role = 'USER' | 'ADMIN'
export type AuthStatus = 'initializing' | 'authenticated' | 'unauthenticated' | 'error'

export interface AuthState {
  status: AuthStatus
  subject: string | null
  email: string | null
  roles: Role[]
  isAdmin: boolean
  login: () => void
  logout: () => void
  getAccessToken: () => Promise<string | null>
}

export interface E2EAuthConfig {
  status: AuthStatus
  subject: string
  email: string
  roles: Role[]
}

declare global {
  interface Window {
    __SDV_E2E_AUTH__?: E2EAuthConfig
  }
}

const DEFAULT_CONFIG: E2EAuthConfig = {
  status: 'authenticated',
  subject: 'e2e-user',
  email: 'e2e-user@example.invalid',
  roles: ['USER'],
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const config = (typeof window !== 'undefined' && window.__SDV_E2E_AUTH__) || DEFAULT_CONFIG

  const value = useMemo<AuthState>(
    () => ({
      status: config.status,
      subject: config.status === 'authenticated' ? config.subject : null,
      email: config.status === 'authenticated' ? config.email : null,
      roles: config.status === 'authenticated' ? config.roles : [],
      isAdmin: config.status === 'authenticated' && config.roles.includes('ADMIN'),
      login: () => {},
      logout: () => {},
      getAccessToken: async () => (config.status === 'authenticated' ? 'e2e-harness-fake-token' : null),
    }),
    [config.status, config.subject, config.email, config.roles],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
