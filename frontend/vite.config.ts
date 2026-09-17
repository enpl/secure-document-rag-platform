import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// M16A follow-up - the local testbed (docs/runbooks/M16A_LOCAL_TESTBED.md)
// runs its own backend on a different port (18080). VITE_API_PROXY_TARGET is
// a real process env var set by scripts/testbed/start-testbed.ps1 before
// launching `npm run dev` - it is read here directly via `process.env`
// (this file runs in Node, not the browser) rather than through Vite's
// .env-file loading, so it never touches/overwrites the developer's own
// frontend/.env.local. Outside the testbed, this is unset and the safe
// canonical default (http://localhost:8080) applies unchanged.
const proxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  // `--mode e2e` never reads a developer's own `.env`/`.env.local` at all -
  // the harness-only build must not depend on whatever real values happen to
  // be sitting in this checkout.
  envDir: mode === 'e2e' ? false : undefined,
  plugins: [react()],
  resolve: {
    alias:
      // frontend/e2e (scripts/harness gate 08's real-browser adapter) ONLY -
      // `vite build --mode e2e`/`vite preview --mode e2e` swap every
      // `*/auth/AuthContext` import for the harness-only double so the real
      // OIDC/Keycloak flow is never exercised there; `npm run dev`/`npm run
      // build`/`npm test` never pass `mode: 'e2e'` and are unaffected.
      mode === 'e2e'
        ? [
            {
              find: /^(?:\.\.?\/)*auth\/AuthContext$/,
              replacement: fileURLToPath(new URL('./src/auth/AuthContext.e2e.tsx', import.meta.url)),
            },
          ]
        : [],
  },
  server: {
    // `mode === 'e2e'` never runs `vite dev` at all (only `vite build`/`vite
    // preview`), but the proxy is still explicitly disabled here too, not
    // just left to be unreachable, so nothing in this config depends on that.
    proxy:
      mode === 'e2e'
        ? undefined
        : {
            // same-origin dev proxy to the backend so the browser never needs
            // backend CORS relaxation (CLAUDE.md: avoid broad CORS/permitAll changes).
            // Keycloak itself is called directly by keycloak-js (not proxied) - see
            // README for the exact port/origin layout.
            '/api': {
              target: proxyTarget,
              changeOrigin: true,
            },
          },
  },
  preview: {
    // Vite's own `vite preview` inherits `server.proxy` whenever
    // `preview.proxy` is left unset (`preview?.proxy ?? server.proxy` in
    // vite's own resolved config) - so `--mode e2e` must set an EXPLICIT
    // empty object here, not merely omit this block, or the isolated e2e
    // preview server would silently forward any unmocked `/api/**` request
    // (Playwright's `page.route()` mocks only cover requests it actually
    // intercepts) to whatever `VITE_API_PROXY_TARGET`/`http://localhost:8080`
    // happens to be reachable on this machine at the time.
    proxy: mode === 'e2e' ? {} : undefined,
  },
}))
