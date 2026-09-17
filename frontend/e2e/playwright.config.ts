import { defineConfig } from '@playwright/test'

/**
 * scripts/harness gate 08's real-browser adapter (docs/harness/GATE_MATRIX.md
 * "실제 브라우저 adapter 계약"). Never points at a real backend/Keycloak/Google -
 * every test serves the app from an isolated `vite build --mode e2e` bundle
 * (frontend/../auth/AuthContext.e2e.tsx double, see vite.config.ts) and mocks
 * every `/api/**` call with `page.route()`. Invoked only through
 * `frontend/e2e/run-harness.mjs`, never directly by `npm test`.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // run-harness.mjs sets SDV_E2E_REPORT_FILE to a path namespaced by its own
  // --run-id so concurrent invocations (or a stale file from a previous run)
  // can never collide with or be mistaken for this run's own report. A
  // direct manual `npx playwright test` (no run-harness.mjs) falls back to a
  // fixed path purely for local debugging - never read by the real adapter.
  reporter: [['json', { outputFile: process.env.SDV_E2E_REPORT_FILE ?? '.report/manual/results.json' }]],
  outputDir: '.report/artifacts',
  use: {
    // "localhost", not "127.0.0.1" - `vite preview` binds the IPv6 loopback
    // by default on this host, and only "localhost" resolves to it here.
    baseURL: 'http://localhost:4351',
    viewport: { width: 1280, height: 800 },
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    // A service worker could intercept fetch() on its own terms and is a
    // separate code path from page-level `page.route()` - block it outright
    // rather than rely on route interception to also cover it correctly.
    serviceWorkers: 'block',
  },
  webServer: {
    // Builds the isolated e2e bundle and serves it as static files. Vite's
    // `preview` server otherwise inherits `server.proxy` by default -
    // vite.config.ts explicitly sets `preview.proxy = {}` under `--mode e2e`
    // so this never forwards an unmocked `/api/**` request to a real backend.
    command: 'npm run build:e2e && npm run preview:e2e',
    url: 'http://localhost:4351',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
