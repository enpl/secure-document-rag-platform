import type { Page } from '@playwright/test'

export type E2ERole = 'USER' | 'ADMIN'

/**
 * Simulates an already-authenticated session for `AuthContext.e2e.tsx`
 * (see vite.config.ts's `--mode e2e` alias) - never a real Keycloak login.
 * Must be called before `page.goto()` so the init script runs before the
 * app's own bundle does.
 */
export async function signInAs(page: Page, options: { subject?: string; email?: string; roles?: E2ERole[] } = {}) {
  const config = {
    status: 'authenticated' as const,
    subject: options.subject ?? 'e2e-user',
    email: options.email ?? 'e2e-user@example.invalid',
    roles: options.roles ?? (['USER'] as E2ERole[]),
  }
  await page.addInitScript((value) => {
    Object.defineProperty(window, '__SDV_E2E_AUTH__', { value, configurable: true })
  }, config)
}
