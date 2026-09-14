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
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
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
})
