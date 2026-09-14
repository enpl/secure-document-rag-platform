import Keycloak from 'keycloak-js'

/**
 * M16A - single Keycloak client instance for the whole app (keycloak-js itself
 * requires this: calling `init()` more than once on one instance throws).
 *
 * Authorization Code + PKCE only - no Implicit flow, no client secret (this is
 * a public browser client, `sdv-frontend` in infra/keycloak/realm-export.json).
 * keycloak-js keeps the resulting tokens purely in this JS instance's memory;
 * it never writes them to localStorage/IndexedDB itself, so we must not add
 * any code here that persists `keycloak.token`.
 */
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL,
  realm: import.meta.env.VITE_KEYCLOAK_REALM,
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
})

let initPromise: Promise<boolean> | null = null

/**
 * Runs `keycloak.init()` exactly once for the lifetime of the page, no matter
 * how many times this is called (React 18 StrictMode intentionally invokes
 * effects twice in development, which would otherwise throw on the second
 * `init()` call).
 *
 * `onLoad: 'check-sso'` silently checks for an existing Keycloak session via
 * `silentCheckSsoRedirectUri` (a hidden iframe loading public/silent-check-sso.html)
 * instead of forcing every page load through a full redirect - the app decides
 * per-route whether to then call `keycloak.login()` (see RequireAuth).
 *
 * `checkLoginIframe` is left off: its periodic hidden-iframe session polling
 * adds cross-origin/SameSite complexity for local dev and is not needed for
 * this slice (a revoked session is still caught on the next token refresh
 * attempt, just not instantly) - a known, documented trade-off, not an
 * oversight.
 */
export function ensureKeycloakInitialized(): Promise<boolean> {
  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      checkLoginIframe: false,
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    })
  }
  return initPromise
}
