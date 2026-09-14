/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Keycloak server origin, e.g. http://localhost:8180 - no trailing slash. */
  readonly VITE_KEYCLOAK_URL: string
  readonly VITE_KEYCLOAK_REALM: string
  /** Public browser OIDC client (Authorization Code + PKCE, no secret). */
  readonly VITE_KEYCLOAK_CLIENT_ID: string
  /**
   * M16A follow-up - set to the literal string "true" only by
   * scripts/testbed/start-testbed.ps1. Gates whether testbed-only UI (the
   * read diagnostic panel) is even rendered - the backend's own
   * @Profile("testbed") + sdv.testbed.diagnostics.enabled double gate
   * remains the actual authority regardless of this value.
   */
  readonly VITE_TESTBED_MODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
