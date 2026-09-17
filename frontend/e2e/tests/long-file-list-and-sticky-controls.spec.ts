import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

const SOURCE = {
  id: 1,
  type: 'GOOGLE_DRIVE',
  name: '테스트 드라이브',
  status: 'ACTIVE',
  lastSyncAt: '2026-09-01T00:00:00Z',
  credentialPresent: true,
}

function syntheticFiles(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    documentId: index + 1,
    name: `합성 파일 ${String(index + 1).padStart(3, '0')}.pdf`,
    mimeType: 'application/pdf',
    modifiedAt: '2026-09-01T00:00:00Z',
    indexStatus: 'INDEXED',
  }))
}

test('long-file-list-and-sticky-controls', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)
  await page.route('**/api/sources', (route) => json(route, [SOURCE]))
  await page.route('**/api/sources/1/files**', (route) => json(route, { items: syntheticFiles(50), hasMore: true }))
  await page.goto('/my-drive')

  await page.getByRole('button', { name: '파일 보기' }).click()

  const table = page.locator('.file-table-wrap')
  await expect(table).toBeVisible()
  await expect(page.locator('.file-table tbody tr')).toHaveCount(50)

  // Selecting files reveals the sticky selection toolbar with the real
  // publish action - checking the box alone must not itself share anything.
  await page.getByRole('checkbox', { name: '합성 파일 001.pdf 선택' }).check()
  await page.getByRole('checkbox', { name: '합성 파일 002.pdf 선택' }).check()
  await expect(page.getByText('선택한 파일 2개')).toBeVisible()
  const shareButton = page.getByRole('button', { name: '선택한 파일 공유하기' })
  await expect(shareButton).toBeVisible()

  const toolbar = page.locator('.selection-toolbar')
  const before = await toolbar.boundingBox()
  expect(before).not.toBeNull()

  // `.content` (AppShell's <main>) is the scrolling ancestor - the toolbar's
  // CSS `position: sticky` is relative to it, not to the page or the table.
  await page.locator('.content').evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await page.waitForTimeout(50)

  const after = await toolbar.boundingBox()
  expect(after).not.toBeNull()
  // Sticky means it stays pinned near the top of the viewport instead of
  // scrolling away with the 50-row table - a non-sticky regression would move
  // it far outside the visible viewport height.
  expect(after!.y).toBeGreaterThanOrEqual(0)
  expect(after!.y).toBeLessThan(200)
  await expect(shareButton).toBeInViewport()
  await expect(shareButton).toBeEnabled()
})
