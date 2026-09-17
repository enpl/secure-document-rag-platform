import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { stubDefaultApi } from '../fixtures/mockApi'

// Not one of GATE_MATRIX's 7 required cases - a harness self-check proving
// the catch-all block (frontend/e2e/fixtures/mockApi.ts) actually rejects,
// in a real browser, exactly the two things it must: an `/api/**` call this
// fixture set never decided to stub, and any other-origin request. Neither
// must ever silently reach a real network endpoint.
test('network-isolation: unmocked API and external requests fail closed', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)
  await page.goto('/')

  // /api/admin/shares is deliberately NOT in stubDefaultApi's stub list.
  const unmocked = await page.evaluate(async () => {
    try {
      await fetch('/api/admin/shares')
      return 'reached'
    } catch {
      return 'blocked'
    }
  })
  expect(unmocked).toBe('blocked')

  const external = await page.evaluate(async () => {
    try {
      await fetch('https://evil.example.invalid/x')
      return 'reached'
    } catch {
      return 'blocked'
    }
  })
  expect(external).toBe('blocked')
})
