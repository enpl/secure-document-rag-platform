import type { Page, Route } from '@playwright/test'

// Must match playwright.config.ts's `use.baseURL` exactly.
const ALLOWED_ORIGIN = 'http://localhost:4351'

export function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/**
 * Blocks anything this fixture set did not explicitly decide to allow: any
 * `/api/**` call this test forgot to stub, and any request to an origin
 * other than the isolated preview server itself (same-origin static
 * JS/CSS/HTML for the built app is the only thing let through as-is).
 * Registered FIRST, before every stub below and before each test's own
 * overrides - Playwright tries the most-recently-registered matching
 * `page.route()` handler first, so a later, more specific stub still wins;
 * this one only ever fires for whatever nothing else claimed.
 *
 * This is a browser-level convenience, not an OS/network sandbox: it stops
 * this page from successfully making the request, but it does not, by
 * itself, prove no process on this machine could reach a real network
 * endpoint through some other path outside the browser.
 */
async function blockUnmockedAndExternalRequests(page: Page) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url())
    if (url.origin === ALLOWED_ORIGIN && !url.pathname.startsWith('/api/')) {
      return route.fallback()
    }
    return route.abort('failed')
  })
}

/**
 * Harmless default stubs for the backend calls most authenticated pages make
 * on mount, registered BEFORE `page.goto()`. A test that cares about one
 * specific endpoint registers its own `page.route()` override afterward -
 * Playwright runs the most-recently-registered matching handler first, so
 * later overrides win without needing `route.fallback()` bookkeeping here.
 * No request in this harness ever reaches a real network endpoint.
 */
export async function stubDefaultApi(page: Page) {
  await blockUnmockedAndExternalRequests(page)
  await page.route('**/api/me', (route) => json(route, { registryStatus: 'READY' }))
  await page.route('**/api/shares', (route) => json(route, []))
  await page.route('**/api/sources', (route) => json(route, []))
  await page.route('**/api/admin/sources', (route) => json(route, []))
  await page.route('**/api/rag/files**', (route) => json(route, { items: [], hasMore: false, partial: false }))
}
