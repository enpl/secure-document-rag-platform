import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

const USER_V1 = {
  id: 7,
  loginId: 'user.seven',
  displayName: '테스트 사용자',
  maximumClassification: 'INTERNAL',
  active: true,
  authorizationRevision: 1,
  version: 1,
}

// Reload reveals another admin's already-committed change: higher version,
// different clearance AND active flag - both edited fields must reseed, not
// just one, and not merely "any change happened".
const USER_V2 = {
  ...USER_V1,
  maximumClassification: 'SECRET',
  active: false,
  authorizationRevision: 2,
  version: 2,
}

test('admin-conflict-reload', async ({ page }) => {
  await signInAs(page, { roles: ['ADMIN'] })
  await stubDefaultApi(page)

  let getCalls = 0
  await page.route('**/api/admin/users**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    getCalls += 1
    return json(route, { items: [getCalls === 1 ? USER_V1 : USER_V2], hasMore: false })
  })

  const patchBodies: unknown[] = []
  let patchCalls = 0
  await page.route('**/api/admin/users/7/access', (route) => {
    patchCalls += 1
    patchBodies.push(route.request().postDataJSON())
    if (patchCalls === 1) {
      // Someone else's committed change landed first (R2's expectedVersion
      // optimistic-concurrency contract) - the backend's fixed ApiErrorBody shape.
      return json(route, { code: 'USER_ACCESS_CONFLICT', message: 'stale expectedVersion', traceId: null }, 409)
    }
    return json(route, { ...USER_V2, active: true, authorizationRevision: 3, version: 3 })
  })

  await page.goto('/admin/users')
  await expect(page.getByText('테스트 사용자')).toBeVisible()

  const row = page.locator('.source-row').filter({ hasText: '테스트 사용자' })
  const select = row.getByLabel('최대 등급')
  const activeCheckbox = row.getByRole('checkbox', { name: '계정 접근 활성' })
  await expect(select).toHaveValue('INTERNAL')
  await expect(activeCheckbox).toBeChecked()

  // Edit away from the server's current values before the rejected save.
  await select.selectOption('CONFIDENTIAL')
  await row.getByRole('button', { name: '저장' }).click()

  await expect(page.getByText('다른 관리자가 먼저 변경했습니다. 목록을 다시 불러온 뒤 저장해 주세요.')).toBeVisible()
  // The row must not silently show a saved state after a rejected write.
  await expect(page.getByText('저장됨')).toHaveCount(0)
  expect(patchBodies[0]).toMatchObject({ expectedVersion: 1, maximumClassification: 'CONFIDENTIAL', active: true })

  // Re-query (the message's own instruction: reload the list, then save).
  await page.getByRole('button', { name: '검색' }).click()
  await expect.poll(() => getCalls).toBeGreaterThanOrEqual(2)

  // Both edited fields must reseed from the fresh version-2 snapshot - the
  // stale local draft ('CONFIDENTIAL') and the original server value
  // ('INTERNAL') must both be gone.
  await expect(select).toHaveValue('SECRET')
  await expect(activeCheckbox).not.toBeChecked()

  // One more edit on top of the fresh snapshot, then save again.
  await activeCheckbox.check()
  await row.getByRole('button', { name: '저장' }).click()

  await expect(page.getByText('저장됨')).toBeVisible()
  expect(patchBodies[1]).toMatchObject({ expectedVersion: 2, maximumClassification: 'SECRET', active: true })
})
