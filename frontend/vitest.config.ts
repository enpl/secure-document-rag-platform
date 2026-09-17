import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

// M16A - kept separate from vite.config.ts (Simplicity First: avoids pulling
// vitest's config-augmentation types into the app's own Vite config just for
// dev/build).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    css: false,
    // frontend/e2e/*.spec.ts are Playwright specs (scripts/harness gate 08's
    // adapter, run only via `npm run test:e2e:harness`) - they use
    // `@playwright/test`'s own `test`/`expect`, not Vitest's, and must never
    // be collected here.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
