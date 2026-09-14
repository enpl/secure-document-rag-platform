import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ensureKeycloakInitialized, keycloak } from './keycloak'

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
  /**
   * Returns a currently-valid access token, refreshing it first if it is
   * within 30s of expiry. Returns null if the user is not authenticated. On a
   * refresh failure (e.g. the Keycloak session itself expired) it redirects to
   * login instead of returning a stale/invalid token.
   */
  getAccessToken: () => Promise<string | null>
}

const AuthContext = createContext<AuthState | null>(null)

const RECOGNIZED_ROLES: readonly Role[] = ['USER', 'ADMIN']

function currentRoles(): Role[] {
  const token = keycloak.tokenParsed
  const realmRoles = token?.realm_access?.roles ?? []
  const clientRoles = token?.resource_access?.['sdv-backend']?.roles ?? []
  const combined = new Set([...realmRoles, ...clientRoles])
  return RECOGNIZED_ROLES.filter((role) => combined.has(role))
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('initializing')
  const [subject, setSubject] = useState<string | null>(null)
  const [email, setEmail] = useState<string | null>(null)
  const [roles, setRoles] = useState<Role[]>([])

  useEffect(() => {
    let cancelled = false

    function applyAuthenticatedState() {
      if (cancelled) return
      setSubject(keycloak.subject ?? null)
      setEmail((keycloak.tokenParsed?.email as string | undefined) ?? null)
      setRoles(currentRoles())
      setStatus('authenticated')
    }

    keycloak.onAuthSuccess = applyAuthenticatedState
    keycloak.onAuthRefreshSuccess = applyAuthenticatedState
    keycloak.onAuthLogout = () => {
      if (cancelled) return
      setSubject(null)
      setEmail(null)
      setRoles([])
      setStatus('unauthenticated')
    }
    // 만료된 Access Token으로 계속 API를 호출하지 않도록, 만료 즉시 한 번 더 갱신을
    // 시도한다 - 실패하면(Keycloak Session 자체가 끝남) 다시 로그인해야 한다.
    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.login())
    }

    ensureKeycloakInitialized()
      .then((authenticated) => {
        if (cancelled) return
        if (authenticated) {
          applyAuthenticatedState()
        } else {
          setStatus('unauthenticated')
        }
      })
      .catch(() => {
        if (cancelled) return
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      status,
      subject,
      email,
      roles,
      isAdmin: roles.includes('ADMIN'),
      login: () => {
        void keycloak.login({ redirectUri: `${window.location.origin}/` })
      },
      logout: () => {
        void keycloak.logout({ redirectUri: `${window.location.origin}/` })
      },
      getAccessToken: async () => {
        if (!keycloak.authenticated) {
          return null
        }
        try {
          await keycloak.updateToken(30)
        } catch {
          keycloak.login()
          return null
        }
        return keycloak.token ?? null
      },
    }),
    [status, subject, email, roles],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// AuthProvider(Component)와 useAuth(Hook)를 한 파일에 함께 두는 것은 React
// Context의 표준적인 관례다 - react-refresh는 Fast Refresh 최적화를 위해 파일당
// Component Export만 권장하지만, 여기서 분리하면 Provider와 그 Hook이 다른
// 파일에 흩어져 오히려 읽기 어려워진다.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
