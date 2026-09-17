import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { json, stubDefaultApi } from '../fixtures/mockApi'

const VIEW_ONLY_ITEM = {
  documentId: 101,
  sourceId: 1,
  name: 'view-only-정책.pdf',
  mimeType: 'application/pdf',
  sourceVersion: 'v1',
  modifiedAt: '2026-09-01T00:00:00Z',
  indexStatus: 'INDEXED',
  sourceVersionCurrent: true,
  downloadable: true,
  viewUrl: 'https://drive.google.com/file/d/example/view',
  shareId: 501,
  // No 'DOWNLOAD' - the publisher shared VIEW only (M16C `allowedActions`).
  allowedActions: ['VIEW'],
}

test('view-only-no-download', async ({ page }) => {
  await signInAs(page)
  await stubDefaultApi(page)
  await page.route('**/api/rag/files**', (route) =>
    json(route, { items: [VIEW_ONLY_ITEM], hasMore: false, partial: false }),
  )
  await page.goto('/files')

  await expect(page.getByText('view-only-정책.pdf')).toBeVisible()
  // The SDV download action must be entirely absent for a VIEW-only share -
  // not merely disabled, since a hidden re-enable would be a client-side
  // authorization bypass (allowedActions is a hint the endpoint re-checks,
  // but the UI itself must never invite a call the server will only reject).
  await expect(page.getByRole('button', { name: 'SDV 다운로드' })).toHaveCount(0)
  // The unrelated "open the Google original" link is a separate, ungated
  // path (Google's own permission check) and may still legitimately appear.
  await expect(page.getByRole('link', { name: 'Google에서 원본 열기' })).toBeVisible()
})
