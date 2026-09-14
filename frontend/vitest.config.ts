import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

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
  },
})
