import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

test('login-bootstrap', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)
  // Override the default READY stub: the very first bootstrap call fails,
  // exercising SessionBootstrap's (R3) visible-failure + manual-retry path
  // rather than the happy path already covered incidentally by other tests.
  let calls = 0
  await page.route('**/api/me', (route) => {
    calls += 1
    if (calls === 1) return json(route, { registryStatus: 'REGISTRY_UNAVAILABLE' })
    return json(route, { registryStatus: 'READY' })
  })
  await page.goto('/')

  await expect(page.getByRole('alert')).toContainText('SDV 사용자 등록을 확인하지 못했습니다')
  const retry = page.getByRole('button', { name: '다시 확인' })
  await expect(retry).toBeVisible()

  await retry.click()

  // READY renders nothing (SessionBootstrap returns null) - the banner must
  // disappear and the underlying route's own content must be reachable.
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '업무 문서를 찾고 질문해 보세요.' })).toBeVisible()
  expect(calls).toBe(2)
})
