import { expect, test } from '@playwright/test'
import { signInAs } from '../fixtures/auth'
import { stubDefaultApi } from '../fixtures/mockApi'

test('desktop-navigation', async ({ page }) => {
  await signInAs(page, { roles: ['ADMIN'] })
  await stubDefaultApi(page)
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/')

  const sidebar = page.locator('#primary-sidebar')
  await expect(sidebar).toBeVisible()
  await expect(sidebar).not.toHaveAttribute('aria-hidden')

  // The off-canvas hamburger is a mobile-only control (CSS-gated) - it must
  // stay hidden at desktop width, where the sidebar is always on screen.
  await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeHidden()

  await expect(page.getByRole('link', { name: '새 질문' })).toBeVisible()
  await expect(page.getByRole('link', { name: '문서 찾기' })).toBeVisible()
  await expect(page.getByRole('link', { name: '내 Drive' })).toBeVisible()
  // ADMIN-only entries must render for an ADMIN session.
  await expect(page.getByRole('link', { name: '사용자 접근' })).toBeVisible()
  await expect(page.getByRole('link', { name: '연결 관리' })).toBeVisible()
  await expect(page.getByRole('link', { name: '공유 자료 관리' })).toBeVisible()

  await page.getByRole('link', { name: '문서 찾기' }).click()
  await expect(page).toHaveURL(/\/files$/)
  await expect(page.locator('.topbar__title')).toHaveText('문서 찾기')

  await page.getByRole('link', { name: '내 Drive' }).click()
  await expect(page).toHaveURL(/\/my-drive$/)
  await expect(page.locator('.topbar__title')).toHaveText('내 Drive')
})
